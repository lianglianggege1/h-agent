package com.h.backend.chat.interfaces.dto;

public record CreateChatSessionRequest(
        String currentSessionId,
        Long promptId,
        String agentId
) {
}
