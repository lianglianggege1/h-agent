package com.h.otheragents.a2a.registry;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint;
import com.alibaba.nacos.api.exception.NacosException;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.export.A2AAgentExportRegistry;
import com.h.otheragents.a2a.export.A2AAgentExports;
import com.h.otheragents.a2a.infrastructure.ai.Agents;
import com.h.otheragents.a2a.server.*;
import com.h.registry.NacosRegistryProperties;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerInitializedEvent;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NacosA2ARegistrationTest {
    private final AiService ai = mock(AiService.class);
    private final OtherAgentsA2AProperties properties = new OtherAgentsA2AProperties();
    private final ReactiveWebServerApplicationContext context = mock(ReactiveWebServerApplicationContext.class);
    private final WebServer webServer = mock(WebServer.class);

    private NacosA2ARegistration registration(int advertisedPort) {
        Agents.CreativeWriter writer = topic -> "draft:" + topic;
        var exports = new A2AAgentExportRegistry(A2AAgentExports.builder()
                .export("creative-writer", writer, Agents.CreativeWriter.class, "generateStory").build());
        var server = A2AAgentServer.create(properties, exports, new LangChain4jAgentMethodInvoker(),
                new A2AMessageMapper(), new InMemoryA2ATaskStore());
        var registry = new NacosRegistryProperties("localhost:8848", "test", "DEFAULT_GROUP", "DEFAULT",
                "", "", "192.0.2.10", advertisedPort, 5000, Map.of());
        when(webServer.getPort()).thenReturn(18982);
        return new NacosA2ARegistration(ai, registry, properties, exports, server, context);
    }

    @Test
    void publishesCompatibleCardAndBoundEndpointAndCleansUp() throws Exception {
        var registration = registration(0);
        var event = new ReactiveWebServerInitializedEvent(webServer, context);
        registration.onApplicationEvent(event);
        registration.onApplicationEvent(event);
        var cards = ArgumentCaptor.forClass(AgentCard.class);
        var endpoints = ArgumentCaptor.forClass(AgentEndpoint.class);
        verify(ai).releaseAgentCard(cards.capture(), eq("SERVICE"), eq(true));
        verify(ai).registerAgentEndpoint(eq("creative-writer"), endpoints.capture());
        AgentCard card = cards.getValue();
        assertEquals("creative-writer", card.getName());
        assertEquals("0.1.0", card.getVersion());
        assertEquals(AgentInterface.CURRENT_PROTOCOL_VERSION, card.getProtocolVersion());
        assertEquals("http://unregistered.invalid/a2a/agents/creative-writer", card.getUrl());
        assertEquals(card.getUrl(), card.getSupportedInterfaces().getFirst().getUrl());
        assertEquals("JSONRPC", card.getSupportedInterfaces().getFirst().getProtocolBinding());
        assertEquals("creative-writer", card.getSkills().getFirst().getId());
        assertFalse(card.getCapabilities().getStreaming());
        AgentEndpoint endpoint = endpoints.getValue();
        assertEquals(18982, endpoint.getPort());
        assertEquals("192.0.2.10", endpoint.getAddress());
        assertEquals("/a2a/agents/creative-writer", endpoint.getPath());
        assertEquals("JSONRPC", endpoint.getTransport());
        assertFalse(endpoint.isSupportTls());
        registration.destroy();
        verify(ai).deregisterAgentEndpoint("creative-writer", endpoint);
    }

    @Test
    void supportsAdvertisedPortAndIgnoresManagementContext() throws Exception {
        var registration = registration(28082);
        registration.onApplicationEvent(new ReactiveWebServerInitializedEvent(webServer,
                mock(ReactiveWebServerApplicationContext.class)));
        verifyNoInteractions(ai);
        registration.onApplicationEvent(new ReactiveWebServerInitializedEvent(webServer, context));
        var endpoints = ArgumentCaptor.forClass(AgentEndpoint.class);
        verify(ai).registerAgentEndpoint(eq("creative-writer"), endpoints.capture());
        assertEquals(28082, endpoints.getValue().getPort());
    }

    @Test
    void explicitHttpsPublicUrlIncludesProxyPrefixAndVersion() throws Exception {
        properties.setPublicUrl("https://agents.example.com/proxy/");
        properties.setVersion("0.2.0");
        var registration = registration(0);
        registration.onApplicationEvent(new ReactiveWebServerInitializedEvent(webServer, context));
        var endpoints = ArgumentCaptor.forClass(AgentEndpoint.class);
        verify(ai).registerAgentEndpoint(eq("creative-writer"), endpoints.capture());
        assertEquals("agents.example.com", endpoints.getValue().getAddress());
        assertEquals(443, endpoints.getValue().getPort());
        assertTrue(endpoints.getValue().isSupportTls());
        assertEquals("/proxy/a2a/agents/creative-writer", endpoints.getValue().getPath());
        assertEquals("0.2.0", endpoints.getValue().getVersion());
    }

    @Test
    void registrationFailureAbortsAndCleansUpPossiblyAcceptedEndpoint() throws Exception {
        doThrow(new NacosException(500, "unavailable")).when(ai)
                .registerAgentEndpoint(eq("creative-writer"), any(AgentEndpoint.class));
        var registration = registration(0);
        assertThrows(IllegalStateException.class, () -> registration.onApplicationEvent(
                new ReactiveWebServerInitializedEvent(webServer, context)));
        registration.destroy();
        verify(ai).deregisterAgentEndpoint(eq("creative-writer"), any(AgentEndpoint.class));
    }
}
