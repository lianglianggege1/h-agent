package com.h.backend.memory.infrastructure;

import com.h.backend.memory.application.TurnMessagePort;
import com.h.backend.memory.domain.MemoryScopeKind;
import com.h.backend.memory.domain.MemoryScopePolicy;
import com.h.backend.memory.infrastructure.config.LongTermMemoryProperties;
import com.h.backend.memory.infrastructure.mem0.Mem0ErrorClassifier;
import com.h.backend.memory.infrastructure.mem0.Mem0Gateway;
import com.h.backend.memory.infrastructure.mem0.Mem0Models;
import com.h.backend.memory.infrastructure.persistence.entity.LongTermMemoryRecordEntity;
import com.h.backend.memory.infrastructure.persistence.entity.MemoryCaptureOutboxEntity;
import com.h.backend.memory.infrastructure.persistence.mapper.LongTermMemoryRecordMapper;
import com.h.backend.memory.infrastructure.persistence.mapper.MemoryCaptureOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步 capture worker：claim（FOR UPDATE SKIP LOCKED）→ 回读已持久化消息 →
 * Mem0 add(infer=true) → 登记返回 memory ids。Mem0 HTTP 不参与本地事务。
 */
@Component
@ConditionalOnProperty(prefix = "memory.long-term", name = "enabled", havingValue = "true")
public class MemoryCaptureWorker {

    private static final Logger log = LoggerFactory.getLogger(MemoryCaptureWorker.class);

    private static final int BATCH_SIZE = 20;
    private static final int RECONCILE_SEARCH_TOP_K = 50;

    private final Mem0Gateway mem0Gateway;
    private final MemoryCaptureOutboxMapper outboxMapper;
    private final LongTermMemoryRecordMapper recordMapper;
    private final TurnMessagePort turnMessagePort;
    private final LongTermMemoryProperties properties;
    private final CaptureBackoffPolicy backoffPolicy;
    private final TransactionTemplate transactionTemplate;

