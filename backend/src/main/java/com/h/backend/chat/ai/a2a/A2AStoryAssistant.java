package com.h.backend.chat.ai.a2a;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;

public interface A2AStoryAssistant {

    @Agent(name = "A2A故事协作助手", description = "由 backend 编排并通过 A2A 调用 other-agents")
    ResultWithAgenticScope<String> chat(@MemoryId String memoryId, @V("message") String message);
}
