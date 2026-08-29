package com.h.backend.memory.domain;

/** 本地 version CAS 冲突：返回 409，不可作为网络错误重试。 */
public class MemoryVersionConflictException extends RuntimeException {

    private final Long localId;
    private final int expectedVersion;
    private final int actualVersion;

    public MemoryVersionConflictException(Long localId, int expectedVersion, int actualVersion) {
        super("记忆已被修改，请刷新后重试");
        this.localId = localId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public Long localId() {
        return localId;
    }

    public int expectedVersion() {
        return expectedVersion;
    }

    public int actualVersion() {
        return actualVersion;
    }
}
