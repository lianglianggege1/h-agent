package com.h.backend.memory.domain;

import dev.langchain4j.invocation.InvocationParameters;

/**
 * 服务端可信的长期记忆执行身份。除 promptId 仅对 standard-chat 可选外，
 * 其余字段在每次内部执行中全部必填；禁止由模型或前端传入。
 */
public record MemoryInvocationContext(
        Long userId,
        String logicalAgentId,
        String memoryRunId,
        Long sourceExecutionId,
        String actualSessionId,
        Long promptId
) {

    public static final String INVOCATION_KEY = "h-agent.memory-context";

    public MemoryInvocationContext {
        require(userId, "userId");
        require(logicalAgentId, "logicalAgentId");
        require(memoryRunId, "memoryRunId");
        require(sourceExecutionId, "sourceExecutionId");
        require(actualSessionId, "actualSessionId");
    }

    public static MemoryInvocationContext from(InvocationParameters parameters) {
        if (parameters == null) {
            return null;
        }
        Object value = parameters.get(INVOCATION_KEY);
        return value instanceof MemoryInvocationContext context ? context : null;
    }

    public InvocationParameters toInvocationParameters() {
        return InvocationParameters.from(INVOCATION_KEY, this);
    }

    /** 叶子 Agent 召回时用构建时绑定的叶子 ID 替换 logicalAgentId，其他字段不变。 */
    public MemoryInvocationContext withLogicalAgentId(String leafAgentId) {
        return new MemoryInvocationContext(
                userId,
                leafAgentId,
                memoryRunId,
                sourceExecutionId,
                actualSessionId,
                promptId
        );
    }

    private static void require(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException("MemoryInvocationContext requires " + field);
        }
    }
}
