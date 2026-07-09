package com.h.otheragents.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfig {

    @Bean
    ToolCallbackProvider mcpTools(AdditionMcpTool additionMcpTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(additionMcpTool)
                .build();
    }
}
