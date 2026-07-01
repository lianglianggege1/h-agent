package com.h.backend.voice.interfaces.dto;

public record CallTurnFinalizeRequest(String sessionId, String agentId, Long messageId, String transcript) {
}
