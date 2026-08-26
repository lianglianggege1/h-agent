package com.h.backend.chat.infrastructure.storage;

/**
 * 资源字节能力的外部 seam（计划 §4.1）。
 *
 * <p>接口契约：
 * <ul>
 *   <li>{@link #save}：写入一个新对象，返回 {@link StoredResource}。生产 Adapter
 *       （任务 2 的 {@code MinioResourceStorage}）固定返回
 *       {@code ResourceStorageType.OBJECT_STORAGE.value()}；
 *       过渡期 {@link LocalFileResourceStorage} 仍返回 {@code LOCAL_FILE}。
 *       Adapter 负责关闭 {@link ResourceSaveCommand} 提供的输入流
 *       （成功与失败路径都要关闭）。</li>
 *   <li>{@link #open}：内部执行 stat 并结合对象总大小解析实际 Range，
 *       返回带 offset/responseLength/partial 的 {@link ResourceContent}；
 *       不额外暴露通用 stat。语法级 Range 解析由
 *       {@link ResourceRange#fromHeader(String)} 提供，可满足性在本方法内判定。</li>
 *   <li>{@link #discard}：幂等删除——对象不存在时正常返回；
 *       失败抛出 {@link ResourceStorageException}。仅用于尚未成功挂接
 *       数据库对象的补偿，不用于业务删除（计划不变量 15）。</li>
 * </ul>
 *
 * <p>错误语义（计划 §4.5）：实现通过 {@link ResourceStorageException} 只暴露
 * 四类稳定错误（NOT_FOUND/SIZE_LIMIT/UNAVAILABLE/IO_ERROR），消息必须脱敏；
 * Range 语义错误通过 {@link ResourceRangeException} 暴露（400/416）。
 *
 * <p>URL 构造不属于本 seam（计划 §2.4.3/§4.4）——
 * 应用层 {@code ChatResourceUrls} 负责 view/download URL。
 */
public interface ResourceStorage {

    StoredResource save(ResourceSaveCommand command);

    ResourceContent open(String storageKey, ResourceRange range);

    void discard(String storageKey);
}
