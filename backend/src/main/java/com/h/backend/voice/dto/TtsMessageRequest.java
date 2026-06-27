package com.h.backend.voice.dto;

public record TtsMessageRequest(String sessionId, String agentId, Long messageId) {
}
