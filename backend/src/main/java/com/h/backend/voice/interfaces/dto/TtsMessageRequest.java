package com.h.backend.voice.interfaces.dto;

public record TtsMessageRequest(String sessionId, String agentId, Long messageId) {
}
