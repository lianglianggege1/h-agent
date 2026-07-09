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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = AdditionMcpProtocolIT.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.mcp.server.name=other-agents-mcp-test",
                "spring.ai.mcp.server.version=0.0.1",
                "spring.ai.mcp.server.type=async",
                "spring.ai.mcp.server.protocol=streamable",
                "spring.autoconfigure.exclude="
                        + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
        })
class AdditionMcpProtocolIT {

    @LocalServerPort
    int port;

    @Test
    void exposesAddNumbersThroughMcpProtocol() throws Exception {
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:" + port + "/mcp")
                .build();

        try (McpClient client = new DefaultMcpClient.Builder()
                .transport(transport)
                .toolExecutionTimeout(Duration.ofSeconds(4))
                .build()) {

            assertThat(client.listTools())
                    .extracting(ToolSpecification::name)
                    .contains("add_numbers");

            ToolExecutionResult result = client.executeTool(ToolExecutionRequest.builder()
                    .name("add_numbers")
                    .arguments(Json.toJson(Map.of("a", 1, "b", 2)))
                    .build());

            assertThat(result.isError()).isFalse();
            assertThat(result.resultText()).isEqualTo("3");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({AdditionMcpTool.class, McpToolConfig.class})
    static class TestApplication {
    }
}
