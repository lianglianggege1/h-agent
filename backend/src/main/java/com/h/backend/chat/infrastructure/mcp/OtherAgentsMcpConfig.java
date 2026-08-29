package com.h.backend.chat.infrastructure.mcp;

import com.h.agent.observability.AgentObservability;
import com.h.backend.observability.mcp.ObservingMcpHeadersSupplier;
import com.h.backend.observability.mcp.ObservingMcpToolExecutor;
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
    McpClient otherAgentsMcpClient(OtherAgentsMcpProperties properties, AgentObservability observability) {
        StreamableHttpMcpTransport.Builder transportBuilder = new StreamableHttpMcpTransport.Builder()
                .url(properties.getUrl());
        String token = properties.getToken();
        Supplier<Map<String, String>> authHeaders = null;
        if (token != null && !token.isBlank()) {
            // other-agents 的每个 MCP endpoint 都要求 Authorization: Bearer <token>
            authHeaders = () -> Map.of("Authorization", "Bearer " + token);
        }
        // 每次构造 POST 时动态注入 W3C Context（设计 14.1），Authorization 等基础 Header 一并合并
        transportBuilder.customHeaders(new ObservingMcpHeadersSupplier(observability, authHeaders));
        McpTransport transport = transportBuilder.build();
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .toolExecutionTimeout(Duration.ofSeconds(properties.getToolExecutionTimeoutSeconds()))
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "agents.mcp.other-agents", name = "enabled", havingValue = "true")
    McpToolProvider otherAgentsMcpToolProvider(McpClient otherAgentsMcpClient, AgentObservability observability) {
        return McpToolProvider.builder()
                .mcpClients(otherAgentsMcpClient)
                .toolWrapper(executor -> new ObservingMcpToolExecutor(observability, executor))
                .build();
    }
}
