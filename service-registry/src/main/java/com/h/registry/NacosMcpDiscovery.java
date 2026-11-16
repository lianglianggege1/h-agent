package com.h.registry;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointInfo;
import java.net.URI;
import java.util.List;

/** Resolves a pinned definition on each new operation; never replays business requests. */
public final class NacosMcpDiscovery {
    private final AiService service;

    public NacosMcpDiscovery(AiService service) { this.service = service; }

    public String resolve(String name, String version, String preferredUrl) {
        if (name == null || name.isBlank() || version == null || version.isBlank()) {
            throw new IllegalArgumentException("MCP name and version are required");
        }
        try {
            var info = service.getMcpServer(name, version);
            if (info == null || !info.isEnabled() || !AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE.equals(info.getProtocol())
                    || info.getVersionDetail() == null || !version.equals(info.getVersionDetail().getVersion())) {
                throw new IllegalStateException("MCP definition unavailable or incompatible: " + name);
            }
            List<McpEndpointInfo> endpoints = info.getFrontendEndpoints();
            if (endpoints == null || endpoints.isEmpty()) endpoints = info.getBackendEndpoints();
            List<String> urls = endpoints == null ? List.of() : endpoints.stream()
                    .map(NacosMcpDiscovery::url).distinct().sorted().toList();
            if (urls.isEmpty()) throw new IllegalStateException("No available MCP endpoint: " + name);
            return urls.contains(preferredUrl) ? preferredUrl : urls.getFirst();
        } catch (com.alibaba.nacos.api.exception.NacosException error) {
            throw new IllegalStateException("Nacos MCP discovery failed: " + name, error);
        }
    }

    private static String url(McpEndpointInfo endpoint) {
        try {
            // Nacos 3.2.4 naming-backed endpoints omit protocol for plain HTTP.
            String scheme = endpoint.getProtocol();
            if (scheme == null || scheme.isBlank()) scheme = "http";
            String path = endpoint.getPath();
            if (!("http".equals(scheme) || "https".equals(scheme)) || endpoint.getAddress() == null
                    || endpoint.getAddress().isBlank() || endpoint.getPort() < 1 || endpoint.getPort() > 65535
                    || path == null || !path.startsWith("/") || path.startsWith("//")) {
                throw new IllegalArgumentException("Invalid MCP endpoint");
            }
            return new URI(scheme, null, endpoint.getAddress(), endpoint.getPort(), path, null, null).toASCIIString();
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("Invalid MCP endpoint", error);
        }
    }
}
