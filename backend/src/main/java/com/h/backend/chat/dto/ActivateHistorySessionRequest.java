package com.h.backend.chat.dto;

public record ActivateHistorySessionRequest(
        String targetSessionId,
        String currentSessionId
) {
}
