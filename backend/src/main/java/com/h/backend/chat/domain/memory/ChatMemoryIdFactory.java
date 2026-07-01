package com.h.backend.chat.domain.memory;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import org.springframework.stereotype.Component;

@Component
public class ChatMemoryIdFactory {

    public static final String DEFAULT_MEMORY_SCOPE = "default";

    public String executionId(Long userId, String sessionId, String agentId) {
        return "exec:v2:user:" + userId + ":session:" + sessionId + ":agent:" + agentId;
    }

    public String scopedMemoryId(String executionId, String scopeKey) {
        ChatMemoryContext context = parse(executionId);
        return "mem:v2:user:" + context.userId()
                + ":session:" + context.sessionId()
                + ":agent:" + context.agentId()
                + ":scope:" + scopeKey;
    }

    public ChatMemoryContext parse(Object memoryId) {
        String value = String.valueOf(memoryId);
        if (value.startsWith("exec:v2:")) {
            return parseExecutionId(value);
        }
        if (value.startsWith("mem:v2:")) {
            return parseScopedMemoryId(value);
        }
        return parseLegacy(value);
    }

    private ChatMemoryContext parseExecutionId(String value) {
        String[] parts = value.split(":", 8);
        if (parts.length == 8
                && "exec".equals(parts[0])
                && "v2".equals(parts[1])
                && "user".equals(parts[2])
                && "session".equals(parts[4])
                && "agent".equals(parts[6])
                && StringUtils.isNotBlank(parts[3])
                && StringUtils.isNotBlank(parts[5])
                && StringUtils.isNotBlank(parts[7])) {
            return new ChatMemoryContext(
                    Long.valueOf(parts[3]),
                    null,
                    parts[5],
                    parts[7],
                    DEFAULT_MEMORY_SCOPE
            );
        }
        throw invalid(value);
    }

    private ChatMemoryContext parseScopedMemoryId(String value) {
        String[] parts = value.split(":", 10);
        if (parts.length == 10
                && "mem".equals(parts[0])
                && "v2".equals(parts[1])
                && "user".equals(parts[2])
                && "session".equals(parts[4])
                && "agent".equals(parts[6])
                && "scope".equals(parts[8])
                && StringUtils.isNotBlank(parts[3])
                && StringUtils.isNotBlank(parts[5])
                && StringUtils.isNotBlank(parts[7])
                && StringUtils.isNotBlank(parts[9])) {
            return new ChatMemoryContext(
                    Long.valueOf(parts[3]),
                    null,
                    parts[5],
                    parts[7],
                    parts[9]
            );
        }
        throw invalid(value);
    }

    private ChatMemoryContext parseLegacy(String value) {
        String[] parts = value.split(":", 4);
        if (parts.length == 3 && StringUtils.isNotBlank(parts[2])) {
            return new ChatMemoryContext(
                    Long.valueOf(parts[0]),
                    Long.valueOf(parts[1]),
                    parts[2],
                    ChatAgentIds.STANDARD_CHAT,
                    DEFAULT_MEMORY_SCOPE
            );
        }
        if (parts.length == 4 && "agent".equals(parts[1]) && StringUtils.isNotBlank(parts[3])) {
            return new ChatMemoryContext(
                    Long.valueOf(parts[0]),
                    null,
                    parts[3],
                    parts[2],
                    DEFAULT_MEMORY_SCOPE
            );
        }
        throw invalid(value);
    }

    private IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException("Invalid memoryId: " + value);
    }
}
