package com.h.otheragents.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;

class AdditionMcpToolTest {

    @Test
    void addsTwoNumbers() {
        AdditionMcpTool tool = new AdditionMcpTool();

        int result = tool.addNumbers(1, 2);

        assertThat(result).isEqualTo(3);
    }

    @Test
    void registersAddNumbersTool() {
        ToolCallbackProvider provider = new McpToolConfig().mcpTools(new AdditionMcpTool());

        assertThat(provider.getToolCallbacks())
                .singleElement()
                .satisfies(callback -> assertThat(callback.getToolDefinition().name()).isEqualTo("add_numbers"));
    }
}
