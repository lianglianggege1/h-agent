package com.h.backend.chat.infrastructure.storage;

/**
 * 存储层统一异常（计划 §4.5）。
 *
 * <p>脱敏契约（计划不变量 17）：{@link #getMessage()} 永远只包含调用方
 * 传入的安全描述——Bucket、Endpoint、access key/secret、完整 object key、
 * 签名 query 以及底层 SDK 异常消息都不得进入异常消息与前端响应。
 * 原始异常可保留在 cause 链中供结构化日志使用，但它的消息不会被并入本异常。
 */
public final class ResourceStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ResourceStorageErrorKind kind;

    public ResourceStorageException(ResourceStorageErrorKind kind, String safeMessage) {
        super(safeMessage);
        this.kind = kind;
    }

    public ResourceStorageException(ResourceStorageErrorKind kind, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.kind = kind;
    }

    public ResourceStorageErrorKind kind() {
        return kind;
    }
}
