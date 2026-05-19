package com.h.backend.chat.dto;

public record CreateChatSessionRequest(
        String currentSessionId,
        Long promptId
) {
}
