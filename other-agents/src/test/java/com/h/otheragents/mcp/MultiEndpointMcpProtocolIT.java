package com.h.otheragents.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.internal.Json;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = MultiEndpointMcpProtocolIT.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.mcp.server.enabled=false",
                "other-agents.mcp.endpoints.test1.path=/test1/mcp",
                "other-agents.mcp.endpoints.test1.token=it-token-test1",
                "other-agents.mcp.endpoints.test2.path=/test2/mcp",
                "other-agents.mcp.endpoints.test2.token=it-token-test2",
                "spring.autoconfigure.exclude="
                        + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
        })
class MultiEndpointMcpProtocolIT {

    @LocalServerPort
    int port;

    @Test
    void test1EndpointExposesOnlyAddition() throws Exception {
        try (McpClient client = newClient("/test1/mcp", "it-token-test1")) {

            assertThat(client.listTools())
                    .extracting(ToolSpecification::name)
                    .contains("add_numbers")
                    .doesNotContain("multiply_numbers");

            ToolExecutionResult result = client.executeTool(ToolExecutionRequest.builder()
                    .name("add_numbers")
                    .arguments(Json.toJson(Map.of("a", 1, "b", 2)))
                    .build());

            assertThat(result.isError()).isFalse();
            assertThat(result.resultText()).isEqualTo("3");
        }
    }

    @Test
    void test2EndpointExposesOnlyMultiplication() throws Exception {
        try (McpClient client = newClient("/test2/mcp", "it-token-test2")) {

            assertThat(client.listTools())
                    .extracting(ToolSpecification::name)
                    .contains("multiply_numbers")
                    .doesNotContain("add_numbers");

            ToolExecutionResult result = client.executeTool(ToolExecutionRequest.builder()
                    .name("multiply_numbers")
                    .arguments(Json.toJson(Map.of("a", 3, "b", 4)))
                    .build());

            assertThat(result.isError()).isFalse();
            assertThat(result.resultText()).isEqualTo("12");
        }
    }

    @Test
    void rejectsMissingToken() {
        assertThatThrownBy(() -> newClient("/test1/mcp", null))
                .hasStackTraceContaining("401");
    }

    @Test
    void rejectsWrongToken() {
        assertThatThrownBy(() -> newClient("/test1/mcp", "wrong-token"))
                .hasStackTraceContaining("401");
    }

    @Test
    void rejectsCrossEndpointToken() {
        // test2 的 token 不能用来访问 test1
        assertThatThrownBy(() -> newClient("/test1/mcp", "it-token-test2"))
                .hasStackTraceContaining("401");
    }

    private McpClient newClient(String path, String token) {
        StreamableHttpMcpTransport.Builder transportBuilder = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:" + port + path);
        if (token != null) {
            Supplier<Map<String, String>> authHeaders = () -> Map.of("Authorization", "Bearer " + token);
            transportBuilder.customHeaders(authHeaders);
        }
        McpTransport transport = transportBuilder.build();
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .toolExecutionTimeout(Duration.ofSeconds(4))
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({AdditionMcpTool.class, MultiplicationMcpTool.class, McpEndpointConfig.class})
    static class TestApplication {
    }
}
