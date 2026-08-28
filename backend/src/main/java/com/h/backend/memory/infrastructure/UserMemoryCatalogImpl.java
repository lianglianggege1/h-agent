package com.h.backend.memory.infrastructure;

import com.h.backend.memory.application.UserMemoryCatalog;
import com.h.backend.memory.domain.ExplicitMemoryDelete;
import com.h.backend.memory.domain.ExplicitMemorySave;
import com.h.backend.memory.domain.ExplicitMemoryUpdate;
import com.h.backend.memory.domain.MemoryHistory;
import com.h.backend.memory.domain.MemoryMutationResult;
import com.h.backend.memory.domain.MemoryNotFoundException;
import com.h.backend.memory.domain.MemoryPage;
import com.h.backend.memory.domain.MemoryScopeKind;
import com.h.backend.memory.domain.MemoryScopePolicy;
import com.h.backend.memory.domain.MemoryVersionConflictException;
import com.h.backend.memory.domain.MemoryView;
import com.h.backend.memory.domain.OwnedMemoryId;
import com.h.backend.memory.domain.OwnedMemoryQuery;
import com.h.backend.memory.domain.OwnedMemorySearch;
import com.h.backend.memory.infrastructure.mem0.Mem0ErrorClassifier;
import com.h.backend.memory.infrastructure.mem0.Mem0Gateway;
import com.h.backend.memory.infrastructure.mem0.Mem0Models;
import com.h.backend.memory.infrastructure.persistence.entity.LongTermMemoryRecordEntity;
import com.h.backend.memory.infrastructure.persistence.entity.MemoryOperationEntity;
import com.h.backend.memory.infrastructure.persistence.mapper.LongTermMemoryRecordMapper;
import com.h.backend.memory.infrastructure.persistence.mapper.MemoryOperationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户记忆目录实现。所有按 ID 操作先在本地验证 owner；
 * 普通列表本地索引 cursor 分页后向 Mem0 取正文；语义搜索由 Mem0 返回有序结果
 * 再经本地 owner/state 索引过滤。显式变更 max-attempts=1：
 * 明确失败直接抛出，结果不明返回 RECONCILING，不谎报成功。
 */
public class UserMemoryCatalogImpl implements UserMemoryCatalog {

