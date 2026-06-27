package com.h.backend.voice.tts;

public record MiniMaxTtsResult(
        byte[] audioBytes,
        String mimeType,
        String providerRequestId,
        String model,
        String voiceId
) {
}
