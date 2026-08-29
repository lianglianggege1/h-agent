package com.h.backend.observability.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservabilityConfig;
import com.h.agent.observability.AgentObservabilitySdk;
import com.h.agent.observability.EnvFileLoader;
import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.semantic.ContentCaptureMode;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test of the AgentScope observability middleware against a real Langfuse
 * instance (LANGFUSE_* from environment or repository root .env). It replays the exact
 * threading model of an AgentScope ReAct turn with one tool round-trip:
 *
 * <pre>
 * caller thread              model IO thread               tool pool thread
 * ----------------           ---------------------         ----------------
 * execution scope
 *   onAgent assembly
 *                            round 1 events (thinking,
 *                            tool call, usage)
 *                                                          tool result events
 *                            round 2 events (text, usage)
 * </pre>
 *
 * and asserts the ingested Langfuse trace tree: agent.run -> agent.harness-agent
 * -> {gen_ai round 1, tool.search, gen_ai round 2}, with the RuntimeContext typed
 * ObservationContext as the only causal carrier. Skips automatically when Langfuse
 * is not configured.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealAgentScopeLangfuseSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private String baseUrl;
    private String publicKey;
    private String secretKey;
    private final ExecutorService modelIoThread = Executors.newSingleThreadExecutor();
    private final ExecutorService toolPoolThread = Executors.newSingleThreadExecutor();

    @BeforeAll
    void requireConfiguration() {
        Map<String, String> fileValues = EnvFileLoader.load(Path.of("").toAbsolutePath());
        baseUrl = EnvFileLoader.resolve(fileValues, "LANGFUSE_BASE_URL");
        publicKey = EnvFileLoader.resolve(fileValues, "LANGFUSE_PUBLIC_KEY");
        secretKey = EnvFileLoader.resolve(fileValues, "LANGFUSE_SECRET_KEY");
        Assumptions.assumeTrue(baseUrl != null && publicKey != null && secretKey != null,
                "LANGFUSE_BASE_URL/PUBLIC_KEY/SECRET_KEY not configured; skipping agentscope smoke test");
    }

    @AfterEach
    void tearDown() {
        modelIoThread.shutdownNow();
        toolPoolThread.shutdownNow();
    }

    @Test
    void agentscopeReActTurnFormsCausalTreeInRealLangfuse() throws Exception {
        String sessionId = "agentscope-smoke-" + UUID.randomUUID();
        AgentObservabilityConfig config = AgentObservabilityConfig.builder()
                .baseUrl(baseUrl)
                .publicKey(publicKey)
                .secretKey(secretKey)
                .environment("local")
                .serviceName("h-agent")
                .rootRatio(1.0)
                .contentMode(ContentCaptureMode.STRUCTURED)
                .queueSize(64)
                .batchSize(32)
                .scheduleDelayMillis(200)
                .build();
        AgentObservability observability = AgentObservabilitySdk.build(config);
        assertTrue(observability.enabled(), "observability must be ACTIVE against real Langfuse");

        HAgentObservabilityMiddleware middleware = new HAgentObservabilityMiddleware(observability);
        Agent agent = agent("harness-agent", "as-smoke-1");
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("42")
                .sessionId(sessionId)
                .build();

        AgentExecutionObservation root = observability.start(new AgentExecutionStart(
                "agent.run", sessionId, 42L, "harness-agent", "as-smoke-1", "CHAT",
                "run-agentscope-smoke-1", List.of("agentscope-smoke"), Map.of(),
                SemanticContent.ofMessages(List.of(SemanticMessage.of(
                        "user", "search sources about the agentscope middleware")))));

        try (var ignored = observability.scope(root.observationContext())) {
            Flux<AgentEvent> turn = middleware.onAgent(
                    agent,
                    runtimeContext,
                    new AgentInput(List.of(userMessage("search sources about the agentscope middleware"))),
                    input -> Flux.concat(
                            Flux.defer(() -> modelRound(middleware, agent, runtimeContext, true)),
                            Flux.defer(() -> actingRound(middleware, agent, runtimeContext)),
                            Flux.defer(() -> modelRound(middleware, agent, runtimeContext, false)),
                            Flux.just(
                                    new AgentResultEvent(assistantMessage(
                                            "The middleware keeps the causal tree intact.")),
                                    new AgentEndEvent("reply-1"))));
            turn.publishOn(Schedulers.boundedElastic()).blockLast();
        }
        root.succeed(SemanticContent.ofMessages(List.of(
                SemanticMessage.of("assistant", "The middleware keeps the causal tree intact."))));
        observability.close();

        String traceId = root.traceId();
        assertNotNull(traceId);
        System.out.println("[agentscope-smoke] traceId=" + traceId + " sessionId=" + sessionId);

        List<JsonNode> observations = awaitObservations(traceId, 5);
        JsonNode run = byName(observations, "agent.run");
        JsonNode agentObs = byName(observations, "agent.harness-agent");
        JsonNode tool = byName(observations, "tool.search");
        List<JsonNode> generations = observations.stream()
                .filter(obs -> "gen_ai.claude-sonnet".equals(obs.path("name").asText()))
                .sorted(Comparator.comparing(obs -> obs.path("startTime").asText()))
                .toList();
        assertEquals(2, generations.size(), "expected two generation rounds, got: " + observations);
        JsonNode round1 = generations.get(0);
        JsonNode round2 = generations.get(1);

        assertEquals(run.path("id").asText(), agentObs.path("parentObservationId").asText(),
                "agent span must be a child of agent.run");
        assertEquals(agentObs.path("id").asText(), round1.path("parentObservationId").asText(),
                "generation round 1 must be a child of the agent span");
        assertEquals(agentObs.path("id").asText(), round2.path("parentObservationId").asText(),
                "generation round 2 must be a child of the agent span");
        assertEquals(agentObs.path("id").asText(), tool.path("parentObservationId").asText(),
                "tool must be a child of the agent span");

        assertEquals("SPAN", agentObs.path("type").asText().toUpperCase());
        assertEquals("SPAN", tool.path("type").asText().toUpperCase());
        assertEquals("GENERATION", round1.path("type").asText().toUpperCase());
        assertEquals("GENERATION", round2.path("type").asText().toUpperCase());

        assertTrue(agentObs.path("input").asText().contains("search sources about the agentscope middleware"),
                "agent input must contain the user prompt: " + agentObs.path("input").asText());
        assertTrue(round1.path("input").asText().contains("search sources"),
                "round 1 input must contain the model prompt: " + round1.path("input").asText());
        assertTrue(round1.path("output").asText().contains("call search"),
                "round 1 output must contain the thinking delta: " + round1.path("output").asText());
        assertTrue(tool.path("input").asText().contains("langfuse agentscope"),
                "tool input must contain the tool call arguments: " + tool.path("input").asText());
        assertTrue(tool.path("output").asText().contains("3 verified sources"),
                "tool output must contain the tool result: " + tool.path("output").asText());
        assertTrue(round2.path("output").asText().contains("causal tree intact"),
                "round 2 output must contain the final answer: " + round2.path("output").asText());
        assertTrue(agentObs.path("output").asText().contains("causal tree intact"),
                "agent output must contain the final answer: " + agentObs.path("output").asText());

        assertEquals(300, round1.path("usageDetails").path("input").asInt(),
                "round 1 input usage: " + round1.path("usageDetails"));
        assertEquals(600, round1.path("usageDetails").path("output").asInt(),
                "round 1 output usage: " + round1.path("usageDetails"));
        assertEquals(900, round1.path("usageDetails").path("total").asInt(),
                "round 1 usage must be 300+600 total tokens: " + round1.path("usageDetails"));
        assertEquals(700, round2.path("usageDetails").path("total").asInt(),
                "round 2 usage must be 500+200 total tokens: " + round2.path("usageDetails"));

        System.out.println("[agentscope-smoke] tree verified: "
                + "agent.run -> agent.harness-agent -> {gen#1, tool.search, gen#2}");
    }

    /**
     * Replays one ReAct model call. Round 1 answers with a tool call from the provider IO
     * thread, round 2 answers with plain text - a real provider never blocks the calling
     * thread, so events arrive asynchronously on the executor.
     */
    private Flux<AgentEvent> modelRound(
            HAgentObservabilityMiddleware middleware, Agent agent,
            RuntimeContext ctx, boolean wantsTool) {
        List<AgentEvent> events;
        if (wantsTool) {
            events = List.of(
                    new ThinkingBlockDeltaEvent("reply-1", "block-think-1",
                            "user asks for sources, call search"),
                    new ToolCallStartEvent("reply-1", "call-search-1", "search"),
                    new ModelCallEndEvent("reply-1", ChatUsage.builder()
                            .inputTokens(300)
                            .outputTokens(600)
                            .build()));
        } else {
            events = List.of(
                    new TextBlockDeltaEvent("reply-1", "block-final",
                            "The middleware keeps the causal tree intact."),
                    new ModelCallEndEvent("reply-1", ChatUsage.builder()
                            .inputTokens(500)
                            .outputTokens(200)
                            .build()));
        }
        return middleware.onModelCall(
                agent,
                ctx,
                new ModelCallInput(
                        List.of(userMessage("search sources about the agentscope middleware")),
                        List.of(),
                        null,
                        model("claude-sonnet")),
                input -> eventsFrom(modelIoThread, events));
    }

    /** Replays tool execution: the toolkit runs on the tool pool thread and streams results back. */
    private Flux<AgentEvent> actingRound(
            HAgentObservabilityMiddleware middleware, Agent agent, RuntimeContext ctx) {
        return middleware.onActing(
                agent,
                ctx,
                new ActingInput(List.of(
                        ToolUseBlock.builder().id("call-search-1").name("search")
                                .input(Map.of("query", "langfuse agentscope")).build())),
                input -> eventsFrom(toolPoolThread, List.of(
                        new ToolCallStartEvent("reply-1", "call-search-1", "search"),
                        new ToolResultTextDeltaEvent("reply-1", "call-search-1", "search",
                                "3 verified sources about the middleware"),
                        new ToolResultEndEvent("reply-1", "call-search-1", "search",
                                ToolResultState.SUCCESS))));
    }

    private static Flux<AgentEvent> eventsFrom(ExecutorService executor, List<AgentEvent> events) {
        return Mono.fromFuture(CompletableFuture.supplyAsync(() -> events, executor))
                .flatMapMany(Flux::fromIterable);
    }

    private static Msg userMessage(String text) {
        return Msg.builder().name("user").role(MsgRole.USER).textContent(text).build();
    }

    private static Msg assistantMessage(String text) {
        return Msg.builder().id("reply-1").name("assistant").role(MsgRole.ASSISTANT).textContent(text).build();
    }

    private static Agent agent(String name, String agentId) {
        return (Agent) Proxy.newProxyInstance(
                Agent.class.getClassLoader(),
                new Class<?>[]{Agent.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getAgentId" -> agentId;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static Model model(String name) {
        return (Model) Proxy.newProxyInstance(
                Model.class.getClassLoader(),
                new Class<?>[]{Model.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getModelName" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
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
                System.out.println("[agentscope-smoke] observations query returned " + response.statusCode()
                        + ": " + response.body().substring(0, Math.min(200, response.body().length())));
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("Langfuse did not ingest the agentscope trace within 60s; last: " + lastBody);
    }

    private JsonNode byName(List<JsonNode> observations, String name) {
        return observations.stream()
                .filter(obs -> name.equals(obs.path("name").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("observation not found: " + name + " in " + observations));
    }
}