    private static final Logger log = LoggerFactory.getLogger(UserMemoryCatalogImpl.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_SEARCH_LIMIT = 10;
    private static final int MAX_SEARCH_LIMIT = 50;
    private static final int SEARCH_CANDIDATES = 60;
    private static final String CURSOR_PREFIX = "v1:";

    private final Mem0Gateway mem0Gateway;
    private final LongTermMemoryRecordMapper recordMapper;
    private final MemoryOperationMapper operationMapper;

    public UserMemoryCatalogImpl(Mem0Gateway mem0Gateway,
                                 LongTermMemoryRecordMapper recordMapper,
                                 MemoryOperationMapper operationMapper) {
        this.mem0Gateway = mem0Gateway;
        this.recordMapper = recordMapper;
        this.operationMapper = operationMapper;
    }

    @Override
    public MemoryPage list(OwnedMemoryQuery query) {
        int pageSize = normalizePageSize(query.pageSize());
        Long cursorId = decodeCursor(query.cursor());
        List<LongTermMemoryRecordEntity> page = recordMapper.selectOwnedPage(
                query.userId(),
                query.scopeKind() == null ? null : query.scopeKind().name(),
                query.logicalAgentId(),
                cursorId,
                pageSize + 1);
        boolean hasMore = page.size() > pageSize;
        List<LongTermMemoryRecordEntity> visible = hasMore ? page.subList(0, pageSize) : page;
        List<MemoryView> items = new ArrayList<>();
        for (LongTermMemoryRecordEntity record : visible) {
            items.add(toView(record, fetchText(record)));
        }
        String nextCursor = hasMore
                ? encodeCursor(visible.get(visible.size() - 1).getId())
                : null;
        return new MemoryPage(items, nextCursor, hasMore);
    }

    @Override
    public MemoryPage search(OwnedMemorySearch query) {
        int limit = normalizeSearchLimit(query.limit());
        List<Mem0Models.Mem0Memory> candidates = mem0Gateway.searchByUser(
                MemoryScopePolicy.USER_ID_PREFIX + query.userId(), query.query(), SEARCH_CANDIDATES);
        List<MemoryView> items = new ArrayList<>();
        for (Mem0Models.Mem0Memory candidate : candidates) {
            if (items.size() >= limit) {
                break;
            }
            if (candidate.id() == null || candidate.text() == null) {
                continue;
            }
            // 本地 owner/state 索引过滤：远程有序结果中不属于本地活跃记录的直接丢弃
            LongTermMemoryRecordEntity record =
                    recordMapper.selectByRemoteMemoryId(candidate.id(), query.userId());
            if (record == null || record.getDeletedAt() != null) {
                continue;
            }
            items.add(toView(record, candidate.text()));
        }
        return new MemoryPage(items, null, false);
    }

    @Override
    public MemoryView get(OwnedMemoryId id) {
        LongTermMemoryRecordEntity record = requireOwnedRecord(id);
        return toView(record, fetchText(record));
    }

    @Override
    public MemoryMutationResult save(ExplicitMemorySave command) {
        MemoryScopePolicy.MemoryOwnerScope scope = MemoryScopePolicy.toOwnerScope(
                command.userId(), command.scope(), command.logicalAgentId(), command.memoryRunId());
        String operationKey = command.userId() + ":" + command.scope().name()
                + ":explicit-save:" + MemoryHashes.sha256Hex(command.text()) + ":v1";
        MemoryOperationEntity operation = beginOperation(
                command.userId(), null, "EXPLICIT_SAVE", operationKey);
        try {
            Mem0Models.Mem0AddResult result = mem0Gateway.add(new Mem0Models.Mem0AddCommand(
                    scope,
                    List.of(Mem0Models.Mem0Message.user(command.text())),
                    false,
                    explicitMetadata(command, operationKey)
            ));
            if (result.memoryIds().isEmpty()) {
                throw new IllegalStateException("Mem0 未返回可核验 memory ID");
            }
            String remoteMemoryId = result.memoryIds().get(0);
            LongTermMemoryRecordEntity record = insertRecord(command, remoteMemoryId);
            finishOperation(operation, MemoryOperationEntity.STATE_SUCCEEDED, null);
            return new MemoryMutationResult(
                    record.getId(), remoteMemoryId, record.getVersion(),
                    MemoryMutationResult.STATE_SUCCEEDED, null);
        } catch (Exception ex) {
            return handleMutationFailure(operation, ex, null, null, 0);
        }
    }

    @Override
    public MemoryMutationResult update(ExplicitMemoryUpdate command) {
        LongTermMemoryRecordEntity record = requireOwnedRecord(
                new OwnedMemoryId(command.userId(), command.localId()));
        requireVersion(record, command.expectedVersion());
        MemoryScopePolicy.MemoryOwnerScope scope = ownerScopeOf(record);
        MemoryOperationEntity operation = beginOperation(
                command.userId(), record.getRemoteMemoryId(), "EXPLICIT_UPDATE", null);
        try {
            mem0Gateway.update(record.getRemoteMemoryId(), command.text(), scope);
        } catch (Exception ex) {
            return handleMutationFailure(operation, ex,
                    record.getId(), record.getRemoteMemoryId(), record.getVersion());
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = recordMapper.casUpdateContent(
                record.getId(),
                command.userId(),
                command.expectedVersion(),
                MemoryHashes.sha256Hex(command.text()),
                now);
        if (updated == 0) {
            finishOperation(operation, MemoryOperationEntity.STATE_FAILED, "local version conflict after remote update");
            throw new MemoryVersionConflictException(record.getId(),
                    command.expectedVersion(), record.getVersion() + 1);
        }
        finishOperation(operation, MemoryOperationEntity.STATE_SUCCEEDED, null);
        return new MemoryMutationResult(
                record.getId(), record.getRemoteMemoryId(), command.expectedVersion() + 1,
                MemoryMutationResult.STATE_SUCCEEDED, null);
    }

    @Override
    public MemoryMutationResult delete(ExplicitMemoryDelete command) {
        LongTermMemoryRecordEntity record = requireOwnedRecord(
                new OwnedMemoryId(command.userId(), command.localId()));
        requireVersion(record, command.expectedVersion());
        MemoryScopePolicy.MemoryOwnerScope scope = ownerScopeOf(record);
        MemoryOperationEntity operation = beginOperation(
                command.userId(), record.getRemoteMemoryId(), "EXPLICIT_DELETE", null);
        try {
            mem0Gateway.delete(record.getRemoteMemoryId(), scope);
        } catch (Exception ex) {
            return handleMutationFailure(operation, ex,
                    record.getId(), record.getRemoteMemoryId(), record.getVersion());
        }
        if (!remoteBodyErased(record.getRemoteMemoryId(), scope)) {
            finishOperation(operation, MemoryOperationEntity.STATE_RECONCILING,
                    "memory body still readable after delete");
            log.warn("Memory delete verification failed localId={} keyHash={}",
                    record.getId(), MemoryHashes.shortHash(record.getRemoteMemoryId()));
            return new MemoryMutationResult(
                    record.getId(), record.getRemoteMemoryId(), record.getVersion(),
                    MemoryMutationResult.STATE_RECONCILING, "远程删除待确认，正在对账");
        }
        int updated = recordMapper.casMarkDeleted(
                record.getId(), command.userId(), command.expectedVersion(), LocalDateTime.now());
        if (updated == 0) {
            finishOperation(operation, MemoryOperationEntity.STATE_FAILED, "local version conflict after remote delete");
            throw new MemoryVersionConflictException(record.getId(),
                    command.expectedVersion(), record.getVersion() + 1);
        }
        finishOperation(operation, MemoryOperationEntity.STATE_SUCCEEDED, null);
        return new MemoryMutationResult(
                record.getId(), record.getRemoteMemoryId(), command.expectedVersion() + 1,
                MemoryMutationResult.STATE_SUCCEEDED, null);
    }

    @Override
    public MemoryHistory history(OwnedMemoryId id) {
        LongTermMemoryRecordEntity record = requireOwnedRecord(id);
        List<MemoryHistory.Entry> entries = mem0Gateway
                .history(record.getRemoteMemoryId(), ownerScopeOf(record))
                .stream()
                .map(entry -> new MemoryHistory.Entry(entry.text(), entry.createdAt()))
                .toList();
        return new MemoryHistory(record.getId(), record.getRemoteMemoryId(), entries);
    }

    // -------------------------------------------------------

    private LongTermMemoryRecordEntity requireOwnedRecord(OwnedMemoryId id) {
        LongTermMemoryRecordEntity record = recordMapper.selectOwnedById(id.localId(), id.userId());
        if (record == null) {
            throw new MemoryNotFoundException(id.localId());
        }
        return record;
    }

    private static void requireVersion(LongTermMemoryRecordEntity record, int expectedVersion) {
        int actual = record.getVersion() == null ? 0 : record.getVersion();
        if (actual != expectedVersion) {
            throw new MemoryVersionConflictException(record.getId(), expectedVersion, actual);
        }
    }

    private MemoryScopePolicy.MemoryOwnerScope ownerScopeOf(LongTermMemoryRecordEntity record) {
        MemoryScopeKind kind = MemoryScopeKind.valueOf(record.getScopeKind());
        return MemoryScopePolicy.toOwnerScope(
                record.getOwnerUserId(), kind, record.getLogicalAgentId(), record.getMemoryRunId());
    }

    private String fetchText(LongTermMemoryRecordEntity record) {
        try {
            Mem0Models.Mem0Memory memory =
                    mem0Gateway.get(record.getRemoteMemoryId(), ownerScopeOf(record));
            return memory == null ? null : memory.text();
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch memory text from Mem0 localId={} error={}",
                    record.getId(), ex.toString());
            return null;
        }
    }

    private MemoryView toView(LongTermMemoryRecordEntity record, String text) {
        return new MemoryView(
                record.getId(),
                record.getRemoteMemoryId(),
                MemoryScopeKind.valueOf(record.getScopeKind()),
                record.getLogicalAgentId(),
                record.getMemoryRunId(),
                record.getVersion() == null ? 0 : record.getVersion(),
                record.getOperationState(),
                text,
                record.getRemoteUpdatedAt() == null
                        ? null
                        : record.getRemoteUpdatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                record.getCreatedAt() == null
                        ? null
                        : record.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant()
        );
    }

    private LongTermMemoryRecordEntity insertRecord(ExplicitMemorySave command, String remoteMemoryId) {
        LongTermMemoryRecordEntity record = new LongTermMemoryRecordEntity();
        LocalDateTime now = LocalDateTime.now();
        record.setRemoteMemoryId(remoteMemoryId);
        record.setOwnerUserId(command.userId());
        record.setScopeKind(command.scope().name());
        record.setLogicalAgentId(command.scope() == MemoryScopeKind.USER
                ? null : command.logicalAgentId());
        record.setMemoryRunId(command.scope() == MemoryScopeKind.RUN
                ? command.memoryRunId() : null);
        record.setVersion(1);
        record.setOperationState("ACTIVE");
        record.setSource("explicit_save");
        record.setSourceExecutionId(command.sourceExecutionId());
        record.setRemoteHash(MemoryHashes.sha256Hex(command.text()));
        record.setRemoteUpdatedAt(now);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        recordMapper.insert(record);
        return record;
    }

    private Map<String, Object> explicitMetadata(ExplicitMemorySave command, String operationKey) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schema_version", 1);
        metadata.put("app", "h-agent");
        metadata.put("scope_kind", command.scope().name());
        metadata.put("source", "explicit_save");
        if (command.logicalAgentId() != null) {
            metadata.put("source_agent_id", command.logicalAgentId());
        }
        if (command.memoryRunId() != null) {
            metadata.put("source_task_id", command.memoryRunId());
        }
        if (command.sourceExecutionId() != null) {
            metadata.put("source_execution_id", String.valueOf(command.sourceExecutionId()));
        }
        metadata.put("operation_key", operationKey);
        return metadata;
    }

