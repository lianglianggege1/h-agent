package com.h.otheragents.mcp;

import com.h.agent.observability.AgentObservability;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;

import java.util.List;
import java.util.Map;

/**
 * 多 MCP Endpoint 装配：关闭 Spring AI 单端点自动装配（{@code spring.ai.mcp.server.enabled=false}），
 * 改为按配置手工构建若干独立的 MCP Server 暴露单元。设计决策见 docs/adr/0004。
 *
 * <p>工具组与 endpoint 的映射是代码驱动的：配置只声明路径与凭证，哪些工具挂到哪个
 * endpoint 由下面 {@code mcpEndpointServers} 中的映射决定。
 */
@Configuration
@EnableConfigurationProperties(McpEndpointProperties.class)
public class McpEndpointConfig {

    @Bean(destroyMethod = "close")
    McpEndpointServers mcpEndpointServers(McpEndpointProperties properties,
                                          AdditionMcpTool additionMcpTool,
                                          MultiplicationMcpTool multiplicationMcpTool,
                                          AgentObservability observability) {
        Map<String, List<Object>> toolGroups = Map.of(
                "test1", List.of(additionMcpTool),
                "test2", List.of(multiplicationMcpTool));
        return McpEndpointServers.build(properties, toolGroups, observability);
    }

    @Bean
    RouterFunction<?> mcpEndpointsRouterFunction(McpEndpointServers mcpEndpointServers) {
        return mcpEndpointServers.routerFunction();
    }
}
