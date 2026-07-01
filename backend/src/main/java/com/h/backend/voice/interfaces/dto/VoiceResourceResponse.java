package com.h.backend.voice.interfaces.dto;

public record VoiceResourceResponse(
        String resourceId,
        String viewUrl,
        String downloadUrl,
        String mimeType,
        Long durationMs
) {
}
