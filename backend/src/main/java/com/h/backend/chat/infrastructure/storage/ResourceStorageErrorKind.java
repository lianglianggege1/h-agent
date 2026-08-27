package com.h.backend.chat.infrastructure.storage;

/**
 * 存储层稳定的四类错误（计划 §4.5）。第一版只保留这四类，
 * 不得为具体 SDK/实现细节增加新的对外错误种类。
 */
public enum ResourceStorageErrorKind {

    /** 对象不存在，或 owner 查询结果不存在。HTTP 404。 */
    NOT_FOUND(404),

    /** 实际读取超过业务或存储绝对上限。HTTP 413。 */
    SIZE_LIMIT(413),

    /** 对象存储连接、超时、5xx、凭证或权限异常。HTTP 503。 */
    UNAVAILABLE(503),

    /** 其他流或协议错误。HTTP 500。 */
    IO_ERROR(500);

    private final int httpStatus;

    ResourceStorageErrorKind(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
