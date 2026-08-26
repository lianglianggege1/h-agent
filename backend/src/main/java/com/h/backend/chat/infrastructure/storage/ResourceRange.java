package com.h.backend.chat.infrastructure.storage;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语法级单区间 Range（计划 §6.4）。
 *
 * <p>本类型只负责 HTTP Range 头的<b>语法</b>解析；对象总大小未知，
 * 因此可满足性判断发生在 {@link #resolve(long)}。
 *
 * <p>支持且仅支持：
 * <ul>
 *   <li>{@code bytes=start-end}</li>
 *   <li>{@code bytes=start-}</li>
 *   <li>{@code bytes=-suffix}</li>
 *   <li>{@link #fullRead()}：无 Range 的完整读取</li>
 * </ul>
 *
 * <p>malformed（非法语法、多区间逗号、负数、start&gt;end）在
 * {@link #fromHeader(String)} 即抛出 400 语义的 {@link ResourceRangeException}；
 * 语法合法但结合总大小不可满足（start&gt;=total、suffix=0、空对象）在
 * {@link #resolve(long)} 抛出携带 totalSize 的 416 语义异常，
 * 供 Controller 生成 {@code Content-Range: bytes *&#47;total}。
 */
public record ResourceRange(Kind kind, Long start, Long end, Long suffixLength) {

    private static final Pattern CLOSED_RANGE = Pattern.compile("bytes=(\\d+)-(\\d+)");
    private static final Pattern OPEN_RANGE = Pattern.compile("bytes=(\\d+)-");
    private static final Pattern SUFFIX_RANGE = Pattern.compile("bytes=-(\\d+)");

    /** 单例常量：完整读取。 */
    private static final ResourceRange FULL = new ResourceRange(Kind.FULL, null, null, null);

    public ResourceRange {
        Objects.requireNonNull(kind, "kind must not be null");
        switch (kind) {
            case FULL -> {
                requireUnused(start, "start");
                requireUnused(end, "end");
                requireUnused(suffixLength, "suffixLength");
            }
            case START_END, START_TO_END -> {
                requireValue(start, "start");
                requireUnused(suffixLength, "suffixLength");
                if (start < 0) {
                    throw malformed("起始字节不能为负数");
                }
                if (kind == Kind.START_END) {
                    requireValue(end, "end");
                    if (end < 0) {
                        throw malformed("结束字节不能为负数");
                    }
                    if (end < start) {
                        throw malformed("结束字节不能小于起始字节");
                    }
                } else {
                    requireUnused(end, "end");
                }
            }
            case SUFFIX -> {
                requireValue(suffixLength, "suffixLength");
                requireUnused(start, "start");
                requireUnused(end, "end");
                if (suffixLength < 0) {
                    throw malformed("后缀长度不能为负数");
                }
            }
        }
    }

    /** 无 Range：完整读取，resolve 后为 200 语义。 */
    public static ResourceRange fullRead() {
        return FULL;
    }

    /**
     * 解析 HTTP Range 头。仅接受单个 byte 区间；语法非法、多区间、
     * 负数或 start&gt;end 抛出 400 语义的 {@link ResourceRangeException}。
     */
    public static ResourceRange fromHeader(String rangeHeader) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            throw malformed("Range 请求头为空");
        }
        String header = rangeHeader.strip();
        if (header.contains(",")) {
            throw malformed("Range 请求头不允许多个区间");
        }
        Matcher closed = CLOSED_RANGE.matcher(header);
        if (closed.matches()) {
            return new ResourceRange(Kind.START_END, parse(closed.group(1)), parse(closed.group(2)), null);
        }
        Matcher open = OPEN_RANGE.matcher(header);
        if (open.matches()) {
            return new ResourceRange(Kind.START_TO_END, parse(open.group(1)), null, null);
        }
        Matcher suffix = SUFFIX_RANGE.matcher(header);
        if (suffix.matches()) {
            return new ResourceRange(Kind.SUFFIX, null, null, parse(suffix.group(1)));
        }
        throw malformed("Range 请求头语法无效");
    }

    /**
     * 结合对象总大小解析实际区间。
     *
     * <ul>
     *   <li>FULL：offset=0、length=totalSize、partial=false（200）。</li>
     *   <li>其余形态：返回实际 offset/length、partial=true（206）；
     *       end 超出 totalSize 时截断到 totalSize-1。</li>
     *   <li>语法合法但不可满足（start&gt;=totalSize、suffix=0、解析后长度为 0）：
     *       抛出携带 totalSize 的 416 语义异常。</li>
     * </ul>
     */
    public Resolved resolve(long totalSize) {
        if (totalSize < 0) {
            throw new IllegalArgumentException("totalSize must not be negative");
        }
        if (kind == Kind.FULL) {
            return new Resolved(0L, totalSize, false);
        }
        long offset;
        long length;
        switch (kind) {
            case START_END, START_TO_END -> {
                offset = start;
                length = (kind == Kind.START_END ? Math.min(end, totalSize - 1) : totalSize - 1) - offset + 1;
            }
            case SUFFIX -> {
                long effective = Math.min(suffixLength, totalSize);
                offset = totalSize - effective;
                length = effective;
            }
            default -> throw new IllegalStateException("Unknown range kind: " + kind);
        }
        if (length <= 0) {
            throw ResourceRangeException.unsatisfiable(totalSize);
        }
        return new Resolved(offset, length, true);
    }

    private static long parse(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ex) {
            throw malformed("Range 数值超出可表示范围");
        }
    }

    private static void requireValue(Long value, String field) {
        if (value == null) {
            throw malformed("Range 缺少必要数值: " + field);
        }
    }

    private static void requireUnused(Long value, String field) {
        if (value != null) {
            throw malformed("Range 携带了当前形态不允许的数值: " + field);
        }
    }

    private static ResourceRangeException malformed(String safeMessage) {
        // 消息为固定安全文案，不回显原始请求头内容。
        return ResourceRangeException.malformed(safeMessage);
    }

    public enum Kind {
        FULL,
        START_END,
        START_TO_END,
        SUFFIX
    }

    /**
     * 结合对象总大小解析后的实际区间：{@code offset} 为读取起点，
     * {@code length} 为本次响应字节数，{@code partial} 表示 206 部分内容。
     */
    public record Resolved(long offset, long length, boolean partial) {
    }
}
