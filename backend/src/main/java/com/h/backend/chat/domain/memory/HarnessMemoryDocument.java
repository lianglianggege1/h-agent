package com.h.backend.chat.domain.memory;

import java.time.Instant;

/**
 * 当前认证用户唯一的 Harness MEMORY.md 投影；exists=false 表示文件尚未创建。
 */
public record HarnessMemoryDocument(
        String content,
        long revision,
        boolean exists,
        Instant updatedAt
) {
}
