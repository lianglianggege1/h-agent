package com.h.backend.chat.infrastructure.ai.carrentalassistant.services;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;


public interface ExportAssistant {

    @Agent(name = "专家智能体")
    ResultWithAgenticScope<String> chat(@MemoryId String memoryId, @V("request") String request, InvocationParameters parameters);
}
