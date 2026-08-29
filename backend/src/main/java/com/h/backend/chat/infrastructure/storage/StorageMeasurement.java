package com.h.backend.chat.infrastructure.storage;

/**
 * 一次资源存储操作的测量（统一 Trace 设计 §10.7）。
 *
 * <p>调用处只开始一次测量，并以 success/failure/rejected 结束；open/discard 用
 * 无参 {@link #success()}，save 在确认实际写入大小后用 {@link #success(long)}。
 * 终态 first-wins：重复终态调用是 no-op。遗漏终态时 {@link #close()} 以
 * failure/io_error 安全结束。所有方法 no-throw——指标记录失败
 * 不得改变资源操作结果。
 */
public interface StorageMeasurement extends AutoCloseable {

    /** open/discard 成功结束。 */
    void success();

    /** save 成功结束，actualBytes 为确认写入的实际对象字节量。 */
    void success(long actualBytes);

    /** 存储读写失败结束，按 {@link ResourceStorageErrorKind} 细分。 */
    void failure(ResourceStorageErrorKind kind);

    /** 请求被拒绝结束（非存储失败），按 {@link StorageRejectionKind} 细分。 */
    void rejected(StorageRejectionKind kind);

    /** 遗漏终态时的安全出口：等价 failure/io_error；已终结则 no-op。 */
    @Override
    void close();
}
