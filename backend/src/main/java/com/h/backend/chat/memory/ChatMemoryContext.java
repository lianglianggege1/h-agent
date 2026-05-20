package com.h.backend.chat.memory;

public record ChatMemoryContext(
        Long userId,
        Long promptId,
        String sessionId
) {
}
