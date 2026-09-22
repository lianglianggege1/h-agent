package com.h.backend.voice.application;

import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import java.util.function.Consumer;

public interface VoiceReply {
    String modelName();

    Execution prepare(String systemPrompt, List<ChatMessage> history, Consumer<String> text, Consumer<String> terminal);

    default Execution prepare(VoiceReplyContext ctx, Consumer<String> text, Consumer<String> terminal) {
        return prepare(ctx.systemPrompt(), ctx.history(), text, terminal);
    }

    default boolean supports(String agentId) { return true; }

    interface Execution {
        void start();
        void cancel();
    }
}
