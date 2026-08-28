package com.h.backend.memory.domain;

/**
 * 显式变更结果。state：SUCCEEDED 表示已获得可核验结果；
 * RECONCILING 表示远程结果不明，等待 reconciliation，不谎报成功。
 */
public record MemoryMutationResult(
        Long localId,
        String remoteMemoryId,
        int version,
        String state,
        String message
) {
    public static final String STATE_SUCCEEDED = "SUCCEEDED";
    public static final String STATE_RECONCILING = "RECONCILING";
}
