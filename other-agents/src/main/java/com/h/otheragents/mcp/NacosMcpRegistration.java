package com.h.otheragents.mcp;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.mcp.*;
import com.alibaba.nacos.api.ai.model.mcp.registry.ServerVersionDetail;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.registry.NacosRegistrationAddress;
import com.h.registry.NacosRegistryProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import java.util.*;

/** Definitions persist; only this process's runtime endpoints are removed on shutdown. */
@Component
@ConditionalOnProperty(prefix = "h-agent.registry", name = {"enabled", "mcp-enabled"}, havingValue = "true")
public final class NacosMcpRegistration implements ApplicationListener<WebServerInitializedEvent>, DisposableBean {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NacosMcpRegistration.class);
    private final AiService service;
    private final NacosRegistryProperties properties;
    private final List<McpEndpointServers> groups;
    private final ApplicationContext context;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, NacosRegistrationAddress> registered = new LinkedHashMap<>();

    public NacosMcpRegistration(AiService service, NacosRegistryProperties properties,
            List<McpEndpointServers> groups, ApplicationContext context) {
        this.service = service; this.properties = properties; this.groups = groups; this.context = context;
    }

    @Override
    public synchronized void onApplicationEvent(WebServerInitializedEvent event) {
        if (event.getApplicationContext() != context || !registered.isEmpty()) return;
        try {
            var address = NacosRegistrationAddress.resolve(properties, event.getWebServer().getPort());
            var endpoints = groups.stream().flatMap(group -> group.endpoints().stream()).toList();
            if (endpoints.stream().map(McpEndpointServers.Endpoint::name).distinct().count() != endpoints.size()) {
                throw new IllegalStateException("MCP server names must be unique");
            }
            for (var endpoint : endpoints) {
                var definition = new McpServerBasicInfo();
                definition.setName(endpoint.name());
                definition.setProtocol(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE);
                var version = new ServerVersionDetail(); version.setVersion(endpoint.version());
                definition.setVersionDetail(version);
                var remote = new McpServerRemoteServiceConfig(); remote.setExportPath(endpoint.path());
                definition.setRemoteServerConfig(remote);
                var tools = new McpToolSpecification();
                List<McpTool> toolList = new ArrayList<>();
                for (var callback : endpoint.tools()) {
                    var source = callback.getToolDefinition();
                    var tool = new McpTool(); tool.setName(source.name()); tool.setDescription(source.description());
                    tool.setInputSchema(json.readValue(source.inputSchema(), new TypeReference<Map<String,Object>>() {}));
                    toolList.add(tool);
                }
                tools.setTools(toolList);
                try {
                    service.releaseMcpServer(definition, tools);
                } catch (com.alibaba.nacos.api.exception.NacosException error) {
                    // 3.2.4 reports an existing version as 409; verify it rather than overwriting it.
                    if (error.getErrCode() != 409) throw error;
                }
                var existing = service.getMcpServer(endpoint.name(), endpoint.version());
                if (existing == null || !definition.getProtocol().equals(existing.getProtocol())
                        || existing.getRemoteServerConfig() == null
                        || !endpoint.path().equals(existing.getRemoteServerConfig().getExportPath())
                        || existing.getToolSpec() == null || !schemas(tools).equals(schemas(existing.getToolSpec()))) {
                    throw new IllegalStateException("MCP definition changed; publish a new version: " + endpoint.name());
                }
                // Record before RPC: the server may accept registration even if the response is lost.
                registered.put(endpoint.name(), address);
                service.registerMcpServerEndpoint(endpoint.name(), address.ip(), address.port(), endpoint.version());
                log.info("Registered MCP server {} version {} at {}:{}{}", endpoint.name(), endpoint.version(),
                        address.ip(), address.port(), endpoint.path());
            }
        } catch (Exception error) {
            destroy();
            throw new IllegalStateException("Nacos MCP registration failed; refusing to start", error);
        }
    }

    private Map<String, Map<String,Object>> schemas(McpToolSpecification specification) {
        Map<String, Map<String,Object>> result = new TreeMap<>();
        for (var tool : specification.getTools()) {
            if (result.put(tool.getName(), tool.getInputSchema()) != null) {
                throw new IllegalStateException("Duplicate MCP tool name");
            }
        }
        return result;
    }

    @Override
    public synchronized void destroy() {
        registered.forEach((name, address) -> {
            try { service.deregisterMcpServerEndpoint(name, address.ip(), address.port()); }
            catch (Exception error) { log.warn("Failed to deregister MCP endpoint {}", name, error); }
        });
        registered.clear();
    }
}
