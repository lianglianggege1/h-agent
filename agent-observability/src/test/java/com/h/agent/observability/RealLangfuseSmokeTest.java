package com.h.agent.observability;

import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Langfuse smoke test: sends a full causal tree over OTLP/HTTP to the Langfuse
 * instance configured via LANGFUSE_* (environment or repository root .env), then
 * verifies ingestion through the public REST API. Skips automatically when no
 * configuration is present, so CI without Langfuse stays green.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealLangfuseSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private String baseUrl;
    private String publicKey;
    private String secretKey;

    @BeforeAll
    void requireConfiguration() {
        configureProxyFromEnvironment();
        Map<String, String> fileValues = EnvFileLoader.load(Path.of("").toAbsolutePath());
        baseUrl = EnvFileLoader.resolve(fileValues, "LANGFUSE_BASE_URL");
        publicKey = EnvFileLoader.resolve(fileValues, "LANGFUSE_PUBLIC_KEY");
        secretKey = EnvFileLoader.resolve(fileValues, "LANGFUSE_SECRET_KEY");
        Assumptions.assumeTrue(baseUrl != null && publicKey != null && secretKey != null,
                "LANGFUSE_BASE_URL/PUBLIC_KEY/SECRET_KEY not configured; skipping real smoke test");
    }

    /**
     * The self-hosted Langfuse may only be reachable through the developer machine's
     * local HTTP proxy (http_proxy env). Java never reads that variable on its own,
     * so install it as the global selector for this test JVM; this also covers the
     * OTLP exporter's OkHttp transport. Environments without http_proxy keep direct
     * connections.
     */
    private static void configureProxyFromEnvironment() {
        String proxy = System.getenv("http_proxy");
        if (proxy == null || proxy.isBlank()) {
            proxy = System.getenv("HTTP_PROXY");
        }
        if (proxy == null || proxy.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(proxy.trim());
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            if (host == null) {
                return;
            }
            java.net.InetSocketAddress address = new java.net.InetSocketAddress(host, port);
            java.net.ProxySelector.setDefault(new java.net.ProxySelector() {
                @Override
                public List<java.net.Proxy> select(URI target) {
                    if (target.getHost() == null || target.getHost().equals("127.0.0.1")
                            || target.getHost().equals("localhost")) {
                        return List.of(java.net.Proxy.NO_PROXY);
                    }
                    return List.of(new java.net.Proxy(java.net.Proxy.Type.HTTP, address));
                }

                @Override
                public void connectFailed(URI target, java.net.SocketAddress sa, java.io.IOException ioe) {
                }
            });
            System.out.println("[smoke] using HTTP proxy from http_proxy env: " + host + ":" + port);
        } catch (RuntimeException ex) {
            System.out.println("[smoke] ignoring invalid http_proxy value: " + proxy);
        }
    }

    @Test
    void fullCausalTreeReachesRealLangfuse() throws Exception {
        String sessionId = "smoke-" + UUID.randomUUID();
        AgentObservabilityConfig config = AgentObservabilityConfig.builder()
                .baseUrl(baseUrl)
                .publicKey(publicKey)
                .secretKey(secretKey)
                .environment("local")
                .serviceName("h-agent")
                .rootRatio(1.0)
                .contentMode(com.h.agent.observability.semantic.ContentCaptureMode.STRUCTURED)
                .queueSize(64)
                .batchSize(32)
                .scheduleDelayMillis(200)
                .build();
        AgentObservability observability = AgentObservabilitySdk.build(config);
        assertTrue(observability.enabled(), "observability must be ACTIVE against real Langfuse");

        AgentExecutionObservation root = observability.start(new AgentExecutionStart(
                "agent.run", sessionId, 42L, "general-assistant", "as-smoke", "CHAT", "run-smoke-1",
                List.of("smoke-test"), Map.of(),
                SemanticContent.ofMessages(List.of(SemanticMessage.of("user", "please search and delegate")))));

        String serverGenerationId = null;
        try (AutoCloseable scope = root.scope()) {
            AgentObservation agent = observability.span(
                    ObservationSpec.of("agent general-assistant", HObsKind.AGENT, "langchain4j"),
                    observability.currentContext());
            agent.attribute("h.agent_id", "general-assistant");
            try (AgentObservation agentClose = agent) {
                AgentObservation generation = observability.span(
                        ObservationSpec.of("gen_ai.claude-sonnet-4", HObsKind.GENERATION, "langchain4j"),
                        agent.context());
                generation.input(SemanticContent.ofMessages(List.of(
                        SemanticMessage.of("system", "you are a helpful assistant"),
                        SemanticMessage.of("user", "please search and delegate"))));
                generation.attribute("gen_ai.request.model", "claude-sonnet-4");
                generation.usage(120L, 80L, 200L);
                generation.output(SemanticContent.ofMessages(List.of(
                        SemanticMessage.of("assistant", "I will call the search tool now."))));
                generation.succeed();

                AgentObservation tool = observability.span(
                        ObservationSpec.of("tool search", HObsKind.TOOL, "langchain4j",
                                Map.of("h.tool_name", "search")),
                        agent.context());
                tool.input(SemanticContent.ofMessages(List.of(SemanticMessage.of("tool", "{\"query\":\"h-agent\"}"))));
                tool.output(SemanticContent.ofMessages(List.of(SemanticMessage.of("tool", "{\"hits\":2}"))));
                tool.succeed();

                AgentObservation remoteCall = observability.span(
                        ObservationSpec.of("remote_call a2a.message/send", HObsKind.REMOTE_CALL, "a2a-client"),
                        agent.context());
                try (AgentObservation remoteClose = remoteCall) {
                    Map<String, String> headers = new HashMap<>();
                    observability.inject(remoteCall.context(), headers);
                    assertTrue(headers.containsKey("traceparent"),
                            "W3C traceparent must be injected for cross-service propagation");

                    ObservationContext serverContext = observability.extract(headers);
                    AgentObservation serverSpan = observability.span(
                            ObservationSpec.of("remote_call a2a.server", HObsKind.REMOTE_CALL, "a2a-server"),
                            serverContext);
                    assertEquals(root.traceId(), serverSpan.traceId(),
                            "server-side span must stay in the same trace");
                    try (AgentObservation serverClose = serverSpan) {
                        AgentObservation serverGeneration = observability.span(
                                ObservationSpec.of("gen_ai.claude-sonnet-4", HObsKind.GENERATION, "a2a-server"),
                                serverSpan.context());
                        serverGeneration.input(SemanticContent.ofMessages(List.of(
                                SemanticMessage.of("user", "remote task"))));
                        serverGeneration.output(SemanticContent.ofMessages(List.of(
                                SemanticMessage.of("assistant", "remote answer"))));
                        serverGeneration.succeed();
                        serverGenerationId = null;
                    }
                    serverSpan.succeed();
                }
                remoteCall.succeed();
            }
        }
        root.succeed(SemanticContent.ofMessages(List.of(
                SemanticMessage.of("assistant", "final answer with search results"))));
        observability.close();

        String traceId = root.traceId();
        assertNotNull(traceId);
        System.out.println("[smoke] traceId=" + traceId + " sessionId=" + sessionId + " baseUrl=" + baseUrl);

        List<JsonNode> observations = awaitObservations(traceId);
        System.out.println("[smoke] ingested observations=" + observations.size());

        assertEquals(7, observations.size(), "expected 7 observations, got: " + observations);
        for (JsonNode observation : observations) {
            assertEquals(traceId, observation.path("traceId").asText(),
                    "all observations must share the root trace id");
        }

        JsonNode rootObs = byName(observations, "agent.run");
        assertTrue(rootObs.path("parentObservationId").isNull() || rootObs.path("parentObservationId").asText().isEmpty(),
                "agent.run must be the trace root");

        JsonNode agentObs = byName(observations, "agent general-assistant");
        assertEquals(rootObs.path("id").asText(), agentObs.path("parentObservationId").asText(),
                "agent must be a child of agent.run");

        JsonNode toolObs = byName(observations, "tool search");
        assertEquals(agentObs.path("id").asText(), toolObs.path("parentObservationId").asText(),
                "tool must be a child of the agent");

        JsonNode serverObs = byName(observations, "remote_call a2a.server");
        JsonNode clientObs = byName(observations, "remote_call a2a.message/send");
        assertEquals(clientObs.path("id").asText(), serverObs.path("parentObservationId").asText(),
                "server-side remote_call must be the child of the client-side remote_call");

        List<JsonNode> generations = observations.stream()
                .filter(obs -> "gen_ai.claude-sonnet-4".equals(obs.path("name").asText()))
                .toList();
        assertEquals(2, generations.size(), "one client-side and one server-side generation expected");
        assertTrue(generations.stream().anyMatch(g -> agentObs.path("id").asText()
                        .equals(g.path("parentObservationId").asText())),
                "client-side generation must be a child of the agent");
        JsonNode generationObs = generations.stream()
                .filter(g -> agentObs.path("id").asText().equals(g.path("parentObservationId").asText()))
                .findFirst().orElseThrow();

        assertEquals("GENERATION", generationObs.path("type").asText().toUpperCase(),
                "gen_ai.* spans must map to Langfuse GENERATION observations");
        assertTrue(hasText(generationObs, "input"), "generation input must be recorded: " + generationObs);
        assertTrue(hasText(generationObs, "output"), "generation output must be recorded: " + generationObs);
        assertTrue(generationObs.path("input").asText().contains("please search and delegate"),
                "generation input must contain the prompt text: " + generationObs.path("input").asText());
        assertTrue(hasText(rootObs, "input"), "root input must be recorded: " + rootObs);

        System.out.println("[smoke] tree verified: agent.run -> agent -> {generation, tool, remote_call -> server -> generation}");
    }

    private List<JsonNode> awaitObservations(String traceId) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String auth = Base64.getEncoder().encodeToString(
                (publicKey + ":" + secretKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String url = baseUrl.replaceAll("/+$", "")
                + "/api/public/v2/observations?traceId=" + traceId
                + "&fields=core,basic,io&limit=100";

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
                int count = lastBody.path("data").size();
                if (count >= 7) {
                    List<JsonNode> result = new java.util.ArrayList<>();
                    lastBody.withArray("data").forEach(result::add);
                    return result;
                }
            } else {
                System.out.println("[smoke] observations query returned " + response.statusCode()
                        + ": " + response.body().substring(0, Math.min(200, response.body().length())));
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("Langfuse did not ingest the trace within 60s; last response: " + lastBody);
    }

    private JsonNode byName(List<JsonNode> observations, String name) {
        return observations.stream()
                .filter(obs -> name.equals(obs.path("name").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("observation not found: " + name + " in " + observations));
    }

    private boolean hasText(JsonNode observation, String field) {
        JsonNode value = observation.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        return !value.asText().isBlank();
    }
}
