package com.h.otheragents.order;

import com.h.agent.observability.AgentObservability;
import com.h.otheragents.mcp.McpEndpointProperties;
import com.h.otheragents.mcp.McpEndpointServers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import java.util.List;
import java.util.Map;

@Configuration
public class OrderDemoMcpConfig {
    @Bean(destroyMethod = "close")
    public McpEndpointServers orderDemoMcpServer(OrderDemoMcpTools tools, AgentObservability observability,
            @Value("${order-demo.mcp.path:/orders/mcp}") String path,
            @Value("${order-demo.mcp.token:${ORDER_MCP_TOKEN:dev-token-orders}}") String token) {
        McpEndpointProperties properties = new McpEndpointProperties();
        McpEndpointProperties.Endpoint endpoint = new McpEndpointProperties.Endpoint();
        endpoint.setPath(path); endpoint.setToken(token);
        properties.setEndpoints(Map.of("orders", endpoint));
        return McpEndpointServers.build(properties, Map.of("orders", List.of(tools)), observability);
    }

    @Bean
    public RouterFunction<?> orderDemoMcpRouter(
            @org.springframework.beans.factory.annotation.Qualifier("orderDemoMcpServer") McpEndpointServers server) {
        return server.routerFunction();
    }
}
