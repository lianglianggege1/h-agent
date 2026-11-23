package com.h.backend.chat.infrastructure.ai.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.AgentObservability;
import com.h.backend.chat.domain.agent.AgentStepEventBridge;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.backend.chat.domain.agent.AgentTopologyMapper;
import com.h.backend.chat.interfaces.web.AgentController;
import com.h.capabilities.memory.domain.MemoryInvocationContext;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class RemoteStoryAssistantTest {
    @Test
    void topologyEndpointMapsTheSameWorkflowThatRoutesChat() {
        var assistant = new RemoteStoryAssistant(() -> { throw new AssertionError("unexpected network discovery"); },
                Keys.hmacShaKeyFor(new byte[32]), new SessionReferences(), ignored -> false, null, null,
                mock(AgentStepEventBridge.class), mock(AgentObservability.class),
                request -> new StoryIntentRouter.Decision(StoryIntentRouter.Intent.REVISION, false));
        var definition = new AgentDefinition("a2a-remote-story", "A2A 远端故事助手", "跨服务协作", List.of("A2A"),
                "远端故事创作", assistant, AgentRuntimeType.AGENTIC_SYNC, true);
        var controller = new AgentController(new AgentRegistry(List.of(definition)), new AgentTopologyMapper());

        var topology = controller.topology(definition.agentId()).data();
        assertEquals("SEQUENCE", topology.root().topology());
        assertEquals(assistant.executionGraph().subagents().stream().map(node -> node.name()).toList(),
                topology.root().children().stream().map(node -> node.name()).toList());
        var delegation = topology.root().children().get(1);
        assertEquals("ROUTER", delegation.topology());
        assertEquals(List.of("了解可用技能", "请求插画", "请求评分", "请求审稿或事实核查", "修改已完成故事",
                        "明确请求长篇创作", "请求文档处理", "发起创作或补充信息"),
                delegation.children().stream().map(node -> node.condition()).toList());
        var run = new MemoryInvocationContext(24L, "a2a-remote-story", "session-1", 33L, "session-1", null);
        assertEquals("请先完成一篇故事，再发起修订。",
                assistant.chat("memory", "请把刚才的故事结尾改得更温暖一点", run.toInvocationParameters()).result());
    }

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
            var writer = new RemoteStoryAssistant(() -> endpoint, key, references, ignored -> false, null, null, events, observability, RemoteStoryAssistantTest::storyIntent);

            var first = new MemoryInvocationContext(24L, "a2a-remote-story", "session-1", 33L, "session-1", null);
            assertEquals("故事风格是什么？", writer.chat("memory", "写个月球故事", first.toInvocationParameters()).result());
            assertEquals("24", subject.get());
            assertEquals("chat-run-33", request.get().path("messageId").asText());
            assertEquals(java.util.List.of("a2a_story_waiting"), transitions);

            var reopened = new RemoteStoryAssistant(() -> endpoint, key, references, ignored -> false, null, null, events, observability, RemoteStoryAssistantTest::storyIntent);
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
                    com.h.agent.observability.NoopAgentObservability.getInstance(), RemoteStoryAssistantTest::storyIntent);
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

    @Test
    void dispatchesIllustrationAndReviewFromNaturalLanguageWithoutACommand() {
        var illustrator = mock(RemoteStoryIllustration.class);
        var reviewer = mock(NacosStoryReview.class);
        var invocation = new MemoryInvocationContext(24L, "a2a-remote-story", "session-1", 33L, "session-1", null);
        String imageRequest = "请画一张机器人在月球基地看日出的插画";
        String reviewRequest = "帮我查这段稿子的事实：登记册上写着基地有 20 人";
        when(illustrator.run(any(), any(), any())).thenReturn("插画完成");
        when(reviewer.run(any(), any())).thenReturn("核查完成");
        var assistant = new RemoteStoryAssistant(() -> { throw new AssertionError("unexpected story task"); },
                Keys.hmacShaKeyFor(new byte[32]), new SessionReferences(), ignored -> false, reviewer, illustrator,
                mock(AgentStepEventBridge.class), mock(AgentObservability.class),
                request -> new StoryIntentRouter.Decision(request.contains(imageRequest)
                        ? StoryIntentRouter.Intent.ILLUSTRATION : StoryIntentRouter.Intent.REVIEW, false));

        assertEquals("插画完成", assistant.chat("memory", imageRequest, invocation.toInvocationParameters()).result());
        assertEquals("核查完成", assistant.chat("memory", reviewRequest, invocation.toInvocationParameters()).result());
        verify(reviewer).run(reviewRequest, "style-review");
    }

    private static StoryIntentRouter.Decision storyIntent(String request) {
        return new StoryIntentRouter.Decision(StoryIntentRouter.Intent.STORY, false);
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
