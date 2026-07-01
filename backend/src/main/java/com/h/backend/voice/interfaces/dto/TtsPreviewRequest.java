package com.h.backend.voice.interfaces.dto;

public record TtsPreviewRequest(String sessionId, String agentId, String text) {
}
