package com.h.backend.voice.application;

import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import java.util.function.Consumer;

public interface VoiceReply {
    String modelName();
    Execution prepare(String systemPrompt, List<ChatMessage> history, Consumer<String> text, Consumer<String> terminal);
    interface Execution {
        void start();
        void cancel();
    }
}
