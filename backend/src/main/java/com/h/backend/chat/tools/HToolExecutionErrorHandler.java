package com.h.backend.chat.tools;

import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import org.springframework.stereotype.Component;

@Component
public class HToolExecutionErrorHandler implements ToolExecutionErrorHandler {

    @Override
    public ToolErrorHandlerResult handle(Throwable error, ToolErrorContext context) {
        String errorMessage = error.getMessage();
        return ToolErrorHandlerResult.text(errorMessage);
    }

}
