package com.h.backend.voice.tts;

public interface MiniMaxTtsClient {
    MiniMaxTtsResult synthesize(MiniMaxTtsRequest request);
}
