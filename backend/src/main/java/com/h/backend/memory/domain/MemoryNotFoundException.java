package com.h.backend.memory.domain;

/** 本地控制记录不存在或不属于当前用户；按 ID 读写先验证 owner。 */
public class MemoryNotFoundException extends RuntimeException {

    public MemoryNotFoundException(Long localId) {
        super("记忆不存在");
        this.localId = localId;
    }

    private final Long localId;

    public Long localId() {
        return localId;
    }
}
