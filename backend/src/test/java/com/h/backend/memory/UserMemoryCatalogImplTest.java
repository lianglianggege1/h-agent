package com.h.backend.memory;

import com.h.backend.memory.domain.ExplicitMemoryDelete;
import com.h.backend.memory.domain.ExplicitMemorySave;
import com.h.backend.memory.domain.ExplicitMemoryUpdate;
import com.h.backend.memory.domain.MemoryMutationResult;
import com.h.backend.memory.domain.MemoryNotFoundException;
import com.h.backend.memory.domain.MemoryPage;
import com.h.backend.memory.domain.MemoryScopeKind;
import com.h.backend.memory.domain.MemoryVersionConflictException;
import com.h.backend.memory.domain.MemoryView;
import com.h.backend.memory.domain.OwnedMemoryId;
import com.h.backend.memory.domain.OwnedMemoryQuery;
import com.h.backend.memory.domain.OwnedMemorySearch;
import com.h.backend.memory.infrastructure.UserMemoryCatalogImpl;
import com.h.backend.memory.infrastructure.mem0.InMemoryMem0Gateway;
import com.h.backend.memory.infrastructure.mem0.Mem0Gateway;
import com.h.backend.memory.infrastructure.mem0.Mem0Models;
import com.h.backend.memory.infrastructure.persistence.entity.LongTermMemoryRecordEntity;
import com.h.backend.memory.infrastructure.persistence.entity.MemoryOperationEntity;
import com.h.backend.memory.infrastructure.persistence.mapper.LongTermMemoryRecordMapper;
import com.h.backend.memory.infrastructure.persistence.mapper.MemoryOperationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserMemoryCatalogImplTest {

    private final InMemoryMem0Gateway gateway = new InMemoryMem0Gateway();
    private final LongTermMemoryRecordMapper recordMapper = mock(LongTermMemoryRecordMapper.class);
    private final MemoryOperationMapper operationMapper = mock(MemoryOperationMapper.class);
    private final Map<Long, LongTermMemoryRecordEntity> recordStore = new ConcurrentHashMap<>();
    private final Map<Long, MemoryOperationEntity> operationStore = new ConcurrentHashMap<>();
    private final AtomicLong recordIdSeq = new AtomicLong();
    private final AtomicLong operationIdSeq = new AtomicLong();
    private UserMemoryCatalogImpl catalog;

    @BeforeEach
    void setUp() {
        catalog = new UserMemoryCatalogImpl(gateway, recordMapper, operationMapper);
        doAnswer(invocation -> {
            LongTermMemoryRecordEntity entity = invocation.getArgument(0);
            entity.setId(recordIdSeq.incrementAndGet());
            recordStore.put(entity.getId(), entity);
            return 1;
        }).when(recordMapper).insert(any(LongTermMemoryRecordEntity.class));
        doAnswer(invocation -> {
            MemoryOperationEntity entity = invocation.getArgument(0);
            entity.setId(operationIdSeq.incrementAndGet());
            operationStore.put(entity.getId(), entity);
            return 1;
        }).when(operationMapper).insert(any(MemoryOperationEntity.class));
        when(operationMapper.updateById(any(MemoryOperationEntity.class))).thenAnswer(invocation -> {
            MemoryOperationEntity entity = invocation.getArgument(0);
            operationStore.put(entity.getId(), entity);
            return 1;
        });
        when(recordMapper.selectOwnedById(any(), any())).thenAnswer(invocation -> {
            Long localId = invocation.getArgument(0);
            Long userId = invocation.getArgument(1);
            LongTermMemoryRecordEntity entity = recordStore.get(localId);
            if (entity == null || !entity.getOwnerUserId().equals(userId) || entity.getDeletedAt() != null) {
                return null;
            }
            return copyOf(entity);
        });
        when(recordMapper.selectByRemoteMemoryId(any(), any())).thenAnswer(invocation -> {
            String remoteId = invocation.getArgument(0);
            Long userId = invocation.getArgument(1);
            return recordStore.values().stream()
                    .filter(entity -> entity.getRemoteMemoryId().equals(remoteId)
                            && entity.getOwnerUserId().equals(userId))
                    .findFirst()
                    .map(this::copyOf)
                    .orElse(null);
        });
        when(recordMapper.selectOwnedPage(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    Long userId = invocation.getArgument(0);
                    String scopeKind = invocation.getArgument(1);
                    String agentId = invocation.getArgument(2);
                    Long cursorId = invocation.getArgument(3);
                    int limit = invocation.getArgument(4);
                    List<LongTermMemoryRecordEntity> page = recordStore.values().stream()
                            .filter(entity -> entity.getOwnerUserId().equals(userId)
                                    && entity.getDeletedAt() == null)
                            .filter(entity -> scopeKind == null || entity.getScopeKind().equals(scopeKind))
                            .filter(entity -> agentId == null || agentId.equals(entity.getLogicalAgentId()))
                            .filter(entity -> cursorId == null || entity.getId() < cursorId)
                            .sorted(Comparator.comparingLong(LongTermMemoryRecordEntity::getId).reversed())
                            .limit(limit)
                            .map(this::copyOf)
                            .toList();
                    return new ArrayList<>(page);
                });
        when(recordMapper.casUpdateContent(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenAnswer(invocation -> {
                    Long localId = invocation.getArgument(0);
                    Long userId = invocation.getArgument(1);
                    int expectedVersion = invocation.getArgument(2);
                    LongTermMemoryRecordEntity entity = recordStore.get(localId);
                    if (entity == null || !entity.getOwnerUserId().equals(userId)
                            || entity.getDeletedAt() != null
                            || entity.getVersion() != expectedVersion) {
                        return 0;
                    }
                    entity.setVersion(entity.getVersion() + 1);
                    entity.setRemoteHash(invocation.getArgument(3));
                    entity.setRemoteUpdatedAt(LocalDateTime.now());
                    entity.setUpdatedAt(LocalDateTime.now());
                    return 1;
                });
        when(recordMapper.casMarkDeleted(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenAnswer(invocation -> {
                    Long localId = invocation.getArgument(0);
                    Long userId = invocation.getArgument(1);
                    int expectedVersion = invocation.getArgument(2);
                    LongTermMemoryRecordEntity entity = recordStore.get(localId);
                    if (entity == null || !entity.getOwnerUserId().equals(userId)
                            || entity.getDeletedAt() != null
                            || entity.getVersion() != expectedVersion) {
                        return 0;
                    }
                    entity.setVersion(entity.getVersion() + 1);
                    entity.setOperationState("DELETED");
                    entity.setDeletedAt(LocalDateTime.now());
                    entity.setUpdatedAt(LocalDateTime.now());
                    return 1;
                });
    }

    private LongTermMemoryRecordEntity copyOf(LongTermMemoryRecordEntity entity) {
        LongTermMemoryRecordEntity copy = new LongTermMemoryRecordEntity();
        copy.setId(entity.getId());
        copy.setRemoteMemoryId(entity.getRemoteMemoryId());
        copy.setOwnerUserId(entity.getOwnerUserId());
        copy.setScopeKind(entity.getScopeKind());
        copy.setLogicalAgentId(entity.getLogicalAgentId());
        copy.setMemoryRunId(entity.getMemoryRunId());
        copy.setVersion(entity.getVersion());
        copy.setOperationState(entity.getOperationState());
        copy.setSource(entity.getSource());
        copy.setSourceExecutionId(entity.getSourceExecutionId());
        copy.setRemoteHash(entity.getRemoteHash());
        copy.setRemoteUpdatedAt(entity.getRemoteUpdatedAt());
        copy.setCreatedAt(entity.getCreatedAt());
        copy.setUpdatedAt(entity.getUpdatedAt());
        copy.setDeletedAt(entity.getDeletedAt());
        return copy;
    }

    private MemoryMutationResult saveUserMemory(long userId, String text) {
        return catalog.save(new ExplicitMemorySave(
                userId, MemoryScopeKind.USER, null, null, text, null));
    }

    @Test
    void saveExplicitMemoryRegistersLocalRecordAndRemoteBody() {
        MemoryMutationResult result = saveUserMemory(1L, "用户偏好深色主题");

        assertEquals(MemoryMutationResult.STATE_SUCCEEDED, result.state());
        assertEquals(1, result.version());
        assertNotNull(result.localId());
        assertNotNull(result.remoteMemoryId());

        MemoryView view = catalog.get(new OwnedMemoryId(1L, result.localId()));
        assertEquals("用户偏好深色主题", view.text());
        assertEquals(MemoryScopeKind.USER, view.scopeKind());
        assertEquals(1, gateway.memoryCount());
    }

    @Test
    void saveRejectsAgentScopeWithoutAgentId() {
        assertThrows(IllegalArgumentException.class, () -> catalog.save(new ExplicitMemorySave(
                1L, MemoryScopeKind.AGENT, null, null, "text", null)));
    }

    @Test
    void saveRejectsRunScopeWithoutRunId() {
        assertThrows(IllegalArgumentException.class, () -> catalog.save(new ExplicitMemorySave(
                1L, MemoryScopeKind.RUN, "export-assistant", null, "text", null)));
    }

    @Test
    void getRejectsForeignOwner() {
        MemoryMutationResult result = saveUserMemory(1L, "用户偏好深色主题");

        assertThrows(MemoryNotFoundException.class,
                () -> catalog.get(new OwnedMemoryId(2L, result.localId())));
    }

    @Test
    void updateBumpsVersionAndRemoteText() {
        MemoryMutationResult saved = saveUserMemory(1L, "用户偏好深色主题");

        MemoryMutationResult updated = catalog.update(new ExplicitMemoryUpdate(
                1L, saved.localId(), "用户偏好浅色主题", saved.version()));

        assertEquals(MemoryMutationResult.STATE_SUCCEEDED, updated.state());
        assertEquals(2, updated.version());
        MemoryView view = catalog.get(new OwnedMemoryId(1L, saved.localId()));
        assertEquals("用户偏好浅色主题", view.text());
    }

    @Test
    void updateWithStaleVersionReturnsConflict() {
        MemoryMutationResult saved = saveUserMemory(1L, "用户偏好深色主题");
        catalog.update(new ExplicitMemoryUpdate(1L, saved.localId(), "改写", 1));

        assertThrows(MemoryVersionConflictException.class, () -> catalog.update(
                new ExplicitMemoryUpdate(1L, saved.localId(), "再次改写", 1)));
    }

    @Test
    void deleteErasesRemoteBodyAndMarksLocalRecord() {
        MemoryMutationResult saved = saveUserMemory(1L, "用户偏好深色主题");

        MemoryMutationResult deleted = catalog.delete(new ExplicitMemoryDelete(1L, saved.localId(), 1));

        assertEquals(MemoryMutationResult.STATE_SUCCEEDED, deleted.state());
        assertEquals(0, gateway.memoryCount());
        assertThrows(MemoryNotFoundException.class,
                () -> catalog.get(new OwnedMemoryId(1L, saved.localId())));
    }

    @Test
    void deleteWithStaleVersionReturnsConflict() {
        MemoryMutationResult saved = saveUserMemory(1L, "用户偏好深色主题");
        catalog.update(new ExplicitMemoryUpdate(1L, saved.localId(), "改写", 1));

        assertThrows(MemoryVersionConflictException.class,
                () -> catalog.delete(new ExplicitMemoryDelete(1L, saved.localId(), 1)));
    }

    @Test
    void historyTracksRemoteEvolution() {
        MemoryMutationResult saved = saveUserMemory(1L, "初稿");
        catalog.update(new ExplicitMemoryUpdate(1L, saved.localId(), "定稿", 1));

        var history = catalog.history(new OwnedMemoryId(1L, saved.localId()));

        assertEquals(2, history.entries().size());
        assertEquals("初稿", history.entries().get(0).text());
        assertEquals("定稿", history.entries().get(1).text());
    }

    @Test
    void listPaginatesWithOpaqueCursor() {
        for (int i = 1; i <= 3; i++) {
            saveUserMemory(1L, "记忆 " + i);
        }

        MemoryPage firstPage = catalog.list(new OwnedMemoryQuery(1L, null, null, null, 2));
        assertEquals(2, firstPage.items().size());
        assertTrue(firstPage.hasMore());
        assertNotNull(firstPage.nextCursor());

        MemoryPage secondPage = catalog.list(
                new OwnedMemoryQuery(1L, null, null, firstPage.nextCursor(), 2));
        assertEquals(1, secondPage.items().size());
        assertFalse(secondPage.hasMore());
        assertNull(secondPage.nextCursor());
    }

    @Test
    void listFiltersByScopeKind() {
        saveUserMemory(1L, "用户记忆");
        catalog.save(new ExplicitMemorySave(
                1L, MemoryScopeKind.AGENT, "export-assistant", null, "Agent 记忆", null));

        MemoryPage userScopeOnly = catalog.list(
                new OwnedMemoryQuery(1L, MemoryScopeKind.USER, null, null, 20));

        assertEquals(1, userScopeOnly.items().size());
        assertEquals(MemoryScopeKind.USER, userScopeOnly.items().get(0).scopeKind());
    }

    @Test
    void searchKeepsOnlyLocallyIndexedMemories() {
        saveUserMemory(1L, "用户喜欢蓝色");
        // 远程存在但本地索引缺失的记录（漂移），搜索必须过滤掉
        gateway.add(new Mem0Models.Mem0AddCommand(
                userScope(1L),
                List.of(Mem0Models.Mem0Message.user("用户喜欢绿色")),
                false,
                java.util.Map.of()));

        MemoryPage result = catalog.search(new OwnedMemorySearch(1L, "喜欢", 10));

        assertEquals(1, result.items().size());
        assertEquals("用户喜欢蓝色", result.items().get(0).text());
    }

    @Test
    void searchIsolatedBetweenOwners() {
        saveUserMemory(1L, "用户一喜欢蓝色");
        saveUserMemory(2L, "用户二喜欢蓝色");

        MemoryPage result = catalog.search(new OwnedMemorySearch(1L, "喜欢", 10));

        assertEquals(1, result.items().size());
        assertEquals("用户一喜欢蓝色", result.items().get(0).text());
    }

    @Test
    void updateUnknownResultReturnsReconcilingInsteadOfSuccess() {
        FailingUpdateGateway failingGateway = new FailingUpdateGateway(gateway);
        UserMemoryCatalogImpl failingCatalog =
                new UserMemoryCatalogImpl(failingGateway, recordMapper, operationMapper);
        MemoryMutationResult saved = saveUserMemory(1L, "初稿");

        MemoryMutationResult result = failingCatalog.update(
                new ExplicitMemoryUpdate(1L, saved.localId(), "定稿", 1));

        assertEquals(MemoryMutationResult.STATE_RECONCILING, result.state());
        assertNotNull(result.message());
        // 本地版本未推进，等待 reconciliation
        MemoryView view = catalog.get(new OwnedMemoryId(1L, saved.localId()));
        assertEquals(1, view.version());
        boolean hasReconciling = operationStore.values().stream()
                .anyMatch(op -> MemoryOperationEntity.STATE_RECONCILING.equals(op.getState()));
        assertTrue(hasReconciling);
    }

    @Test
    void invalidCursorRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> catalog.list(new OwnedMemoryQuery(1L, null, null, "not-a-cursor", 20)));
    }

    private static com.h.backend.memory.domain.MemoryScopePolicy.MemoryOwnerScope userScope(
            long userId) {
        return com.h.backend.memory.domain.MemoryScopePolicy.toOwnerScope(
                userId, MemoryScopeKind.USER, null, null);
    }

    /** 读超时属于结果不明：update 抛 ResourceAccessException(SocketTimeoutException)。 */
    private static final class FailingUpdateGateway implements Mem0Gateway {
        private final InMemoryMem0Gateway delegate;

        private FailingUpdateGateway(InMemoryMem0Gateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Mem0Models.Mem0Memory> searchExact(Mem0Models.Mem0SearchQuery query) {
            return delegate.searchExact(query);
        }

        @Override
        public List<Mem0Models.Mem0Memory> searchByUser(String mem0UserId, String query, int topK) {
            return delegate.searchByUser(mem0UserId, query, topK);
        }

        @Override
        public Mem0Models.Mem0AddResult add(Mem0Models.Mem0AddCommand command) {
            return delegate.add(command);
        }

        @Override
        public Mem0Models.Mem0Memory get(String remoteMemoryId,
                                         com.h.backend.memory.domain.MemoryScopePolicy.MemoryOwnerScope scope) {
            return delegate.get(remoteMemoryId, scope);
        }

        @Override
        public void update(String remoteMemoryId, String text,
                           com.h.backend.memory.domain.MemoryScopePolicy.MemoryOwnerScope scope) {
            throw new ResourceAccessException("read timed out", new SocketTimeoutException("read timed out"));
        }

        @Override
        public void delete(String remoteMemoryId,
                           com.h.backend.memory.domain.MemoryScopePolicy.MemoryOwnerScope scope) {
            delegate.delete(remoteMemoryId, scope);
        }

        @Override
        public List<Mem0Models.Mem0HistoryEntry> history(String remoteMemoryId,
                                                         com.h.backend.memory.domain.MemoryScopePolicy.MemoryOwnerScope scope) {
            return delegate.history(remoteMemoryId, scope);
        }
    }
}
