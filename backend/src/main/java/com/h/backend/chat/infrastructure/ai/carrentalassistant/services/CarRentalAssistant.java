package com.h.backend.chat.infrastructure.ai.carrentalassistant.services;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;

// 租车助手
public interface CarRentalAssistant {

    @Agent(name = "租车助手")
    ResultWithAgenticScope<String> chat(@MemoryId String memoryId, @V("message") String message);
}
