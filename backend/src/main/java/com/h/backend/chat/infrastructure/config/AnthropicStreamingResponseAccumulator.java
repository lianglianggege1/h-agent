package com.h.backend.chat.infrastructure.config;

import com.anthropic.core.ObjectMappers;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.RawMessageStreamEvent;

import java.util.Optional;

/** Restores one complete Anthropic response body from its raw SSE events. */
final class AnthropicStreamingResponseAccumulator {

    private final MessageAccumulator delegate = MessageAccumulator.create();

    Optional<String> accumulate(RawMessageStreamEvent event) {
        delegate.accumulate(event);
        if (!event.isMessageStop()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ObjectMappers.jsonMapper().writeValueAsString(delegate.message()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize complete Anthropic response", e);
        }
    }
}
