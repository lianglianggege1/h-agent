package com.h.backend.memory.domain;

/** 按 ID 操作的用户记忆定位：先在本地验证 owner，再触达远程。 */
public record OwnedMemoryId(
        Long userId,
        Long localId
) {
    public OwnedMemoryId {
        if (userId == null || localId == null) {
            throw new IllegalArgumentException("userId and localId are required");
        }
    }
}
