package com.h.backend.chat.infrastructure.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;

/**
 * 资源写入命令（计划 §6.1 流式写入契约）。
 *
 * <ul>
 *   <li>{@code content} / {@code contentStream} 二选一：byte[] 形态供图片生成等
 *       现有调用方使用（自动声明 {@code declaredSize = content.length}）；
 *       流式形态单次可消费，由 Adapter 负责关闭。</li>
 *   <li>{@code declaredSize} 可空；调用方已知大小时必须传（先于读取参与上限检查）。</li>
 *   <li>{@code maxBytes} 是调用方业务上限；{@code <=0} 表示使用绝对上限
 *       {@link #ABSOLUTE_MAX_BYTES}（不是无限）。实际上限是二者较小值，
 *       上限不能被调用方放大。</li>
 * </ul>
 *
 * <p>本命令不携带 sessionId、prompt 等业务上下文（计划不变量 12：
 * object key 与 metadata 不包含会话 ID、prompt、原文件名或 display name）。
 */
public final class ResourceSaveCommand {

    /** 存储层绝对上限：500 MiB。可配置但不可被调用方放大。 */
    public static final long ABSOLUTE_MAX_BYTES = 524_288_000L;

    private final String resourceType;
    private final byte[] content;
    private final InputStream contentStream;
    private final Long declaredSize;
    private final String mimeType;
    private final String extension;
    private final Integer width;
    private final Integer height;
    private final long maxBytes;

    public ResourceSaveCommand(
            String resourceType,
            byte[] content,
            String mimeType,
            String extension,
            Integer width,
            Integer height
    ) {
        this(
                resourceType,
                content,
                null,
                content == null ? null : (long) content.length,
                mimeType,
                extension,
                width,
                height,
                0L
        );
    }

    public static ResourceSaveCommand fromStream(
            String resourceType,
            InputStream contentStream,
            String mimeType,
            String extension,
            long maxBytes
    ) {
        return fromStream(resourceType, contentStream, null, mimeType, extension, maxBytes);
    }

    public static ResourceSaveCommand fromStream(
            String resourceType,
            InputStream contentStream,
            Long declaredSize,
            String mimeType,
            String extension,
            long maxBytes
    ) {
        return new ResourceSaveCommand(
                resourceType,
                null,
                Objects.requireNonNull(contentStream, "contentStream must not be null"),
                declaredSize,
                mimeType,
                extension,
                null,
                null,
                maxBytes
        );
    }

    private ResourceSaveCommand(
            String resourceType,
            byte[] content,
            InputStream contentStream,
            Long declaredSize,
            String mimeType,
            String extension,
            Integer width,
            Integer height,
            long maxBytes
    ) {
        this.resourceType = resourceType;
        this.content = content;
        this.contentStream = contentStream;
        this.declaredSize = declaredSize;
        this.mimeType = mimeType;
        this.extension = extension;
        this.width = width;
        this.height = height;
        this.maxBytes = maxBytes;
    }

    public String resourceType() { return resourceType; }

    /** byte[] 形态的内容；流式形态返回 null。 */
    public byte[] content() { return content; }

    /** 调用方声明的对象大小；未知为 null。 */
    public Long declaredSize() { return declaredSize; }

    public String mimeType() { return mimeType; }

    public String extension() { return extension; }

    public Integer width() { return width; }

    public Integer height() { return height; }

    /** 业务上限；{@code <=0} 表示使用绝对上限。 */
    public long maxBytes() { return maxBytes; }

    /** 实际上限 = min(业务上限, 绝对上限)；业务上限 {@code <=0} 时取绝对上限。 */
    public long effectiveMaxBytes() {
        if (maxBytes <= 0) {
            return ABSOLUTE_MAX_BYTES;
        }
        return Math.min(maxBytes, ABSOLUTE_MAX_BYTES);
    }

    /**
     * 打开内容流：byte[] 形态包装为 {@link ByteArrayInputStream}；
     * 流式形态返回原始流（单次可消费，由 Adapter 关闭）。
     */
    public InputStream openContentStream() {
        return contentStream == null
                ? new ByteArrayInputStream(content == null ? new byte[0] : content)
                : contentStream;
    }
}
