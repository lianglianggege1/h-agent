package com.h.backend.chat.infrastructure.image;

public interface MiniMaxImageClient {

    MiniMaxImageGenerationResult generate(MiniMaxImageGenerationRequest request);
}