    public MemoryCaptureWorker(Mem0Gateway mem0Gateway,
                               MemoryCaptureOutboxMapper outboxMapper,
                               LongTermMemoryRecordMapper recordMapper,
                               TurnMessagePort turnMessagePort,
                               LongTermMemoryProperties properties,
                               TransactionTemplate transactionTemplate) {
        this.mem0Gateway = mem0Gateway;
        this.outboxMapper = outboxMapper;
        this.recordMapper = recordMapper;
        this.turnMessagePort = turnMessagePort;
        this.properties = properties;
        this.backoffPolicy = new CaptureBackoffPolicy(properties.getCapture());
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void processOutbox() {
        List<MemoryCaptureOutboxEntity> batch = transactionTemplate.execute(status ->
                outboxMapper.claimBatch(BATCH_SIZE));
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (MemoryCaptureOutboxEntity entry : batch) {
            transactionTemplate.executeWithoutResult(status -> markProcessing(entry));
            processEntry(entry);
        }
    }

    private void markProcessing(MemoryCaptureOutboxEntity entry) {
        entry.setState(MemoryCaptureOutboxEntity.STATE_PROCESSING);
        entry.setAttempts(entry.getAttempts() == null ? 1 : entry.getAttempts() + 1);
        entry.setUpdatedAt(LocalDateTime.now());
        outboxMapper.updateById(entry);
    }

    private void processEntry(MemoryCaptureOutboxEntity entry) {
        int attempts = entry.getAttempts() == null ? 1 : entry.getAttempts();
        int maxAttempts = properties.getCapture().getMaxAttempts();
        try {
            if (MemoryCaptureOutboxEntity.STATE_RECONCILING.equals(entry.getState())) {
                // 结果不明：先按 operation key 核验，禁止盲目重复 add
                if (reconcile(entry)) {
                    complete(entry, List.of());
                    return;
                }
                if (attempts >= maxAttempts) {
                    deadLetter(entry, "reconciliation exhausted without matching memory");
                    return;
                }
            }
            if (attempts > maxAttempts) {
                deadLetter(entry, "max attempts exceeded");
                return;
            }
            Mem0Models.Mem0AddResult result = sendToMem0(entry);
            complete(entry, result.memoryIds());
        } catch (Exception ex) {
            handleFailure(entry, ex, attempts, maxAttempts);
        }
    }

    private Mem0Models.Mem0AddResult sendToMem0(MemoryCaptureOutboxEntity entry) {
        TurnMessagePort.TurnTexts texts = turnMessagePort.loadTurnTexts(
                entry.getSourceExecutionId(), entry.getUserMessageId(), entry.getAssistantMessageId());
        MemoryScopePolicy.MemoryOwnerScope scope = ownerScope(entry);
        Map<String, Object> metadata = captureMetadata(entry);
        return mem0Gateway.add(new Mem0Models.Mem0AddCommand(
                scope,
                List.of(
                        Mem0Models.Mem0Message.user(texts.userMessage()),
                        Mem0Models.Mem0Message.assistant(texts.assistantMessage())
                ),
                true,
                metadata
        ));
    }

    /** 按目标 scope + operation key 查询远程是否已有本 turn 的记忆。 */
    private boolean reconcile(MemoryCaptureOutboxEntity entry) {
        MemoryScopePolicy.MemoryOwnerScope scope = ownerScope(entry);
        String operationKey = entry.getOperationKey();
        List<Mem0Models.Mem0Memory> found = mem0Gateway.searchExact(
                new Mem0Models.Mem0SearchQuery(scope, operationKey, RECONCILE_SEARCH_TOP_K));
        return found.stream()
                .anyMatch(memory -> memory.metadata() != null
                        && operationKey.equals(memory.metadata().get("operation_key")));
    }

    private void complete(MemoryCaptureOutboxEntity entry, List<String> memoryIds) {
        transactionTemplate.executeWithoutResult(status -> {
            registerMemoryRecords(entry, memoryIds);
            entry.setState(MemoryCaptureOutboxEntity.STATE_COMPLETED);
            entry.setLastError(null);
            entry.setNextAttemptAt(LocalDateTime.now());
            entry.setUpdatedAt(LocalDateTime.now());
            outboxMapper.updateById(entry);
        });
        log.info("Memory capture completed keyHash={} scope={} memories={}",
                hashOf(entry.getOperationKey()), entry.getScopeKind(), memoryIds.size());
    }

    private void handleFailure(MemoryCaptureOutboxEntity entry, Exception ex, int attempts, int maxAttempts) {
        Mem0ErrorClassifier.Kind kind = Mem0ErrorClassifier.classify(ex);
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        log.warn("Memory capture failed kind={} attempts={} keyHash={} error={}",
                kind, attempts, hashOf(entry.getOperationKey()), message);
        transactionTemplate.executeWithoutResult(status -> {
            entry.setLastError(abbreviate(message));
            entry.setUpdatedAt(LocalDateTime.now());
            switch (kind) {
                case RETRYABLE -> {
                    if (attempts >= maxAttempts) {
                        entry.setState(MemoryCaptureOutboxEntity.STATE_DEAD_LETTER);
                    } else {
                        entry.setState(MemoryCaptureOutboxEntity.STATE_PENDING);
                        entry.setNextAttemptAt(LocalDateTime.now()
                                .plus(backoffPolicy.delayForAttempt(attempts)));
                    }
                }
                case UNKNOWN -> entry.setState(MemoryCaptureOutboxEntity.STATE_RECONCILING);
                case NON_RETRYABLE -> entry.setState(MemoryCaptureOutboxEntity.STATE_DEAD_LETTER);
            }
            outboxMapper.updateById(entry);
        });
    }

    private void deadLetter(MemoryCaptureOutboxEntity entry, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            entry.setState(MemoryCaptureOutboxEntity.STATE_DEAD_LETTER);
            entry.setLastError(reason);
            entry.setUpdatedAt(LocalDateTime.now());
            outboxMapper.updateById(entry);
        });
        log.error("Memory capture dead-lettered keyHash={} reason={}", hashOf(entry.getOperationKey()), reason);
    }

    /** 把 Mem0 返回的 memory ids 登记到本地控制索引（不保存正文）。 */
    private void registerMemoryRecords(MemoryCaptureOutboxEntity entry, List<String> memoryIds) {
        for (String memoryId : memoryIds) {
            if (memoryId == null || memoryId.isBlank()) {
                continue;
            }
            if (recordMapper.selectByRemoteMemoryId(memoryId, entry.getOwnerUserId()) != null) {
                continue;
            }
            LongTermMemoryRecordEntity record = new LongTermMemoryRecordEntity();
            LocalDateTime now = LocalDateTime.now();
            record.setRemoteMemoryId(memoryId);
            record.setOwnerUserId(entry.getOwnerUserId());
            record.setScopeKind(entry.getScopeKind());
            record.setLogicalAgentId(MemoryScopeKind.USER.name().equals(entry.getScopeKind())
                    ? null : entry.getLogicalAgentId());
            record.setMemoryRunId(MemoryScopeKind.RUN.name().equals(entry.getScopeKind())
                    ? entry.getMemoryRunId() : null);
            record.setVersion(1);
            record.setOperationState("ACTIVE");
            record.setSource("auto_capture");
            record.setSourceExecutionId(entry.getSourceExecutionId());
            record.setCreatedAt(now);
            record.setUpdatedAt(now);
            recordMapper.insert(record);
        }
    }

    private MemoryScopePolicy.MemoryOwnerScope ownerScope(MemoryCaptureOutboxEntity entry) {
        MemoryScopeKind kind = MemoryScopeKind.valueOf(entry.getScopeKind());
        return MemoryScopePolicy.toOwnerScope(
                entry.getOwnerUserId(), kind, entry.getLogicalAgentId(), entry.getMemoryRunId());
    }

    private Map<String, Object> captureMetadata(MemoryCaptureOutboxEntity entry) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schema_version", 1);
        metadata.put("app", "h-agent");
        metadata.put("scope_kind", entry.getScopeKind());
        metadata.put("source", "auto_capture");
        metadata.put("source_agent_id", entry.getLogicalAgentId());
        metadata.put("source_task_id", entry.getMemoryRunId());
        metadata.put("source_execution_id", String.valueOf(entry.getSourceExecutionId()));
        metadata.put("operation_key", entry.getOperationKey());
        return metadata;
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String hashOf(String value) {
        return Integer.toHexString(java.util.Objects.hash(value));
    }
}
