package com.h.otheragents.a2a.registry;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.export.A2AAgentExportRegistry;
import com.h.otheragents.a2a.server.A2AAgentServer;
import com.h.registry.NacosRegistrationAddress;
import com.h.registry.NacosA2AConstants;
import com.h.registry.NacosRegistryProperties;
import org.a2aproject.sdk.spec.AgentInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Publishes definitions and connection-owned runtime endpoints after the HTTP port binds. */
@Component
@ConditionalOnProperty(prefix = "h-agent.registry", name = {"enabled", "a2a-enabled"}, havingValue = "true")
public class NacosA2ARegistration implements ApplicationListener<WebServerInitializedEvent>, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(NacosA2ARegistration.class);
    private final AiService aiService;
    private final NacosRegistryProperties registryProperties;
    private final OtherAgentsA2AProperties properties;
    private final A2AAgentExportRegistry exports;
    private final A2AAgentServer server;
    private final ApplicationContext context;
    private final Map<String, AgentEndpoint> endpoints = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public NacosA2ARegistration(AiService aiService, NacosRegistryProperties registryProperties,
            OtherAgentsA2AProperties properties, A2AAgentExportRegistry exports,
            A2AAgentServer server, ApplicationContext context) {
        this.aiService = aiService;
        this.registryProperties = registryProperties;
        this.properties = properties;
        this.exports = exports;
        this.server = server;
        this.context = context;
    }

    @Override
    public synchronized void onApplicationEvent(WebServerInitializedEvent event) {
        if (event.getApplicationContext() != context || !endpoints.isEmpty()) {
            return;
        }
        try {
            if (properties.getPublicUrl() == null || properties.getPublicUrl().isBlank()) {
                var address = NacosRegistrationAddress.resolve(registryProperties, event.getWebServer().getPort());
                properties.setPublicUrl(new URI("http", null, address.ip(), address.port(), null, null, null).toString());
            }
            for (var export : exports.list()) {
                AgentCard card = mapper.convertValue(server.card(export.id()), AgentCard.class);
                card.setProtocolVersion(AgentInterface.CURRENT_PROTOCOL_VERSION);
                URI url = URI.create(card.getUrl());
                if (url.getHost() == null || !("http".equals(url.getScheme()) || "https".equals(url.getScheme()))
                        || url.getQuery() != null || url.getFragment() != null || url.getUserInfo() != null) {
                    throw new IllegalArgumentException("A2A public-url must be an HTTP(S) base URL without credentials, query or fragment");
                }
                // Definitions are immutable per version and must not retain an instance address.
                // Nacos 3.2 returns this marker if there are no runtime endpoints to inject.
                String unresolved = "http://" + NacosA2AConstants.UNRESOLVED_HOST + "/a2a/agents/" + export.id();
                card.setUrl(unresolved);
                card.getSupportedInterfaces().forEach(i -> i.setUrl(unresolved));
                card.setAdditionalInterfaces(List.of());
                // Nacos 3.2 makes release of an existing version idempotent.
                aiService.releaseAgentCard(card, "SERVICE", true);
                AgentEndpoint endpoint = new AgentEndpoint();
                endpoint.setAddress(url.getHost());
                endpoint.setPort(url.getPort() < 0 ? ("https".equals(url.getScheme()) ? 443 : 80) : url.getPort());
                endpoint.setPath(url.getPath());
                endpoint.setTransport("JSONRPC");
                endpoint.setProtocolVersion(card.getProtocolVersion());
                endpoint.setVersion(card.getVersion());
                endpoint.setSupportTls("https".equals(url.getScheme()));
                // Retain before the RPC so teardown also cleans up partially accepted registrations.
                endpoints.put(export.id(), endpoint);
                aiService.registerAgentEndpoint(export.id(), endpoint);
                log.info("Registered A2A agent {} version {} at {}", export.id(), card.getVersion(), url);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Nacos A2A registration failed; refusing to start", error);
        }
    }

    @Override
    public synchronized void destroy() {
        endpoints.forEach((name, endpoint) -> {
            try {
                aiService.deregisterAgentEndpoint(name, endpoint);
            } catch (Exception error) {
                log.warn("Could not deregister A2A agent {}; Nacos will expire its endpoint", name, error);
            }
        });
        endpoints.clear();
    }
}
