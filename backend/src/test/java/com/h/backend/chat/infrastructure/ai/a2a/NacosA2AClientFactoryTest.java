package com.h.backend.chat.infrastructure.ai.a2a;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.exception.NacosException;
import com.h.backend.chat.infrastructure.config.OtherAgentsA2AProperties;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NacosA2AClientFactoryTest {
    private final AiService ai = mock(AiService.class);
    private final NacosA2AClientFactory factory = new NacosA2AClientFactory(ai, new OtherAgentsA2AProperties());

    @Test
    void discoversPinnedVersionUsingServiceRegistrationAndRejectsMissingEndpoint() throws Exception {
        var card = new AgentCardDetailInfo();
        card.setUrl("http://192.0.2.10:8082/a2a/agents/creative-writer");
        when(ai.getAgentCard("creative-writer", "0.1.0", "SERVICE")).thenReturn(card);
        assertEquals(card.getUrl(), factory.endpoint("creative-writer"));
        card.setUrl("http://unregistered.invalid/a2a/agents/creative-writer");
        assertThrows(IllegalStateException.class, () -> factory.endpoint("creative-writer"));
        card.setUrl("");
        assertThrows(IllegalStateException.class, () -> factory.endpoint("creative-writer"));
        when(ai.getAgentCard("creative-writer", "0.1.0", "SERVICE"))
                .thenThrow(new NacosException(403, "denied"));
        assertInstanceOf(NacosException.class,
                assertThrows(IllegalStateException.class, () -> factory.endpoint("creative-writer")).getCause());
    }

    @Test
    void eachSendUsesCurrentEndpointAndPreservesHeadersBodyAndNoRetries() throws Exception {
        var delegate = mock(A2AHttpClient.class);
        var post = mock(A2AHttpClient.PostBuilder.class, RETURNS_SELF);
        var response = mock(A2AHttpResponse.class);
        when(delegate.createPost()).thenReturn(post);
        when(post.post()).thenReturn(response);
        var url = new AtomicReference<>("http://first/a2a/agents/writer");
        var client = new NacosA2AHttpClient(delegate, url::get);
        assertSame(response, client.createPost().url("http://stale")
                .addHeader("traceparent", "trace").body("message").post());
        verify(post).url("http://first/a2a/agents/writer");
        verify(post).addHeader("traceparent", "trace");
        verify(post).body("message");
        url.set("http://second/a2a/agents/writer");
        client.createPost().url("http://stale").body("second").post();
        verify(post).url("http://second/a2a/agents/writer");
        when(post.post()).thenThrow(new java.io.IOException("connection lost"));
        assertThrows(java.io.IOException.class, () -> client.createPost().post());
        verify(post, times(3)).post();
    }

    @Test
    void typedLangChain4jClientFollowsNacosAddressChangesOverRealHttp() throws Exception {
        try (var first = new AgentHttpServer("first"); var second = new AgentHttpServer("second")) {
            var currentUrl = new AtomicReference<>(first.url());
            when(ai.getAgentCard("creative-writer", "0.1.0", "SERVICE")).thenAnswer(invocation -> {
                var card = new AgentCardDetailInfo();
                card.setUrl(currentUrl.get());
                return card;
            });
            var writer = factory.builder("creative-writer", A2ARemoteAgents.CreativeWriter.class)
                    .outputKey("story").build();
            assertEquals("first", writer.generateStory("moon", "context-1", null));
            assertEquals("context-1", first.message.get().path("params").path("message").path("contextId").asText());
            assertEquals("moon", first.message.get().path("params").path("message").path("parts").get(0).path("text").asText());
            currentUrl.set(second.url());
            first.close();
            assertEquals("second", writer.generateStory("ocean", "context-1", null));
            verify(ai, times(3)).getAgentCard("creative-writer", "0.1.0", "SERVICE");
        }
    }

    private static class AgentHttpServer implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        private final AtomicReference<com.fasterxml.jackson.databind.JsonNode> message = new AtomicReference<>();
        private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        AgentHttpServer(String response) throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/a2a/agents/creative-writer", exchange -> {
                String body;
                if ("GET".equals(exchange.getRequestMethod())) {
                    // Deliberately stale Card address: runtime routing must use Nacos instead.
                    body = """
                            {"name":"creative-writer","version":"0.1.0","description":"writer",
                             "url":"http://127.0.0.1:1/stale",
                             "capabilities":{"streaming":false,"pushNotifications":false},
                             "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],
                             "skills":[],"supportedInterfaces":[{"protocolBinding":"JSONRPC",
                             "url":"http://127.0.0.1:1/stale","protocolVersion":"1.0"}]}
                            """;
                } else {
                    var request = mapper.readTree(exchange.getRequestBody());
                    message.set(request);
                    var result = mapper.createObjectNode();
                    result.put("jsonrpc", "2.0");
                    result.set("id", request.get("id"));
                    var task = result.putObject("result").putObject("task");
                    task.put("id", "task-1");
                    task.put("contextId", "context-1");
                    task.putObject("status").put("state", "TASK_STATE_COMPLETED");
                    var artifact = task.putArray("artifacts").addObject();
                    artifact.put("artifactId", "artifact-1");
                    artifact.putArray("parts").addObject().put("text", response);
                    body = mapper.writeValueAsString(result);
                }
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/a2a/agents/creative-writer"; }

        @Override
        public void close() { server.stop(0); }
    }
}
