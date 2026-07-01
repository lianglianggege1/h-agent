package com.h.backend.chat.infrastructure.tools;

import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import org.springframework.stereotype.Component;

@Component
public class HToolArgumentsErrorHandler implements ToolArgumentsErrorHandler {
    @Override
    public ToolErrorHandlerResult handle(Throwable error, ToolErrorContext context) {
        String errorMessage = error.getMessage();
        return ToolErrorHandlerResult.text(errorMessage);
    }
}
