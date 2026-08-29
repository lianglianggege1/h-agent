package com.h.otheragents.mcp;

import com.h.agent.observability.AgentObservability;
import com.h.otheragents.observability.ObservedMcpToolSpecifications;
import com.h.otheragents.observability.ObservingMcpTransportContextExtractor;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 持有所有 MCP Endpoint 对应的 {@link McpAsyncServer} 与合并后的 {@link RouterFunction}。
 * 每个 endpoint 是一个独立的 MCP Server 暴露单元：独立路径、独立服务身份、独立工具集与认证凭证。
 */
public class McpEndpointServers implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpEndpointServers.class);

    private final List<McpAsyncServer> servers;
    private final RouterFunction<? extends ServerResponse> routerFunction;

    private McpEndpointServers(List<McpAsyncServer> servers,
                               RouterFunction<? extends ServerResponse> routerFunction) {
        this.servers = servers;
        this.routerFunction = routerFunction;
    }

    /**
     * 按配置构建全部 endpoint。工具组由代码驱动（endpointId -> toolObjects），配置只声明路径与凭证。
     * observability 提供 server-side Tool Observation 与 W3C 传播桥（可为 null 表示不观测）。
     */
    public static McpEndpointServers build(McpEndpointProperties properties, Map<String, List<Object>> toolGroups,
                                           AgentObservability observability) {
        List<McpAsyncServer> servers = new ArrayList<>();
        RouterFunction<? extends ServerResponse> merged = null;

        for (Map.Entry<String, McpEndpointProperties.Endpoint> entry : properties.getEndpoints().entrySet()) {
            String endpointId = entry.getKey();
            McpEndpointProperties.Endpoint endpoint = entry.getValue();

            List<Object> toolObjects = toolGroups.get(endpointId);
            if (toolObjects == null || toolObjects.isEmpty()) {
                throw new IllegalStateException("No tool group registered for MCP endpoint: " + endpointId);
            }

            WebFluxStreamableServerTransportProvider.Builder transportProviderBuilder =
                    WebFluxStreamableServerTransportProvider.builder()
                            .messageEndpoint(endpoint.getPath())
                            .securityValidator(new BearerTokenMcpSecurityValidator(endpoint.getToken()));
            if (observability != null) {
                transportProviderBuilder.contextExtractor(new ObservingMcpTransportContextExtractor());
            }
            WebFluxStreamableServerTransportProvider transportProvider = transportProviderBuilder.build();

            List<McpServerFeatures.AsyncToolSpecification> toolSpecifications =
                    observability == null
                            ? org.springframework.ai.mcp.McpToolUtils.toAsyncToolSpecifications(toToolCallbacks(toolObjects))
                            : ObservedMcpToolSpecifications.observing(observability, toToolCallbacks(toolObjects));

            McpAsyncServer server = McpServer.async(transportProvider)
                    .serverInfo(serverName(endpointId, endpoint), serverVersion(endpoint))
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(toolSpecifications)
                    .build();

            servers.add(server);

            RouterFunction<? extends ServerResponse> rf = transportProvider.getRouterFunction();
            merged = (merged == null) ? rf : merged.andOther(rf);

            log.info("Registered MCP endpoint '{}' at path '{}' with {} tool(s)",
                    endpointId, endpoint.getPath(), toolSpecifications.size());
        }

        if (servers.isEmpty()) {
            throw new IllegalStateException("No MCP endpoints configured under other-agents.mcp.endpoints");
        }
        return new McpEndpointServers(servers, merged);
    }

    private static ToolCallback[] toToolCallbacks(List<Object> toolObjects) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build()
                .getToolCallbacks();
    }

    private static String serverName(String endpointId, McpEndpointProperties.Endpoint endpoint) {
        return (endpoint.getName() != null && !endpoint.getName().isBlank())
                ? endpoint.getName()
                : endpointId + "-mcp";
    }

    private static String serverVersion(McpEndpointProperties.Endpoint endpoint) {
        return (endpoint.getVersion() != null && !endpoint.getVersion().isBlank())
                ? endpoint.getVersion()
                : "0.0.1";
    }

    public RouterFunction<? extends ServerResponse> routerFunction() {
        return routerFunction;
    }

    public List<McpAsyncServer> servers() {
        return List.copyOf(servers);
    }

    @Override
    public void close() {
        for (McpAsyncServer server : servers) {
            try {
                server.closeGracefully().block(Duration.ofSeconds(5));
            } catch (Exception e) {
                log.warn("Failed to close MCP server gracefully", e);
            }
        }
    }
}
