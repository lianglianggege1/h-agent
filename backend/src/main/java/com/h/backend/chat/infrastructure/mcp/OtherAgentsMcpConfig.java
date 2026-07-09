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

@Configuration
@EnableConfigurationProperties(OtherAgentsMcpProperties.class)
public class OtherAgentsMcpConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "agents.mcp.other-agents", name = "enabled", havingValue = "true")
    McpClient otherAgentsMcpClient(OtherAgentsMcpProperties properties) {
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url(properties.getUrl())
                .build();
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
