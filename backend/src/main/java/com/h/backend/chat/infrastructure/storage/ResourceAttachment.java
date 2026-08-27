package com.h.backend.chat.infrastructure.storage;

/**
 * 数据库挂接回调（计划 §4.3）：在 Coordinator 开启（或加入）的
 * PROPAGATION_REQUIRED 事务内执行，把已写入的 {@link StoredResource}
 * 挂接到业务表（如 chat_message_resources、generation_tasks）。
 *
 * <p>回调抛出异常或事务最终 rollback 时，Coordinator 会 best-effort
 * discard 对象，因此回调内的数据库写入必须与对象挂接属于同一事务语义单元。
 *
 * <p>定义在 storage 包的原因：入参 {@link StoredResource} 与
 * {@link ResourceSaveCommand} 同包内聚，Coordinator 接口语义自包含；
 * generation/voice 等跨模块调用方只接触本接口，不接触底层存储类型。
 *
 * @param <T> 挂接结果类型
 */
@FunctionalInterface
public interface ResourceAttachment<T> {

    /**
     * 把已保存的资源挂接到数据库。
     *
     * @param stored 刚写入成功的资源（storageType 恒为 OBJECT_STORAGE）
     * @return 挂接结果，由调用方决定形态
     */
    T attach(StoredResource stored);
}
