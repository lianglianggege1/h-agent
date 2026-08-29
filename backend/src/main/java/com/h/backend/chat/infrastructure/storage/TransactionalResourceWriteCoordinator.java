package com.h.backend.chat.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 *   <li>discard 失败只记 compensation Meter（统一 Trace 设计 §10.7：
 *       {@link ResourceStorageMeters#recordCompensationFailure}）并输出一条
 *       ERROR 级脱敏告警（只含 resourceId + key 尾段 + 错误类别，不含完整 key、
 *       secret、endpoint 或 SDK 异常消息；计划不变量 17），绝不覆盖原始数据库
 *       异常。成功操作不写日志，避免刷屏。</li>
 * </ol>
 */
@Component
public class TransactionalResourceWriteCoordinator implements ResourceWriteCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TransactionalResourceWriteCoordinator.class);

    private final ResourceStorage resourceStorage;
    private final TransactionTemplate transactionTemplate;
    private final ResourceStorageMeters meters;

    public TransactionalResourceWriteCoordinator(
            ResourceStorage resourceStorage,
            PlatformTransactionManager transactionManager,
            ResourceStorageMeters meters
    ) {
        this.resourceStorage = resourceStorage;
        this.meters = meters;
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

    private void discardQuietly(StoredResource stored) {
        try {
            resourceStorage.discard(stored.storageKey());
            meters.recordCompensationSuccess();
        } catch (RuntimeException | Error ex) {
            ResourceStorageErrorKind kind = kindOf(ex);
            // Meter 不取代日志（统一 Trace 设计 §10.8）：补偿失败继续输出脱敏 ERROR 告警，
            // 不覆盖原始事务异常。
            meters.recordCompensationFailure(kind);
            String keySuffix = keySuffix(stored.storageKey());
            log.error(
                    "资源补偿删除失败，需人工关注孤儿对象 operation=discard errorKind={} resourceId={} storageKeySuffix={}",
                    kind,
                    stored.id() == null ? "-" : stored.id(),
                    keySuffix == null || keySuffix.isBlank() ? "-" : keySuffix);
        }
    }

    private static ResourceStorageErrorKind kindOf(Throwable failure) {
        return failure instanceof ResourceStorageException storageException
                ? storageException.kind()
                : ResourceStorageErrorKind.IO_ERROR;
    }

    /** 只保留 key 的最后一段（uuid.ext），完整 key 不进日志（不变量 17）。 */
    private String keySuffix(String storageKey) {
        if (storageKey == null) {
            return null;
        }
        int slash = storageKey.lastIndexOf('/');
        return slash >= 0 ? storageKey.substring(slash + 1) : storageKey;
    }
}
