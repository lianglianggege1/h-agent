package com.h.backend.chat.interfaces.dto;

public record ActivateHistorySessionRequest(
        String targetSessionId,
        String currentSessionId
) {
}
