package com.h.backend.memory.domain;

/**
 * 一个成功外层 turn 的待 capture 事实。只携带已持久化消息的 ID 引用，
 * 正文由 worker 回读；禁止附带 system/reasoning/tool 内容或未持久化文本。
 */
public record CompletedTurn(
        MemoryInvocationContext context,
        Long userMessageId,
        Long assistantMessageId,
        MemoryScopeKind captureScope
) {

    public static final String OPERATION_KEY_SUFFIX = "long-term-memory-capture:v1";

    public CompletedTurn {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (captureScope == null) {
            throw new IllegalArgumentException("captureScope is required");
        }
        if (userMessageId == null || assistantMessageId == null) {
            throw new IllegalArgumentException("persisted message ids are required");
        }
    }

    /** 外层 turn 稳定幂等键，outbox 唯一约束与 Mem0 请求 metadata 均携带该键。 */
    public String operationKey() {
        return context.userId() + ":" + context.logicalAgentId() + ":" + context.memoryRunId()
                + ":" + context.sourceExecutionId() + ":" + OPERATION_KEY_SUFFIX;
    }
}
