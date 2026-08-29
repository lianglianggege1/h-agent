package com.h.backend.memory.domain;

/** 显式更新：本地 version CAS，冲突返回 409，不可作为网络错误重试。 */
public record ExplicitMemoryUpdate(
        Long userId,
        Long localId,
        String text,
        int expectedVersion
) {
    public ExplicitMemoryUpdate {
        if (userId == null || localId == null) {
            throw new IllegalArgumentException("userId and localId are required");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
    }
}
