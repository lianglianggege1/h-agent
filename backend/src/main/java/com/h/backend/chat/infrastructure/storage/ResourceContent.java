package com.h.backend.chat.infrastructure.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * 一次资源读取的结果（计划 §4.1/§6.4）。
 *
 * <ul>
 *   <li>{@code inputStream}：可关闭输入流，只覆盖本次请求的字节区间，
 *       由调用方关闭。</li>
 *   <li>{@code mimeType}：对象 Content-Type。</li>
 *   <li>{@code totalSize}：对象总大小（stat 结果）。</li>
 *   <li>{@code responseLength}：本次响应的字节数。</li>
 *   <li>{@code offset}：本次响应在对象中的起始字节。</li>
 *   <li>{@code partial}：是否为 206 部分内容；完整读取（无 Range）为 false。</li>
 * </ul>
 */
public record ResourceContent(
        InputStream inputStream,
        String mimeType,
        long totalSize,
        long responseLength,
        long offset,
        boolean partial
) implements Closeable {

    @Override
    public void close() throws IOException {
        if (inputStream != null) {
            inputStream.close();
        }
    }
}
