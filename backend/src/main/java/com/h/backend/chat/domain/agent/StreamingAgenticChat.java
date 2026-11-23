package com.h.backend.chat.domain.agent;

import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.invocation.InvocationParameters;

import java.util.function.Consumer;

public interface StreamingAgenticChat {
    ResultWithAgenticScope<String> chatStreaming(
            String memoryId, String message, InvocationParameters parameters, Consumer<String> chunks);
}
