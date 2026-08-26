package com.h.backend.chat.infrastructure.storage;

/**
 * Range 语义异常（计划 §4.5 / §6.4）。
 *
 * <ul>
 *   <li>{@link Reason#MALFORMED}：语法非法或多区间，对应 HTTP 400。</li>
 *   <li>{@link Reason#UNSATISFIABLE}：语法合法但结合对象总大小不可满足，
 *       对应 HTTP 416，携带 totalSize 供 Controller 生成
 *       {@code Content-Range: bytes *&#47;total}。</li>
 * </ul>
 *
 * <p>消息只包含固定安全文案，不回显原始 Range 头、object key 或任何存储细节
 * （计划不变量 17）。
 */
public final class ResourceRangeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Reason reason;
    private final Long totalSize;

    private ResourceRangeException(Reason reason, Long totalSize, String safeMessage) {
        super(safeMessage);
        this.reason = reason;
        this.totalSize = totalSize;
    }

    /** 400 语义：Range 请求头语法非法（含多区间）。 */
    public static ResourceRangeException malformed(String safeMessage) {
        return new ResourceRangeException(Reason.MALFORMED, null, safeMessage);
    }

    /** 416 语义：Range 合法但不可满足，携带对象总大小。 */
    public static ResourceRangeException unsatisfiable(long totalSize) {
        return new ResourceRangeException(
                Reason.UNSATISFIABLE,
                totalSize,
                "请求的区间超出资源大小范围");
    }

    public Reason reason() {
        return reason;
    }

    /** 仅 {@link Reason#UNSATISFIABLE} 时非空。 */
    public Long totalSize() {
        return totalSize;
    }

    public enum Reason {
        MALFORMED,
        UNSATISFIABLE
    }
}
