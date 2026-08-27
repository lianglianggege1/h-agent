package com.h.backend.generation.application.port.out;

import com.h.backend.generation.domain.model.GeneratedArtifact;

/**
 * 异步生成对象的数据库挂接回调（新计划任务 3）。
 *
 * <p>异步生成对象只有写入 generation_tasks 的 artifact type/key 后才算挂接；
 * 本回调由应用服务提供（task.complete + taskRepository.save + 投影更新），
 * 在存储 Adapter 转发给 {@code ResourceWriteCoordinator} 的挂接事务内执行，
 * 事务最终 rollback 时对象被 best-effort 补偿删除。
 *
 * @param <T> 挂接结果类型
 */
@FunctionalInterface
public interface GeneratedArtifactAttachment<T> {

    /**
     * 把已保存的生成对象挂接到 generation_tasks。
     *
     * @param artifact 刚写入成功的生成对象
     * @return 挂接结果
     */
    T attach(GeneratedArtifact artifact);
}
