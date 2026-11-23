package com.h.backend.chat.infrastructure.ai.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.AgentObservability;
import com.h.backend.chat.domain.agent.AgentStepEventBridge;
import com.h.capabilities.memory.domain.MemoryInvocationContext;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteStoryAssistantTest {
    @Test
    void chatSendsTheSameRemoteTaskAfterReopeningTheAssistant() throws Exception {
        var json = new ObjectMapper();
        var key = Keys.hmacShaKeyFor(new byte[32]);
        var parser = Jwts.parser().verifyWith(key).requireIssuer("h-agent")
                .requireAudience("story-workflow").build();
        var request = new AtomicReference<JsonNode>();
        var subject = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                var caller = exchange.getRequestHeaders().getFirst("Authorization");
                subject.set(parser.parseSignedClaims(caller.substring(7)).getPayload().getSubject());
                var rpc = json.readTree(exchange.getRequestBody());
                var message = rpc.at("/params/message");
                request.set(message);
                var response = json.createObjectNode().put("jsonrpc", "2.0");
                response.set("id", rpc.get("id"));
                var task = response.putObject("result").putObject("task").put("id", "task-1").put("contextId", "context-1");
                if (!message.has("taskId")) {
                    task.putObject("status").put("state", "TASK_STATE_INPUT_REQUIRED")
                            .putObject("message").putArray("parts").addObject().put("text", "故事风格是什么？");
                } else {
                    task.putObject("status").put("state", "TASK_STATE_COMPLETED");
                    task.putArray("artifacts").addObject().putArray("parts").addObject().put("text", "月球故事");
                }
                byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var observability = mock(AgentObservability.class);
            when(observability.span(any(), nullable(com.h.agent.observability.lifecycle.ObservationContext.class)))
                    .thenReturn(mock(AgentObservation.class));
            var events = new AgentStepEventBridge();
            var transitions = new ArrayList<String>();
            events.registerExecution("memory", event -> transitions.add(event.type()), () -> {});
            var references = new SessionReferences();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var writer = new RemoteStoryAssistant(() -> endpoint, key, references, ignored -> false, null, null, events, observability);

            var first = new MemoryInvocationContext(24L, "a2a-remote-story", "session-1", 33L, "session-1", null);
            assertEquals("故事风格是什么？", writer.chat("memory", "写个月球故事", first.toInvocationParameters()).result());
            assertEquals("24", subject.get());
            assertEquals("chat-run-33", request.get().path("messageId").asText());
            assertEquals(java.util.List.of("a2a_story_waiting"), transitions);

            var reopened = new RemoteStoryAssistant(() -> endpoint, key, references, ignored -> false, null, null, events, observability);
            var followUp = new MemoryInvocationContext(24L, "a2a-remote-story", "session-1", 34L, "session-1", null);
            assertEquals("月球故事", reopened.chat("memory", "科幻", followUp.toInvocationParameters()).result());
            assertEquals("task-1", request.get().path("taskId").asText());
            assertEquals("context-1", request.get().path("contextId").asText());
            assertEquals("chat-run-34", request.get().path("messageId").asText());
            assertNull(references.reference);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resumesAnInterruptedSseStreamFromTheLatestSnapshotWithoutRepeatingText() throws Exception {
        var json = new ObjectMapper();
        var seen = new ArrayList<String>();
        var key = Keys.hmacShaKeyFor(new byte[32]);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                var request = json.readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                seen.add(method);
                String snapshot = """
                        {"id":"task","contextId":"context","status":{"state":"TASK_STATE_WORKING"},
                         "artifacts":[{"parts":[{"text":"第一段第二段"}]}]}
                        """.strip().replace("\n", "");
                String body;
                if ("GetTask".equals(method)) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    body = "{\"result\":" + snapshot + "}";
                } else {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    if ("SendStreamingMessage".equals(method)) {
                        body = "data: {\"result\":{\"task\":"
                                + snapshot.replace("第一段第二段", "第一段") + "}}\n\n";
                    } else {
                        body = "data: {\"result\":{\"task\":" + snapshot + "}}\n\n"
                                + "data: {\"result\":{\"artifactUpdate\":{\"append\":true,\"artifact\":"
                                + "{\"parts\":[{\"text\":\"第三段\"}]}}}}\n\n"
                                + "data: {\"result\":{\"statusUpdate\":{\"status\":"
                                + "{\"state\":\"TASK_STATE_COMPLETED\"}}}}\n\n";
                    }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var assistant = new RemoteStoryAssistant(() -> endpoint, key, new SessionReferences(),
                    ignored -> false, null, null, new AgentStepEventBridge(),
                    com.h.agent.observability.NoopAgentObservability.getInstance());
            var invocation = new MemoryInvocationContext(24L, "a2a-remote-story", "session-1", 33L, "session-1", null);
            var chunks = new ArrayList<String>();
            String result = assistant.chatStreaming("memory", "写故事", invocation.toInvocationParameters(), chunks::add).result();
            assertEquals("第一段第二段第三段", result);
            assertEquals(java.util.List.of("第一段", "第二段", "第三段"), chunks);
            assertEquals(java.util.List.of("SendStreamingMessage", "GetTask", "SubscribeToTask"), seen);
        } finally {
            server.stop(0);
        }
    }

    private static final class SessionReferences implements RemoteStoryAssistant.References {
        private RemoteStoryAssistant.Reference reference;

        @Override
        public Optional<RemoteStoryAssistant.Reference> get(long owner, String session) {
            assertEquals(24, owner);
            assertEquals("session-1", session);
            return Optional.ofNullable(reference);
        }

        @Override
        public void set(long owner, String session, RemoteStoryAssistant.Reference reference) {
            this.reference = reference;
        }

        @Override
        public void remove(long owner, String session) {
            reference = null;
        }
    }
}
