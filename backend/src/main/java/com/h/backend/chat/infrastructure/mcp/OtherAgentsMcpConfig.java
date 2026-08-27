package com.h.backend.chat.infrastructure.mcp;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

@Configuration
@EnableConfigurationProperties(OtherAgentsMcpProperties.class)
public class OtherAgentsMcpConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "agents.mcp.other-agents", name = "enabled", havingValue = "true")
    McpClient otherAgentsMcpClient(OtherAgentsMcpProperties properties) {
        StreamableHttpMcpTransport.Builder transportBuilder = new StreamableHttpMcpTransport.Builder()
                .url(properties.getUrl());
        String token = properties.getToken();
        if (token != null && !token.isBlank()) {
            // other-agents 的每个 MCP endpoint 都要求 Authorization: Bearer <token>
            Supplier<Map<String, String>> authHeaders = () -> Map.of("Authorization", "Bearer " + token);
            transportBuilder.customHeaders(authHeaders);
        }
        McpTransport transport = transportBuilder.build();
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .toolExecutionTimeout(Duration.ofSeconds(properties.getToolExecutionTimeoutSeconds()))
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "agents.mcp.other-agents", name = "enabled", havingValue = "true")
    McpToolProvider otherAgentsMcpToolProvider(McpClient otherAgentsMcpClient) {
        return McpToolProvider.builder()
                .mcpClients(otherAgentsMcpClient)
                .build();
    }
}
