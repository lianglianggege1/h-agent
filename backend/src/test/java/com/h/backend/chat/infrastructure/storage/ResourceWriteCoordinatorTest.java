package com.h.backend.chat.infrastructure.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coordinator 事务补偿语义矩阵（计划 §4.3 / §11.2）。
 *
 * <p>测试方案：mock {@link ResourceStorage} + 真实 Spring 事务（真实 PG 测试库上的
 * DataSourceTransactionManager）。理由：PROPAGATION_REQUIRED 的加入/创建事务、
 * rollback-only 传播、afterCompletion 时序是本类核心语义，纯 Mockito 假同步管理器
 * 无法验证真实 Spring 事务管理器行为；项目已有 @SpringBootTest + 真实 PG 先例
 * （ChatSessionMapperPersistenceTest），数据库写入通过唯一测试前缀隔离并清理。
 */
@SpringBootTest
class ResourceWriteCoordinatorTest {

    private static final String TEST_ID_PREFIX = "rwc-test-";

    @MockitoBean
    private ResourceStorage resourceStorage;

    @Autowired
    private TransactionalResourceWriteCoordinator coordinator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long baseCompensatedDiscardCount;
    private long baseDiscardFailureCount;

    @BeforeEach
    void recordCounterBaseline() {
        baseCompensatedDiscardCount = coordinator.compensatedDiscardCount();
        baseDiscardFailureCount = coordinator.discardFailureCount();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM chat_message_resources WHERE id LIKE ?", TEST_ID_PREFIX + "%");
    }

    @Test
    void withoutOuterTransactionCoordinatorCreatesTransactionAndCommitKeepsObject() {
        when(resourceStorage.save(any(ResourceSaveCommand.class))).thenReturn(stored("res-commit", "key-commit"));
        boolean[] ranInsideTransaction = {false};

        String result = coordinator.saveAndAttach(command(), stored -> {
            ranInsideTransaction[0] = TransactionSynchronizationManager.isActualTransactionActive();
            insertResourceRow(stored);
            return "attached";
        });

        assertEquals("attached", result);
        assertTrue(ranInsideTransaction[0], "挂接回调必须在活动事务内执行");
        verify(resourceStorage, never()).discard(any());
        assertEquals(0L, coordinator.discardFailureCount());
        assertTrue(rowExists("res-commit"), "commit 后数据库行必须存在");
    }

