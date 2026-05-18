package com.h.backend.chat.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface HAssistant {

    TokenStream chat(@MemoryId String sessionId, @UserMessage String userMessage);
}
