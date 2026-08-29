package com.h.otheragents.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservabilityTesting;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.EnvFileLoader;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.langchain4j.ObservingAgentListener;
import com.h.agent.observability.langchain4j.ObservingChatModel;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.infrastructure.ai.Agents;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test of A2A W3C trace-context propagation against a real Langfuse
 * instance (LANGFUSE_* from environment or repository root .env) and the real running
 * other-agents HTTP server (design 19.4). The client side replays exactly what
 * {@code ObservingA2AHttpClient} does in the backend process: a CLIENT
 * {@code remote_call} span whose W3C headers are carried by the real HTTP request, not
 * by the JSON-RPC body. The server side is the production {@code A2AObservabilityWebFilter}
 * plus the production agent wiring, so the ingested Langfuse trace must form
 *
 * <pre>
 * remote_call a2a.message/send (client, root)
 *   -> remote_call a2a.server
 *     -> agent.创意写作者
 *       -> gen_ai.* (canned model)
 * </pre>
 *
 * It also asserts the A2A task/context identity stays independent from the trace
 * identity. Skips automatically when Langfuse is not configured. When Langfuse is only
 * reachable through the local proxy, run with
 * {@code -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9090 -Dhttp.nonProxyHosts="localhost|127.0.0.1"}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
        }
)
class RealA2ALangfuseSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLIENT_SPAN = "remote_call a2a.message/send";
    private static final String SERVER_SPAN = "remote_call a2a.server";

    @LocalServerPort
    private int port;

    @Autowired
    private AgentObservability observability;

    private String baseUrl;
    private String publicKey;
    private String secretKey;

    @BeforeAll
    void requireConfiguration() {
        Map<String, String> fileValues = EnvFileLoader.load();
        baseUrl = EnvFileLoader.resolve(fileValues, "LANGFUSE_BASE_URL");
        publicKey = EnvFileLoader.resolve(fileValues, "LANGFUSE_PUBLIC_KEY");
        secretKey = EnvFileLoader.resolve(fileValues, "LANGFUSE_SECRET_KEY");
        Assumptions.assumeTrue(baseUrl != null && publicKey != null && secretKey != null,
                "LANGFUSE_BASE_URL/PUBLIC_KEY/SECRET_KEY not configured; skipping A2A smoke test");
    }

    @TestConfiguration
    static class CannedAgentConfig implements ApplicationListener<WebServerInitializedEvent> {

        @Autowired
        private OtherAgentsA2AProperties properties;

        @Autowired
        private AgentObservability observability;

        @Override
        public void onApplicationEvent(WebServerInitializedEvent event) {
            properties.setPublicUrl("http://localhost:" + event.getWebServer().getPort());
        }

        @Bean
        @Primary
        Agents.CreativeWriter creativeWriter() {
            return AgenticServices.agentBuilder(Agents.CreativeWriter.class)
                    .chatModel(new ObservingChatModel(cannedChatModel(), observability, "anthropic"))
                    .listener(new ObservingAgentListener(observability))
                    .outputKey("story")
                    .build();
        }
    }

    @Test
    void a2aMessageSendFormsCrossProcessTraceTreeInRealLangfuse() throws Exception {
        AgentObservation clientCall = observability.span(
                ObservationSpec.of(CLIENT_SPAN, HObsKind.REMOTE_CALL, "a2a-client",
                        Map.of("url.full", "http://localhost:" + port + "/a2a/agents/creative-writer")),
                observability.currentContext());

        Map<String, String> w3cHeaders = new HashMap<>();
        observability.inject(clientCall.context(), w3cHeaders);
        assertFalse(w3cHeaders.isEmpty(), "W3C propagator must produce trace headers");

        String requestBody = """
                {"jsonrpc":"2.0","id":"rpc-smoke-1","method":"message/send","params":{"message":{
                  "messageId":"msg-smoke-1","role":"user",
                  "parts":[{"kind":"text","text":"月球救援"}]}}}
                """;
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/a2a/agents/creative-writer"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        w3cHeaders.forEach(request::header);

        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
        clientCall.succeed();
        AgentObservabilityTesting.flush(observability);

        assertEquals(200, response.statusCode());
        JsonNode result = JSON.readTree(response.body());
        assertEquals("rpc-smoke-1", result.path("id").asText());
        JsonNode task = result.path("result").path("task");
        assertEquals("TASK_STATE_COMPLETED", task.path("status").path("state").asText());
        String taskId = task.path("id").asText();
        String contextId = task.path("contextId").asText();
        String story = task.path("artifacts").get(0).path("parts").get(0).path("text").asText();
        assertTrue(story.contains("月球救援"), "canned agent story must echo the topic, got: " + story);

        String traceId = clientCall.traceId();
        assertNotNull(traceId);
        assertNotEquals(traceId, taskId, "A2A task identity must stay independent from the trace identity");
        assertFalse(response.body().contains(traceId),
                "trace id must not leak into the JSON-RPC body or the A2A task identity");
        System.out.println("[a2a-smoke] traceId=" + traceId + " taskId=" + taskId + " contextId=" + contextId);

        List<JsonNode> observations = awaitObservations(traceId, 4);
        JsonNode client = byName(observations, CLIENT_SPAN);
        JsonNode server = byName(observations, SERVER_SPAN);
        JsonNode agent = observations.stream()
                .filter(obs -> obs.path("name").asText().startsWith("agent."))
                .findFirst()
                .orElseThrow(() -> new AssertionError("agent observation not found in " + observations));
        JsonNode generation = observations.stream()
                .filter(obs -> obs.path("name").asText().startsWith("gen_ai."))
                .findFirst()
                .orElseThrow(() -> new AssertionError("generation observation not found in " + observations));

        assertTrue(client.path("parentObservationId").asText("").isBlank(),
                "client remote_call must be the trace root: " + client);
        assertEquals(client.path("id").asText(), server.path("parentObservationId").asText(),
                "server remote_call must be a child of the client remote_call across the real HTTP hop");
        assertEquals(server.path("id").asText(), agent.path("parentObservationId").asText(),
                "A2A agent observation must nest under the server remote_call");
        assertEquals(agent.path("id").asText(), generation.path("parentObservationId").asText(),
                "model generation must nest under the A2A agent");

        assertTrue(agent.path("input").asText().contains("月球救援"),
                "agent input must contain the A2A message text: " + agent.path("input").asText());
        assertEquals(18, generation.path("usageDetails").path("total").asInt(),
                "canned model usage must be 11+7 tokens: " + generation.path("usageDetails"));

        System.out.println("[a2a-smoke] tree verified: " + CLIENT_SPAN + " -> " + SERVER_SPAN
                + " -> " + agent.path("name").asText() + " -> " + generation.path("name").asText());
    }

    private static ChatModel cannedChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("月球救援队带着最后一份氧气抵达了基地。"))
                        .tokenUsage(new TokenUsage(11, 7))
                        .build();
            }
        };
    }

    private List<JsonNode> awaitObservations(String traceId, int expected) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String auth = Base64.getEncoder().encodeToString(
                (publicKey + ":" + secretKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String url = baseUrl.replaceAll("/+$", "")
                + "/api/public/v2/observations?traceId=" + traceId
                + "&fields=core,basic,io,usage&limit=100";

        long deadline = System.currentTimeMillis() + 60_000;
        JsonNode lastBody = null;
        while (System.currentTimeMillis() < deadline) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Basic " + auth)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                lastBody = JSON.readTree(response.body());
                if (lastBody.path("data").size() >= expected) {
                    List<JsonNode> result = new ArrayList<>();
                    lastBody.withArray("data").forEach(result::add);
                    return result;
                }
            } else {
                System.out.println("[a2a-smoke] observations query returned " + response.statusCode()
                        + ": " + response.body().substring(0, Math.min(200, response.body().length())));
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("Langfuse did not ingest the A2A trace within 60s; last: " + lastBody);
    }

    private JsonNode byName(List<JsonNode> observations, String name) {
        return observations.stream()
                .filter(obs -> name.equals(obs.path("name").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("observation not found: " + name + " in " + observations));
    }
}
