package com.h.backend.chat.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinator 的事务实现（计划 §4.3）。
 *
 * <p>语义实现说明（与 §4.3 条款一一对应）：
 * <ol>
 *   <li>先 {@link ResourceStorage#save}，成功后进入挂接事务——save 失败时
 *       未创建事务也无需补偿。</li>
 *   <li>挂接回调运行在 {@link TransactionTemplate}（PROPAGATION_REQUIRED）内：
 *       无外层事务时由本类创建事务并在 commit 后返回；有外层事务时加入外层，
 *       返回回调结果但保留 completion hook，外层最终 rollback 仍触发补偿
 *       （§4.3.4）。</li>
 *   <li>在执行挂接回调<b>之前</b>注册 {@link TransactionSynchronization}，
 *       afterCompletion 以事务最终状态为准：仅 STATUS_ROLLED_BACK 时补偿。</li>
 *   <li>事务创建或同步器注册失败（hook 尚未生效）时在异常路径立即 discard；
 *       挂接回调失败时由 rollback 的 afterCompletion 补偿——两条路径互斥，
 *       保证每个失败场景 discard 恰好一次。</li>
 *   <li>discard 失败只写安全结构化日志（resourceId + key 尾段 + 错误类别，
 *       不含完整 key、secret、endpoint 或 SDK 异常消息）并计数
 *       （任务 6 再统一可观测性），绝不覆盖原始数据库异常。</li>
 * </ol>
 */
@Component
public class TransactionalResourceWriteCoordinator implements ResourceWriteCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TransactionalResourceWriteCoordinator.class);

    private final ResourceStorage resourceStorage;
    private final TransactionTemplate transactionTemplate;
    private final AtomicLong compensatedDiscardCount = new AtomicLong();
    private final AtomicLong discardFailureCount = new AtomicLong();

    public TransactionalResourceWriteCoordinator(
            ResourceStorage resourceStorage,
            PlatformTransactionManager transactionManager
    ) {
        this.resourceStorage = resourceStorage;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactionTemplate = template;
    }

    @Override
    public <T> T saveAndAttach(ResourceSaveCommand command, ResourceAttachment<T> attachment) {
        StoredResource stored = resourceStorage.save(command);
        // hookRegistered[0] == false 表示 synchronization 尚未注册成功：
        // 此时任何失败都发生在“hook 生效前”，必须立即补偿（§4.3.5）。
        boolean[] hookRegistered = {false};
        try {
            return transactionTemplate.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                            discardQuietly(stored);
                        }
                    }
                });
                hookRegistered[0] = true;
                return attachment.attach(stored);
            });
        } catch (RuntimeException | Error ex) {
            if (!hookRegistered[0]) {
                discardQuietly(stored);
            }
            throw ex;
        }
    }

    /** 补偿删除成功次数（任务 6 统一可观测性前的简单计数）。 */
    long compensatedDiscardCount() {
        return compensatedDiscardCount.get();
    }

    /** 补偿删除失败次数（任务 6 统一可观测性前的简单计数）。 */
    long discardFailureCount() {
        return discardFailureCount.get();
    }

    private void discardQuietly(StoredResource stored) {
        try {
            resourceStorage.discard(stored.storageKey());
            compensatedDiscardCount.incrementAndGet();
        } catch (RuntimeException | Error ex) {
            discardFailureCount.incrementAndGet();
            log.warn(
                    "resource compensate discard failed resourceId={} storageKeySuffix={} reason={}",
                    stored.id(),
                    keySuffix(stored.storageKey()),
                    safeReason(ex)
            );
        }
    }

    /** 只保留 key 的最后一段（uuid.ext），完整 key 不进日志（不变量 17）。 */
    private String keySuffix(String storageKey) {
        if (storageKey == null) {
            return null;
        }
        int slash = storageKey.lastIndexOf('/');
        return slash >= 0 ? storageKey.substring(slash + 1) : storageKey;
    }

    /** 只暴露错误类别（四类错误枚举名或异常类名），不透出可能含敏感信息的消息。 */
    private String safeReason(Throwable exception) {
        if (exception instanceof ResourceStorageException storageException) {
            return storageException.kind().name();
        }
        return exception.getClass().getSimpleName();
    }
}
