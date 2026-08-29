package com.h.backend.memory.infrastructure;

import com.h.backend.memory.application.LongTermMemoryRuntime;
import com.h.backend.memory.domain.CompletedTurn;
import com.h.backend.memory.domain.MemoryInvocationContext;
import com.h.backend.memory.domain.MemoryRecallCommand;
import com.h.backend.memory.domain.MemoryRecallResult;
import com.h.backend.memory.domain.MemoryScopeKind;
import com.h.backend.memory.domain.MemoryScopePolicy;
import com.h.backend.memory.infrastructure.config.LongTermMemoryProperties;
import com.h.backend.memory.infrastructure.mem0.Mem0Gateway;
import com.h.backend.memory.infrastructure.mem0.Mem0Models;
import com.h.backend.memory.infrastructure.persistence.entity.MemoryCaptureOutboxEntity;
import com.h.backend.memory.infrastructure.persistence.mapper.MemoryCaptureOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 长期记忆运行时：分层并发召回（fail-open）+ 本地 outbox 入队（参与调用方事务）。
 * 调用方不感知 HTTP 状态码、Mem0 DTO、分层查询次数或 outbox 状态机。
 */
public class LongTermMemoryRuntimeImpl implements LongTermMemoryRuntime {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryRuntimeImpl.class);

    private static final int FAILURE_THRESHOLD = 5;
    private static final long OPEN_WINDOW_MILLIS = 60_000;

    private final Mem0Gateway mem0Gateway;
    private final LongTermMemoryProperties properties;
    private final MemoryCaptureOutboxMapper outboxMapper;
    private final ConsecutiveFailureBreaker breaker;
    private final ExecutorService scopeExecutor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "ltm-scope-recall");
        thread.setDaemon(true);
        return thread;
    });

    public LongTermMemoryRuntimeImpl(Mem0Gateway mem0Gateway,
                                     LongTermMemoryProperties properties,
                                     MemoryCaptureOutboxMapper outboxMapper) {
        this.mem0Gateway = mem0Gateway;
        this.properties = properties;
        this.outboxMapper = outboxMapper;
        this.breaker = properties.getRecall().isCircuitBreakerEnabled()
                ? new ConsecutiveFailureBreaker(FAILURE_THRESHOLD, OPEN_WINDOW_MILLIS)
                : null;
    }

    @Override
    public MemoryRecallResult recall(MemoryRecallCommand command) {
        if (command == null || command.query() == null || command.query().isBlank()) {
            return MemoryRecallResult.empty();
        }
        Set<MemoryScopeKind> scopes = command.scopes();
        if (scopes.isEmpty()) {
            return MemoryRecallResult.empty();
        }
        if (breaker != null && !breaker.allowRequest()) {
            log.debug("Long-term memory recall short-circuited by breaker");
            return MemoryRecallResult.empty();
        }
        List<MemoryRecallResult.MemoryItem> items = recallScopes(command.context(), scopes, command.query());
        return new MemoryRecallResult(items);
    }

    @Override
    public void stageCapture(CompletedTurn turn) {
        if (turn == null || !properties.getCapture().isOutboxEnabled()) {
            return;
        }
        String operationKey = turn.operationKey();
        if (outboxMapper.selectByOperationKey(operationKey) != null) {
            // 重复 turn key 仅入队一次
            return;
        }
        MemoryCaptureOutboxEntity entity = new MemoryCaptureOutboxEntity();
        LocalDateTime now = LocalDateTime.now();
        entity.setOperationKey(operationKey);
        entity.setOwnerUserId(turn.context().userId());
        entity.setLogicalAgentId(turn.context().logicalAgentId());
        entity.setMemoryRunId(turn.context().memoryRunId());
        entity.setScopeKind(turn.captureScope().name());
        entity.setSourceExecutionId(turn.context().sourceExecutionId());
        entity.setPromptId(turn.context().promptId());
        entity.setSessionId(turn.context().actualSessionId());
        entity.setUserMessageId(turn.userMessageId());
        entity.setAssistantMessageId(turn.assistantMessageId());
        entity.setState(MemoryCaptureOutboxEntity.STATE_PENDING);
        entity.setAttempts(0);
        entity.setNextAttemptAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            outboxMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            // 并发入队时唯一约束兜底
            log.debug("Duplicate capture outbox entry ignored keyHash={}", hashOf(operationKey));
        }
    }

    /** 每层一次并发查询；某层失败或超时只丢弃该层结果，不阻断其他层。 */
    private List<MemoryRecallResult.MemoryItem> recallScopes(MemoryInvocationContext context,
                                                             Set<MemoryScopeKind> scopes,
                                                             String query) {
        Map<MemoryScopeKind, Future<List<Mem0Models.Mem0Memory>>> futures = new LinkedHashMap<>();
        long timeoutMillis = properties.getRecall().getResponseTimeout().toMillis();
        for (MemoryScopeKind scope : MemoryScopeKind.values()) {
            if (!scopes.contains(scope)) {
                continue;
            }
            MemoryScopePolicy.MemoryOwnerScope ownerScope = MemoryScopePolicy.toOwnerScope(context, scope);
            int topK = properties.getRecall().getTopKPerScope();
            futures.put(scope, scopeExecutor.submit(
                    (Callable<List<Mem0Models.Mem0Memory>>) () -> mem0Gateway.searchExact(
                            new Mem0Models.Mem0SearchQuery(ownerScope, query, topK))));
        }
        Map<MemoryScopeKind, List<MemoryRecallResult.MemoryItem>> perScope = new LinkedHashMap<>();
        boolean anyFailure = false;
        for (Map.Entry<MemoryScopeKind, Future<List<Mem0Models.Mem0Memory>>> entry : futures.entrySet()) {
            try {
                List<Mem0Models.Mem0Memory> memories = entry.getValue().get(timeoutMillis, TimeUnit.MILLISECONDS);
                List<MemoryRecallResult.MemoryItem> mapped = new ArrayList<>();
                for (Mem0Models.Mem0Memory memory : memories) {
                    if (memory == null || memory.text() == null || memory.text().isBlank()) {
                        continue;
                    }
                    mapped.add(new MemoryRecallResult.MemoryItem(
                            memory.id(), memory.text(), entry.getKey(),
                            memory.score() == null ? 0.0 : memory.score(),
                            memory.updatedAt()));
                }
                perScope.put(entry.getKey(), mapped);
            } catch (Exception ex) {
                anyFailure = true;
                log.warn("Long-term memory scope recall failed scope={} error={}",
                        entry.getKey(), ex.toString());
            }
        }
        recordBreaker(anyFailure);
        return merge(perScope);
    }

    /** 去重、每层最低配额、统一排序、top-k 与 max-chars 截断。 */
    private List<MemoryRecallResult.MemoryItem> merge(Map<MemoryScopeKind, List<MemoryRecallResult.MemoryItem>> perScope) {
        int maxTotal = properties.getRecall().getMaxTotalResults();
        int maxChars = properties.getRecall().getMaxChars();
        int layers = perScope.size();
        // 每层最低配额，避免一层占满全部预算
        int minPerScope = layers == 0 ? 0 : Math.max(1, maxTotal / layers);

        Set<String> seenIds = new HashSet<>();
        Set<String> seenTextHashes = new HashSet<>();
        List<MemoryRecallResult.MemoryItem> merged = new ArrayList<>();
        for (List<MemoryRecallResult.MemoryItem> items : perScope.values()) {
            int taken = 0;
            for (MemoryRecallResult.MemoryItem item : items) {
                if (taken >= minPerScope && merged.size() >= maxTotal) {
                    break;
                }
                String textHash = normalizedHash(item.text());
                if ((item.remoteMemoryId() != null && !seenIds.add(item.remoteMemoryId()))
                        || !seenTextHashes.add(textHash)) {
                    continue;
                }
                merged.add(item);
                taken++;
            }
        }
        merged.sort(Comparator
                .comparingDouble(MemoryRecallResult.MemoryItem::score).reversed()
                .thenComparing(item -> scopeWeight(item.scopeKind()), Comparator.reverseOrder())
                .thenComparing(item -> item.updatedAt() == null
                        ? java.time.Instant.EPOCH
                        : item.updatedAt(), Comparator.reverseOrder()));

        List<MemoryRecallResult.MemoryItem> truncated = new ArrayList<>();
        int totalChars = 0;
        for (MemoryRecallResult.MemoryItem item : merged) {
            if (truncated.size() >= maxTotal) {
                break;
            }
            if (totalChars + item.text().length() > maxChars && !truncated.isEmpty()) {
                break;
            }
            truncated.add(item);
            totalChars += item.text().length();
        }
        return truncated;
    }

    private void recordBreaker(boolean anyFailure) {
        if (breaker == null) {
            return;
        }
        if (anyFailure) {
            breaker.recordFailure();
        } else {
            breaker.recordSuccess();
        }
    }

    private static int scopeWeight(MemoryScopeKind kind) {
        return switch (kind) {
            case RUN -> 3;
            case AGENT -> 2;
            case USER -> 1;
        };
    }

    private static String normalizedHash(String text) {
        String normalized = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        return Integer.toHexString(Objects.hash(normalized));
    }

    private static String hashOf(String value) {
        return Integer.toHexString(Objects.hash(value));
    }
}