    @Test
    void withoutOuterTransactionCallbackFailureDiscardsOnceAndRethrowsOriginalException() {
        when(resourceStorage.save(any(ResourceSaveCommand.class))).thenReturn(stored("res-cb-fail", "key-cb-fail"));
        IllegalStateException boom = new IllegalStateException("attach failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> coordinator.saveAndAttach(command(), stored -> {
                    insertResourceRow(stored);
                    throw boom;
                }));

        assertSame(boom, thrown, "必须上抛原始数据库异常");
        verify(resourceStorage, times(1)).discard("key-cb-fail");
        assertFalse(rowExists("res-cb-fail"), "回调失败的行必须随事务回滚");
        assertEquals(1L, coordinator.compensatedDiscardCount() - baseCompensatedDiscardCount);
    }

    @Test
    void withOuterTransactionRollbackDiscardsOnce() {
        when(resourceStorage.save(any(ResourceSaveCommand.class))).thenReturn(stored("res-outer-rb", "key-outer-rb"));

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            String result = coordinator.saveAndAttach(command(), stored -> {
                insertResourceRow(stored);
                return "ok";
            });
            assertEquals("ok", result);
            // 模拟外层事务后续业务失败
            tx.setRollbackOnly();
        });

        verify(resourceStorage, times(1)).discard("key-outer-rb");
        assertFalse(rowExists("res-outer-rb"), "外层 rollback 后挂接行必须不存在");
    }

    @Test
    void withOuterTransactionCommitKeepsObject() {
        when(resourceStorage.save(any(ResourceSaveCommand.class))).thenReturn(stored("res-outer-ok", "key-outer-ok"));

        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                coordinator.saveAndAttach(command(), this::insertResourceRow));

        verify(resourceStorage, never()).discard(any());
        assertTrue(rowExists("res-outer-ok"), "外层 commit 后挂接行必须存在");
    }

    @Test
    void constraintViolationInsideCallbackDiscardsBeforeCommit() {
        when(resourceStorage.save(any(ResourceSaveCommand.class))).thenReturn(stored("res-dup", "key-dup"));
        // 预插一行制造主键冲突，模拟 commit 前失败（挂接回调触发约束违规）
        insertResourceRowCommitted(stored("res-dup", "key-dup"));

        assertThrows(DuplicateKeyException.class,
                () -> coordinator.saveAndAttach(command(), this::insertResourceRow));

        verify(resourceStorage, times(1)).discard("key-dup");
    }

    @Test
    void discardFailureIsLoggedCountedAndDoesNotOverrideOriginalException() {
        when(resourceStorage.save(any(ResourceSaveCommand.class))).thenReturn(stored("res-discard-fail", "key-df"));
        doThrow(new ResourceStorageException(ResourceStorageErrorKind.UNAVAILABLE, "对象存储暂时不可用"))
                .when(resourceStorage).discard("key-df");
        IllegalStateException boom = new IllegalStateException("attach failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> coordinator.saveAndAttach(command(), stored -> {
                    throw boom;
                }));

        assertSame(boom, thrown, "discard 失败不得覆盖原始数据库异常");
        verify(resourceStorage, times(1)).discard("key-df");
        assertEquals(1L, coordinator.discardFailureCount() - baseDiscardFailureCount);
        assertEquals(0L, coordinator.compensatedDiscardCount() - baseCompensatedDiscardCount);
    }

    @Test
    void saveFailureNeverEntersTransactionAndNeverDiscards() {
        when(resourceStorage.save(any(ResourceSaveCommand.class)))
                .thenThrow(new ResourceStorageException(ResourceStorageErrorKind.UNAVAILABLE, "对象存储暂时不可用"));

        assertThrows(ResourceStorageException.class,
                () -> coordinator.saveAndAttach(command(), stored -> "attached"));

        verify(resourceStorage, never()).discard(any());
    }

    @Test
    void transactionBeginFailureAfterSuccessfulSaveDiscardsImmediately() {
        ResourceStorage storage = mock(ResourceStorage.class);
        PlatformTransactionManager failingManager = mock(PlatformTransactionManager.class);
        when(failingManager.getTransaction(any(org.springframework.transaction.TransactionDefinition.class)))
                .thenThrow(new CannotCreateTransactionException("simulated transaction begin failure"));
        TransactionalResourceWriteCoordinator local =
                new TransactionalResourceWriteCoordinator(storage, failingManager);
        when(storage.save(any(ResourceSaveCommand.class))).thenReturn(stored("res-begin-fail", "key-bf"));

        assertThrows(CannotCreateTransactionException.class,
                () -> local.saveAndAttach(command(), stored -> "attached"));

        verify(storage, times(1)).discard("key-bf");
        assertEquals(1L, local.compensatedDiscardCount());
    }

    @Test
    void attachmentResultIsPassedThroughWhenJoiningOuterTransaction() {
        when(resourceStorage.save(any(ResourceSaveCommand.class))).thenReturn(stored("res-pass", "key-pass"));

        String result = new TransactionTemplate(transactionManager).execute(tx ->
                coordinator.saveAndAttach(command(), stored -> "joined-result"));

        assertEquals("joined-result", result);
        verify(resourceStorage, never()).discard(any());
    }

    private StoredResource stored(String id, String key) {
        return new StoredResource(id, "OBJECT_STORAGE", key, "image/png", id + ".png", 3L, 1, 1);
    }

    private ResourceSaveCommand command() {
        return new ResourceSaveCommand("IMAGE", new byte[]{1, 2, 3}, "image/png", "png", 1, 1);
    }

    private Void insertResourceRow(StoredResource stored) {
        jdbcTemplate.update("""
                        INSERT INTO chat_message_resources
                            (id, message_id, user_id, resource_type, resource_role, storage_type, storage_key,
                             view_url, download_url, mime_type, file_name, file_size, width, height, created_at)
                        VALUES (?, NULL, ?, 'IMAGE', 'ATTACHMENT', ?, ?, ?, ?, 'image/png', ?, ?, 1, 1, NOW())
                        """,
                TEST_ID_PREFIX + stored.id(), 999999L, stored.storageType(), stored.storageKey(),
                "/api/chat/resources/" + TEST_ID_PREFIX + stored.id() + "/content",
                "/api/chat/resources/" + TEST_ID_PREFIX + stored.id() + "/download",
                stored.fileName(), stored.fileSize());
        return null;
    }

    private void insertResourceRowCommitted(StoredResource stored) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> insertResourceRow(stored));
    }

    private boolean rowExists(String id) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id FROM chat_message_resources WHERE id = ?", String.class, TEST_ID_PREFIX + id);
        return !ids.isEmpty();
    }
}
