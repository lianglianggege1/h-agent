package com.h.backend.chat.memory;

public record ChatMemoryContext(
        Long userId,
        Long promptId,
        String sessionId,
        String agentId,
        String memoryScope
) {

    public ChatMemoryContext(Long userId, Long promptId, String sessionId) {
        this(userId, promptId, sessionId, "standard-chat", "default");
    }
}
