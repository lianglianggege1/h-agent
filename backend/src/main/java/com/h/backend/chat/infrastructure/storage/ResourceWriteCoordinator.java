package com.h.backend.chat.infrastructure.storage;

/**
 * 资源写入协调器（计划 §4.3）：所有业务写入点只依赖本接口，
 * 不直接调用 {@link ResourceStorage#save}（拒绝方案 13）。
 *
 * <p>执行语义：
 * <ol>
 *   <li>调用 {@link ResourceStorage#save}，对象成功后获得 {@link StoredResource}。</li>
 *   <li>使用 PROPAGATION_REQUIRED 执行数据库挂接回调；没有事务时创建事务，
 *       已有事务时加入。</li>
 *   <li>在执行挂接回调前注册 TransactionSynchronization，以事务最终状态为准。</li>
 *   <li>Coordinator 自己创建事务时，在 commit 后返回；加入外层事务时，返回回调结果
 *       但保留 completion hook，外层最终 rollback 仍会触发补偿。</li>
 *   <li>创建事务、注册 synchronization 或执行回调在 hook 生效前失败时，立即 discard。</li>
 *   <li>事务最终 rollback 时调用 {@code discard(storageKey)}；commit 后不删除。</li>
 *   <li>discard 失败只写安全结构化日志和计数，不覆盖原始数据库异常。</li>
 * </ol>
 */
public interface ResourceWriteCoordinator {

    /**
     * 保存资源对象并挂接数据库记录。
     *
     * @param command    流式写入命令（计划 §6.1）
     * @param attachment 数据库挂接回调；在 PROPAGATION_REQUIRED 事务内执行，
     *                   事务最终 rollback 时对象被 best-effort 补偿删除
     * @return 挂接回调的返回值
     */
    <T> T saveAndAttach(ResourceSaveCommand command, ResourceAttachment<T> attachment);
}
