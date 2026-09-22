package com.h.backend.voice.application;

import dev.langchain4j.data.message.ChatMessage;
import java.util.List;

public record VoiceReplyContext(
        Long userId,
        String sessionId,
        Long promptId,
        String agentId,
        Object agentBean,
        String systemPrompt,
        List<ChatMessage> history,
        String userMessage,
        Long runId,
        Long userMessageId,
        String modelName
) {}
