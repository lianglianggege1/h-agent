package com.h.backend.voice.dto;

public record TtsPreviewRequest(String sessionId, String agentId, String text) {
}
