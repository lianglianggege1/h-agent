package com.h.backend.chat.infrastructure.storage;

/**
 * 存储侧"拒绝"语义（统一 Trace 设计 §10.7）：请求被业务/协议规则拒绝，
 * 没有发生存储读写失败，因此<b>不计入 MinIO 可用性失败率</b>。
 *
 * <p>Range 语义拒绝（{@link ResourceRangeException} 的 malformed/unsatisfiable）
 * 统一映射到 {@link #RANGE}，对应 tag {@code error.kind=range}。
 */
public enum StorageRejectionKind {

    /** HTTP Range 语义拒绝（400/416）。 */
    RANGE
}
