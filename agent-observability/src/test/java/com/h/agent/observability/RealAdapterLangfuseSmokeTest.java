package com.h.agent.observability;

import com.h.agent.observability.langchain4j.ObservingStreamingChatModel;
import com.h.agent.observability.langchain4j.ObservingToolProvider;
import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.Base64;
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
 * End-to-end smoke test of the real LangChain4j adapters against a real Langfuse instance
 * (LANGFUSE_* from environment or repository root .env). It replays the exact threading
 * model of a streaming HAssistant turn with one tool round-trip:
 *
 * <pre>
 * caller thread              provider IO thread            tool pool thread
 * -----------                ---------------------          ----------------
 * execution scope
 *   model.chat(request)
 *                            onCompleteResponse(generation)
 *                            provideTools (inside scope)
 *                                                          toolExecutor.execute()
 * </pre>
 *
 * and asserts the ingested Langfuse trace tree: agent.run -> gen_ai round 1 -> tool,
 * with round 2 parented to round 1. Skips automatically when Langfuse is not configured.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealAdapterLangfuseSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private String baseUrl;
    private String publicKey;
    private String secretKey;
    private final ExecutorService providerIoThread = Executors.newSingleThreadExecutor();
    private final ExecutorService toolPoolThread = Executors.newSingleThreadExecutor();

    @BeforeAll
    void requireConfiguration() {
        RealLangfuseSmokeTest.class.getName();
        Map<String, String> fileValues = EnvFileLoader.load(Path.of("").toAbsolutePath());
        baseUrl = EnvFileLoader.resolve(fileValues, "LANGFUSE_BASE_URL");
        publicKey = EnvFileLoader.resolve(fileValues, "LANGFUSE_PUBLIC_KEY");
        secretKey = EnvFileLoader.resolve(fileValues, "LANGFUSE_SECRET_KEY");
        Assumptions.assumeTrue(baseUrl != null && publicKey != null && secretKey != null,
                "LANGFUSE_BASE_URL/PUBLIC_KEY/SECRET_KEY not configured; skipping real adapter smoke test");
    }

    @AfterEach
    void tearDown() {
        providerIoThread.shutdownNow();
        toolPoolThread.shutdownNow();
    }

    @Test
    void streamingToolRoundTripFormsCausalTreeInRealLangfuse() throws Exception {
        String sessionId = "adapter-smoke-" + UUID.randomUUID();
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

        ToolProvider searchTool = request -> ToolProviderResult.builder()
                .add(AiServiceTool.builder()
                        .toolSpecification(ToolSpecification.builder()
                                .name("search")
                                .description("search the web")
                                .build())
                        .toolExecutor((toolRequest, memoryId) ->
                                "{\"results\":[\"h-agent observability adapter\"]}")
                        .build())
                .build();
        ObservingToolProvider observingTools = new ObservingToolProvider(searchTool, observability, "langchain4j");

        // Fake provider model: first round answers with a tool call from its IO thread,
        // second round answers with plain text, exactly like a real streaming LLM. The
        // callback is dispatched asynchronously (never joined) - a real provider never
        // blocks the calling thread, and round 2 is initiated from inside the round 1
        // callback, so a same-thread join would deadlock.
        StreamingChatModel fakeProvider = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                boolean wantsTool = request.messages().stream()
                        .noneMatch(message -> message instanceof ToolExecutionResultMessage);
                CompletableFuture.runAsync(() -> {
                    if (wantsTool) {
                        handler.onPartialResponse("I will search for ");
                        handler.onCompleteResponse(ChatResponse.builder()
                                .aiMessage(AiMessage.builder()
                                        .text("I will search for that.")
                                        .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                                .id("call-search-1")
                                                .name("search")
                                                .arguments("{\"query\":\"langfuse adapter\"}")
                                                .build()))
                                        .build())
                                .build());
                    } else {
                        handler.onPartialResponse("The adapter keeps the tree intact.");
                        handler.onCompleteResponse(ChatResponse.builder()
                                .aiMessage(AiMessage.from("The adapter keeps the tree intact."))
                                .build());
                    }
                }, providerIoThread);
            }
        };
        ObservingStreamingChatModel model =
                new ObservingStreamingChatModel(fakeProvider, observability, "anthropic");

        AgentExecutionObservation root = observability.start(new AgentExecutionStart(
                "agent.run", sessionId, 42L, "general-assistant", "as-adapter-smoke", "CHAT",
                "run-adapter-smoke-1", List.of("adapter-smoke"), Map.of(),
                SemanticContent.ofMessages(List.of(SemanticMessage.of("user", "search for langfuse adapter")))));

        List<ChatMessageHistory> history = new ArrayList<>();
        CompletableFuture<Void> finished = new CompletableFuture<>();
        try (var ignored = observability.scope(root.observationContext())) {
            runStreamingTurn(observability, model, observingTools, root, history, finished, 0);
        }
        finished.get();
        root.succeed(SemanticContent.ofMessages(List.of(
                SemanticMessage.of("assistant", "The adapter keeps the tree intact."))));
        observability.close();

        String traceId = root.traceId();
        assertNotNull(traceId);
        System.out.println("[adapter-smoke] traceId=" + traceId + " sessionId=" + sessionId);

        List<JsonNode> observations = awaitObservations(traceId, 4);
        JsonNode rootObs = byName(observations, "agent.run");
        JsonNode toolObs = byName(observations, "tool.search");
        // The observations API returns arbitrary order; the two generation rounds are
        // disambiguated chronologically.
        List<JsonNode> generations = observations.stream()
                .filter(obs -> "gen_ai.model".equals(obs.path("name").asText()))
                .sorted(java.util.Comparator.comparing(obs -> obs.path("startTime").asText()))
                .toList();
        assertEquals(2, generations.size(), "expected exactly two generation rounds, got: " + observations);
        JsonNode round1 = generations.get(0);
        JsonNode round2 = generations.get(1);

        assertEquals(rootObs.path("id").asText(), round1.path("parentObservationId").asText(),
                "generation round 1 must be a child of agent.run");
        // Non-dynamic providers are consulted during the initial request build, before the
        // first generation span exists, so their tools are parented to the execution. Dynamic
        // providers refresh inside the callback's generation scope instead.
        assertEquals(rootObs.path("id").asText(), toolObs.path("parentObservationId").asText(),
                "tool from a non-dynamic provider must be a child of the execution span");
        assertEquals(round1.path("id").asText(), round2.path("parentObservationId").asText(),
                "generation round 2 (built on the callback thread) must be a child of generation round 1");

        assertTrue(round1.path("input").asText().contains("search for langfuse adapter"),
                "round 1 input must contain the user prompt: " + round1.path("input").asText());
        assertTrue(toolObs.path("input").asText().contains("langfuse adapter"),
                "tool input must contain the tool call arguments");
        assertTrue(toolObs.path("output").asText().contains("observability adapter"),
                "tool output must contain the tool result");
        assertTrue(round2.path("output").asText().contains("tree intact"),
                "round 2 output must contain the final answer");
        assertEquals("GENERATION", round1.path("type").asText().toUpperCase());
        assertEquals("SPAN", toolObs.path("type").asText().toUpperCase());

        System.out.println("[adapter-smoke] tree verified: agent.run -> gen(round1) -> {tool, gen(round2)}");
    }

    /**
     * Replays the AiServiceStreamingResponseHandler loop: request built on the calling
     * thread, tool executed on the pool thread, next request built inside the completion
     * callback (where the observing model keeps the generation scope open).
     */
    private void runStreamingTurn(AgentObservability observability,
                                  ObservingStreamingChatModel model,
                                  ObservingToolProvider tools,
                                  AgentExecutionObservation root,
                                  List<ChatMessageHistory> history,
                                  CompletableFuture<Void> finished,
                                  int round) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("You are a helpful assistant with tools."));
        messages.add(UserMessage.from("search for langfuse adapter"));
        for (ChatMessageHistory item : history) {
            messages.add(item.message());
        }

        ToolProviderResult provided = tools.provideTools(ToolProviderRequest.builder()
                .userMessage(UserMessage.from("search for langfuse adapter"))
                .invocationContext(invocationContext())
                .messages(messages)
                .build());

        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(provided.aiServiceTools().stream()
                        .map(AiServiceTool::toolSpecification)
                        .toList())
                .build();

        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                AiMessage aiMessage = completeResponse.aiMessage();
                history.add(new ChatMessageHistory(aiMessage));
                if (aiMessage.hasToolExecutionRequests()) {
                    ToolExecutionRequest toolRequest = aiMessage.toolExecutionRequests().get(0);
                    // Framework submits the tool to the pool from onCompleteToolCall and then
                    // blocks for the result inside onCompleteResponse before building the
                    // next round on this same callback thread.
                    String toolResult;
                    try {
                        toolResult = toolPoolThread.submit(() -> provided.aiServiceTools().get(0)
                                .toolExecutor()
                                .execute(toolRequest, "memory-1")).get();
                    } catch (Exception e) {
                        finished.completeExceptionally(e);
                        return;
                    }
                    history.add(new ChatMessageHistory(ToolExecutionResultMessage.from(
                            toolRequest, toolResult)));
                    runStreamingTurn(observability, model, tools, root, history, finished, round + 1);
                } else {
                    finished.complete(null);
                }
            }

            @Override
            public void onError(Throwable error) {
                finished.completeExceptionally(error);
            }
        });
    }

    private record ChatMessageHistory(dev.langchain4j.data.message.ChatMessage message) {
    }

    private static dev.langchain4j.invocation.InvocationContext invocationContext() {
        return (dev.langchain4j.invocation.InvocationContext) java.lang.reflect.Proxy.newProxyInstance(
                dev.langchain4j.invocation.InvocationContext.class.getClassLoader(),
                new Class<?>[]{dev.langchain4j.invocation.InvocationContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "invocation-context";
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
                if (lastBody.path("data").size() >= expected) {
                    List<JsonNode> result = new ArrayList<>();
                    lastBody.withArray("data").forEach(result::add);
                    return result;
                }
            } else {
                System.out.println("[adapter-smoke] observations query returned " + response.statusCode()
                        + ": " + response.body().substring(0, Math.min(200, response.body().length())));
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("Langfuse did not ingest the adapter trace within 60s; last: " + lastBody);
    }

    private JsonNode byName(List<JsonNode> observations, String name) {
        return observations.stream()
                .filter(obs -> name.equals(obs.path("name").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("observation not found: " + name + " in " + observations));
    }
}
