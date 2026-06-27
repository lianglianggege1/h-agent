package com.h.backend.voice.dto;

public record VoiceResourceResponse(
        String resourceId,
        String viewUrl,
        String downloadUrl,
        String mimeType,
        Long durationMs
) {
}
