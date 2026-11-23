package com.h.otheragents.a2a.story;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.NoopAgentObservability;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.export.A2AAgentExportRegistry;
import com.h.otheragents.a2a.export.A2AAgentExports;
import com.h.otheragents.a2a.interfaces.web.A2AController;
import com.h.otheragents.a2a.server.A2AAgentServer;
import com.h.otheragents.a2a.server.A2AMessageMapper;
import com.h.otheragents.a2a.server.InMemoryA2ATaskStore;
import com.h.otheragents.a2a.server.LangChain4jAgentMethodInvoker;
import com.h.otheragents.observability.A2AObservabilityWebFilter;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StoryReviewAgentsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANUSCRIPT = "请核查稿件中的事实：月球基地有 20 名居民，建于 2040 年。"
            + "参考资料：登记册记载基地有 20 名居民，未注明建造年份。";
    private static final String EVIDENCE = "居民人数有登记册支持；2040 年建造的说法证据不足。";
    private final AiService registry = mock(AiService.class);
    private final ChatModel model = mock(ChatModel.class);
    private final StoryReviewAgents agents = new StoryReviewAgents(registry, NoopAgentObservability.getInstance(), model);

    @Test
    void sendsManuscriptAndParentContextToTheDiscoveredFactChecker() throws Exception {
        var card = new AgentCardDetailInfo();
        var properties = new OtherAgentsA2AProperties();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties.setPublicUrl("http://127.0.0.1:" + http.getAddress().getPort());
        card.setUrl(properties.agentUrl("fact-checker"));
        when(registry.getAgentCard("fact-checker", null, "SERVICE")).thenReturn(card);
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        when(model.chat(anyString())).thenAnswer(call -> {
            assertThat(MDC.get("traceId")).isEqualTo(traceId);
            String prompt = call.getArgument(0);
            if (prompt.startsWith("根据输入中提供的参考资料")) return EVIDENCE;
            assertThat(prompt).contains(EVIDENCE);
            return "审核结论：" + EVIDENCE;
        });
        var client = WebTestClient.bindToController(new A2AController(server(properties)))
                .webFilter(new A2AObservabilityWebFilter(NoopAgentObservability.getInstance())).build();
        var delegated = new AtomicReference<JsonNode>();
        http.createContext("/a2a/agents/fact-checker", exchange -> {
            try (exchange) {
                var request = JSON.readTree(exchange.getRequestBody());
                delegated.set(request);
                String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
                byte[] response = client.post().uri("/a2a/agents/fact-checker").contentType(MediaType.APPLICATION_JSON)
                        .header("traceparent", traceparent).bodyValue(request.toString()).exchange().expectStatus().isOk()
                        .expectHeader().valueEquals("X-Trace-Id", traceId)
                        .expectBody().returnResult().getResponseBody();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        http.start();
        try {
            byte[] review = client.post().uri("/a2a/agents/story-review").contentType(MediaType.APPLICATION_JSON)
                    .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01").bodyValue(reviewRequest().toString())
                    .exchange().expectStatus().isOk().expectHeader().valueEquals("X-Trace-Id", traceId)
                    .expectBody().returnResult().getResponseBody();
            var task = JSON.readTree(review).at("/result/task");
            assertThat(task.at("/status/state").asText()).isEqualTo("TASK_STATE_COMPLETED");
            assertThat(task.at("/artifacts/0/parts/0/text").asText()).isEqualTo("审核结论：" + EVIDENCE);
            assertThat(delegated.get().at("/params/message/contextId").asText()).isEqualTo(task.path("contextId").asText());
            assertThat(delegated.get().at("/params/message/referenceTaskIds/0").asText()).isEqualTo(task.path("id").asText());
            assertThat(delegated.get().at("/params/message/parts/0/text").asText()).isEqualTo(MANUSCRIPT);
        } finally {
            http.stop(0);
        }
    }

    @Test
    void failsTheReviewTaskWhenTheFactCheckerIsUnavailable() {
        var task = server(new OtherAgentsA2AProperties()).handle("story-review", reviewRequest()).at("/result/task");
        assertThat(task.at("/status/state").asText()).isEqualTo("TASK_STATE_FAILED");
        assertThat(task.at("/status/message/parts/0/text").asText()).contains("事实核查服务未完成");
        assertThat(task.has("artifacts")).isFalse();
        verifyNoInteractions(model);
    }

    private A2AAgentServer server(OtherAgentsA2AProperties properties) {
        var exports = new A2AAgentExportRegistry(A2AAgentExports.builder()
                .export("story-review", agents.reviewer(), StoryReviewAgents.Review.class, "assess")
                .export("fact-checker", agents.checker(), StoryReviewAgents.FactCheck.class, "check").build());
        return A2AAgentServer.create(properties, exports, new LangChain4jAgentMethodInvoker(),
                new A2AMessageMapper(), new InMemoryA2ATaskStore());
    }

    private JsonNode reviewRequest() {
        var request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", "review").put("method", "SendMessage");
        var message = request.putObject("params").putObject("message").put("messageId", "review").put("role", "ROLE_USER");
        var parts = message.putArray("parts");
        parts.addObject().put("text", MANUSCRIPT);
        parts.addObject().put("text", "style-review");
        return request;
    }
}
