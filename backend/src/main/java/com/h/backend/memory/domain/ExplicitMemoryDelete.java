package com.h.backend.memory.domain;

/** 显式删除：本地 version CAS，冲突返回 409；删除后验证远程正文不再泄露。 */
public record ExplicitMemoryDelete(
        Long userId,
        Long localId,
        int expectedVersion
) {
    public ExplicitMemoryDelete {
        if (userId == null || localId == null) {
            throw new IllegalArgumentException("userId and localId are required");
        }
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
    }
}
