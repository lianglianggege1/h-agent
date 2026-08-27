package com.h.otheragents.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

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
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(new AdditionMcpTool())
                .build();

        assertThat(provider.getToolCallbacks())
                .singleElement()
                .satisfies(callback -> assertThat(callback.getToolDefinition().name()).isEqualTo("add_numbers"));
    }
}
