package com.h.backend.chat.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OtherAgentsMcpToolProviderTest {

    @Test
    void discoversAndExecutesRemoteMcpToolsWithoutLocalToolWrappers() {
        McpClient mcpClient = mock(McpClient.class);
        when(mcpClient.listTools()).thenReturn(List.of(addNumbersTool()));
        when(mcpClient.executeTool(argThat(OtherAgentsMcpToolProviderTest::isAddNumbersRequest),
                org.mockito.ArgumentMatchers.any(InvocationContext.class)))
                .thenReturn(ToolExecutionResult.builder()
                        .resultText("3")
                        .build());
        McpToolProvider provider = McpToolProvider.builder()
                .mcpClients(mcpClient)
                .build();

        ToolProviderResult result = provider.provideTools(new ToolProviderRequest("memory-1", UserMessage.from("1 + 2")));

        assertThat(result.aiServiceTools())
                .singleElement()
                .satisfies(tool -> assertThat(tool.name()).isEqualTo("add_numbers"));
        assertThat(result.aiServiceTools().getFirst().toolExecutor()
                .execute(ToolExecutionRequest.builder()
                        .name("add_numbers")
                        .arguments("{\"a\":1,\"b\":2}")
                        .build(), "memory-1"))
                .isEqualTo("3");
    }

    private static ToolSpecification addNumbersTool() {
        return ToolSpecification.builder()
                .name("add_numbers")
                .description("Add two integer numbers and return the sum.")
                .parameters(JsonObjectSchema.builder()
                        .addIntegerProperty("a", "The first addend.")
                        .addIntegerProperty("b", "The second addend.")
                        .required("a", "b")
                        .build())
                .build();
    }

    private static boolean isAddNumbersRequest(ToolExecutionRequest request) {
        return request != null
                && "add_numbers".equals(request.name())
                && request.arguments().contains("\"a\":1")
                && request.arguments().contains("\"b\":2");
    }
}
