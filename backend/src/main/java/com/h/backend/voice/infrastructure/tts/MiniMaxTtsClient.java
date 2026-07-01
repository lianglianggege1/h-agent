package com.h.backend.voice.infrastructure.tts;

public interface MiniMaxTtsClient {
    MiniMaxTtsResult synthesize(MiniMaxTtsRequest request);
}
