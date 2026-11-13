package com.h.otheragents.a2a.registry;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.export.A2AAgentExportRegistry;
import com.h.otheragents.a2a.export.A2AAgentExports;
import com.h.otheragents.a2a.infrastructure.ai.Agents;
import com.h.otheragents.a2a.interfaces.web.A2AController;
import com.h.otheragents.a2a.server.*;
import com.h.registry.NacosRegistryProperties;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerInitializedEvent;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Run only against a disposable Nacos 3.2.4; publishes persistent Card definitions. */
@EnabledIfEnvironmentVariable(named = "NACOS_A2A_TEST_ADDR", matches = ".+")
class NacosA2ARegistryIntegrationTest {
    @Test
    void publishesThreeAgentsDiscoversAndCallsThemThenDeregisters() throws Exception {
        Properties client = new Properties();
        client.setProperty(PropertyKeyConst.SERVER_ADDR, System.getenv("NACOS_A2A_TEST_ADDR"));
        client.setProperty(PropertyKeyConst.NAMESPACE, "public");
        AiService publisher = AiFactory.createAiService(client);
        AiService consumer = AiFactory.createAiService(client);
        var http = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        NacosA2ARegistration registration = null;
        try {
            var properties = new OtherAgentsA2AProperties();
            properties.setVersion("0.1.0-test-" + java.util.UUID.randomUUID());
            Agents.CreativeWriter writer = topic -> "draft:" + topic;
            Agents.AudienceEditor audience = (story, target) -> story + ":" + target;
            Agents.StyleEditor style = (story, target) -> story + ":" + target;
            var exports = new A2AAgentExportRegistry(A2AAgentExports.builder()
                    .export("creative-writer", writer, Agents.CreativeWriter.class, "generateStory")
                    .export("audience-editor", audience, Agents.AudienceEditor.class, "editStory")
                    .export("style-editor", style, Agents.StyleEditor.class, "editStory").build());
            var server = A2AAgentServer.create(properties, exports, new LangChain4jAgentMethodInvoker(),
                    new A2AMessageMapper(), new InMemoryA2ATaskStore());
            var controller = new A2AController(server);
            http.createContext("/a2a/agents/", exchange -> {
                String name = exchange.getRequestURI().getPath().substring("/a2a/agents/".length()).split("/")[0];
                String body = "GET".equals(exchange.getRequestMethod()) ? controller.agentCard(name)
                        : controller.jsonRpc(name, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            http.start();
            var registry = new NacosRegistryProperties(System.getenv("NACOS_A2A_TEST_ADDR"), "public",
                    "DEFAULT_GROUP", "DEFAULT", "", "", "127.0.0.1", 0, 5000, Map.of());
            var context = mock(ReactiveWebServerApplicationContext.class);
            var webServer = mock(WebServer.class);
            when(webServer.getPort()).thenReturn(http.getAddress().getPort());
            registration = new NacosA2ARegistration(publisher, registry, properties, exports, server, context);
            registration.onApplicationEvent(new ReactiveWebServerInitializedEvent(webServer, context));
            // A second process can publish the same immutable definition on restart.
            publisher.releaseAgentCard(new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).convertValue(
                    server.card("creative-writer"), com.alibaba.nacos.api.ai.model.a2a.AgentCard.class), "SERVICE", true);
            for (var export : exports.list()) {
                AgentCardDetailInfo card = consumer.getAgentCard(export.id(), properties.getVersion(), "SERVICE");
                assertEquals(properties.agentUrl(export.id()), card.getUrl());
                assertEquals("SERVICE", card.getRegistrationType());
                assertEquals(properties.agentUrl(export.id()), card.getSupportedInterfaces().getFirst().getUrl());
            }
            var writerClient = AgenticServices.a2aBuilder(
                    consumer.getAgentCard("creative-writer", properties.getVersion(), "SERVICE").getUrl(), Writer.class).build();
            assertEquals("draft:moon", writerClient.write("moon"));
            var audienceClient = AgenticServices.a2aBuilder(
                    consumer.getAgentCard("audience-editor", properties.getVersion(), "SERVICE").getUrl(), Editor.class).build();
            assertEquals("draft:children", audienceClient.edit("draft", "children"));
            var styleClient = AgenticServices.a2aBuilder(
                    consumer.getAgentCard("style-editor", properties.getVersion(), "SERVICE").getUrl(), Editor.class).build();
            assertEquals("draft:fantasy", styleClient.edit("draft", "fantasy"));
            registration.destroy();
            registration = null;
            String offlineHost;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            do {
                var offline = consumer.getAgentCard("creative-writer", properties.getVersion(), "SERVICE");
                offlineHost = java.net.URI.create(offline.getUrl()).getHost();
                if ("unregistered.invalid".equals(offlineHost)) { break; }
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            assertEquals("unregistered.invalid", offlineHost);
        } finally {
            if (registration != null) { registration.destroy(); }
            http.stop(0);
            consumer.shutdown();
            publisher.shutdown();
        }
    }

    public interface Writer {
        @Agent(outputKey = "story")
        String write(@V("topic") String topic);
    }

    public interface Editor {
        @Agent(outputKey = "story")
        String edit(@V("story") String story, @V("target") String target);
    }
}
