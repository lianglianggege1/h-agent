package com.h.backend.chat.application.agui;

import reactor.core.publisher.FluxSink;

/** Server-resolved identity of an edit or continuation; never accepts client history as context. */
public record ChatTurnOperation(String action, Long userMessageId, String sourceRunId,
                                String transportRunId, String prefix, String answerId) {
    public static ChatTurnOperation from(FluxSink<?> sink) {
        return sink.contextView() == null ? null : sink.contextView().getOrDefault(ChatTurnOperation.class, null);
    }

    public static String completeAnswer(FluxSink<?> sink, String suffix) {
        var operation = from(sink);
        return operation == null || operation.prefix().isEmpty() ? suffix : operation.prefix() + suffix;
    }
}
