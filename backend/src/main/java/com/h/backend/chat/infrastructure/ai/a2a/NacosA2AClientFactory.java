package com.h.backend.chat.infrastructure.ai.a2a;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.h.backend.chat.infrastructure.config.OtherAgentsA2AProperties;
import com.h.registry.NacosA2AConstants;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.internal.A2AClientBuilder;
import org.a2aproject.sdk.client.ClientBuilder;
import org.a2aproject.sdk.client.http.A2AHttpClientFactory;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(prefix = "agents.a2a.other-agents", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NacosA2AClientFactory {
    private final AiService aiService;
    private final OtherAgentsA2AProperties properties;

    public NacosA2AClientFactory(AiService aiService, OtherAgentsA2AProperties properties) {
        this.aiService = aiService;
        this.properties = properties;
    }

    public <T> A2AClientBuilder<T> builder(String agentName, Class<T> type) {
        // LangChain4j fetches the public Card over HTTP when building its typed proxy.
        // Only the bootstrap address and runtime routing come from the Nacos AI Registry.
        return AgenticServices.a2aBuilder(endpoint(agentName), type)
                .clientCustomizer((Consumer<ClientBuilder>) client -> client.withTransport(JSONRPCTransport.class,
                        new JSONRPCTransportConfigBuilder().httpClient(new NacosA2AHttpClient(
                                A2AHttpClientFactory.create(), () -> endpoint(agentName)))));
    }

    String endpoint(String agentName) {
        try {
            var card = aiService.getAgentCard(agentName, properties.getVersion(), "SERVICE");
            String url = card == null ? null : card.getUrl();
            // Native A2A 1.0 Cards can use supportedInterfaces without the legacy url field.
            if ((url == null || url.isBlank()) && card != null && card.getSupportedInterfaces() != null) {
                url = card.getSupportedInterfaces().stream()
                        .filter(i -> "JSONRPC".equals(i.getProtocolBinding()) || "JSONRPC".equals(i.getTransport()))
                        .map(com.alibaba.nacos.api.ai.model.a2a.AgentInterface::getUrl)
                        .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
            }
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("No available Nacos A2A endpoint for " + agentName);
            }
            URI uri = URI.create(url);
            if (NacosA2AConstants.UNRESOLVED_HOST.equals(uri.getHost())) {
                throw new IllegalStateException("No available Nacos A2A endpoint for " + agentName);
            }
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                throw new IllegalStateException("Invalid Nacos A2A endpoint for " + agentName);
            }
            return url;
        } catch (NacosException error) {
            throw new IllegalStateException("Cannot discover Nacos A2A agent " + agentName
                    + " version " + properties.getVersion(), error);
        }
    }
}
