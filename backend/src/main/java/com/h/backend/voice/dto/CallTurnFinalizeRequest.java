package com.h.backend.voice.dto;

public record CallTurnFinalizeRequest(String sessionId, String agentId, Long messageId, String transcript) {
}