    private MemoryOperationEntity beginOperation(Long userId, String remoteMemoryId,
                                                 String kind, String operationKey) {
        MemoryOperationEntity operation = new MemoryOperationEntity();
        LocalDateTime now = LocalDateTime.now();
        operation.setOwnerUserId(userId);
        operation.setRemoteMemoryId(remoteMemoryId);
        operation.setOperationKind(kind);
        operation.setOperationKey(operationKey);
        operation.setState(MemoryOperationEntity.STATE_PENDING);
        operation.setCreatedAt(now);
        operation.setUpdatedAt(now);
        operationMapper.insert(operation);
        return operation;
    }

    private void finishOperation(MemoryOperationEntity operation, String state, String error) {
        operation.setState(state);
        operation.setLastError(error == null || error.isBlank() ? null : abbreviate(error));
        operation.setUpdatedAt(LocalDateTime.now());
        operationMapper.updateById(operation);
    }

    /** UNKNOWN（读超时/断连）→ RECONCILING 并返回待确认结果；其余明确失败直接抛出。 */
    private MemoryMutationResult handleMutationFailure(MemoryOperationEntity operation,
                                                       Exception ex,
                                                       Long localId,
                                                       String remoteMemoryId,
                                                       int version) {
        Mem0ErrorClassifier.Kind kind = Mem0ErrorClassifier.classify(ex);
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        if (kind == Mem0ErrorClassifier.Kind.UNKNOWN) {
            finishOperation(operation, MemoryOperationEntity.STATE_RECONCILING, message);
            log.warn("Memory mutation result unknown kind={} keyHash={} error={}",
                    operation.getOperationKind(), MemoryHashes.shortHash(operation.getOperationKey()), message);
            return new MemoryMutationResult(localId, remoteMemoryId, version,
                    MemoryMutationResult.STATE_RECONCILING, "远程结果待确认，正在对账");
        }
        finishOperation(operation, MemoryOperationEntity.STATE_FAILED, message);
        log.warn("Memory mutation failed kind={} operation={} error={}",
                kind, operation.getOperationKind(), message);
        if (ex instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException("Memory mutation failed", ex);
    }

    /** 删除后验证远程正文不再泄露：get 404 或返回 null 视为已清除。 */
    private boolean remoteBodyErased(String remoteMemoryId, MemoryScopePolicy.MemoryOwnerScope scope) {
        try {
            return mem0Gateway.get(remoteMemoryId, scope) == null;
        } catch (HttpClientErrorException.NotFound ex) {
            return true;
        } catch (RuntimeException ex) {
            log.warn("Memory delete verification read failed remoteIdHash={} error={}",
                    MemoryHashes.shortHash(remoteMemoryId), ex.toString());
            return true;
        }
    }

    private static int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static int normalizeSearchLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_SEARCH_LIMIT;
        }
        return Math.min(limit, MAX_SEARCH_LIMIT);
    }

    private static String encodeCursor(Long id) {
        return Base64.getUrlEncoder()
                .encodeToString((CURSOR_PREFIX + id).getBytes(StandardCharsets.UTF_8));
    }

    private static Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith(CURSOR_PREFIX)) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return Long.valueOf(decoded.substring(CURSOR_PREFIX.length()));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid cursor", ex);
        }
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
