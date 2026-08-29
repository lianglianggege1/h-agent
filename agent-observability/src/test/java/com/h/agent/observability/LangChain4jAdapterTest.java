package com.h.agent.observability;

import com.h.agent.observability.langchain4j.ObservingAgentListener;
import com.h.agent.observability.langchain4j.ObservingStreamingChatModel;
import com.h.agent.observability.langchain4j.ObservingToolProvider;
import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.semantic.ArtifactKind;
import com.h.agent.observability.semantic.ArtifactReference;
import com.h.agent.observability.semantic.ArtifactUse;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import com.h.agent.observability.semantic.ToolArtifactCollector;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.BeforeAgentToolExecution;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the LangChain4j adapters. They replicate the framework's real
 * threading model: the request is built on the calling thread, streaming callbacks and
 * concurrent tool executions arrive on other threads. The adapters must keep the causal
 * tree intact across those boundaries.
 */
class LangChain4jAdapterTest {

    private DefaultAgentObservability observability;
    private InMemorySpanExporter exporter;
    private final ExecutorService otherThread = Executors.newSingleThreadExecutor();

    private DefaultAgentObservability create() {
        exporter = InMemorySpanExporter.create();
        AgentObservabilityConfig config = AgentObservabilityConfig.builder()
                .baseUrl("http://langfuse.local")
                .publicKey("pk-test")
                .secretKey("sk-test")
                .rootRatio(1.0)
                .scheduleDelayMillis(10)
                .build();
        return DefaultAgentObservability.buildWithExporter(config, exporter);
    }

    @AfterEach
    void tearDown() {
        if (observability != null) {
            observability.close();
        }
        otherThread.shutdownNow();
    }

    private AgentExecutionStart executionStart() {
        return new AgentExecutionStart(
                "agent.run", "session-1", 42L, "general-assistant", "as-1", "CHAT", "run-1",
                List.of("adapter"), Map.of(),
                SemanticContent.ofMessages(List.of(SemanticMessage.of("user", "hello"))));
    }

    private static AgentInstance agentInstance(String name) {
        return (AgentInstance) Proxy.newProxyInstance(
                AgentInstance.class.getClassLoader(),
                new Class<?>[]{AgentInstance.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "name", "agentId" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static AgenticScope agenticScope() {
        return (AgenticScope) Proxy.newProxyInstance(
                AgenticScope.class.getClassLoader(),
                new Class<?>[]{AgenticScope.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static InvocationContext invocationContext() {
        return (InvocationContext) Proxy.newProxyInstance(
                InvocationContext.class.getClassLoader(),
                new Class<?>[]{InvocationContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "invocation-context";
                    default -> null;
                });
    }

    private static StreamingChatModel modelInvokingDelegateOnOtherThread(
            ExecutorService executor, ChatResponse completion) {
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                try {
                    executor.submit(() -> handler.onCompleteResponse(completion)).get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private static StreamingChatModel modelFailingOnOtherThread(ExecutorService executor, Throwable error) {
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                try {
                    executor.submit(() -> handler.onError(error)).get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    @Test
    void agentListenerScopesInvocationAndParentsNestedModelCalls() {
        observability = create();
        ObservingAgentListener listener = new ObservingAgentListener(observability);
        AgentInstance agent = agentInstance("planner");
        AgenticScope scope = agenticScope();
        Map<String, Object> inputs = Map.of("message", "hello");

        AgentExecutionObservation root = observability.start(executionStart());
        try (var ignored = observability.scope(root.observationContext())) {
            listener.beforeAgentInvocation(new AgentRequest(scope, agent, inputs));

            AgentObservation generation = observability.span(
                    ObservationSpec.of("gen_ai.claude-sonnet", HObsKind.GENERATION, "langchain4j"),
                    observability.currentContext());
            generation.succeed();

            listener.afterAgentInvocation(new AgentResponse(scope, agent, inputs, "planned", null, null));
        }
        root.succeed(null);
        observability.flushForTest();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData rootSpan = spanByName(spans, "agent.run");
        SpanData agentSpan = spanByName(spans, "agent.planner");
        SpanData generationSpan = spanByName(spans, "gen_ai.claude-sonnet");

        assertEquals(rootSpan.getSpanId(), agentSpan.getParentSpanId(),
                "agent span must be a child of the execution");
        assertEquals(agentSpan.getSpanId(), generationSpan.getParentSpanId(),
                "model call inside the agent body must be a child of the agent span");
        assertTrue(agentSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))
                .contains("hello"));
        assertTrue(agentSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))
                .contains("planned"));
    }

    @Test
    void agentListenerObservesToolExecutionWithCallAndResult() {
        observability = create();
        ObservingAgentListener listener = new ObservingAgentListener(observability);
        AgentInstance agent = agentInstance("planner");
        AgenticScope scope = agenticScope();
        Map<String, Object> inputs = Map.of("message", "hello");

        AgentExecutionObservation root = observability.start(executionStart());
        try (var ignored = observability.scope(root.observationContext())) {
            listener.beforeAgentInvocation(new AgentRequest(scope, agent, inputs));

            ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                    .id("call-1").name("search").arguments("{\"query\":\"h-agent\"}").build();
            listener.beforeAgentToolExecution(new BeforeAgentToolExecution(agent,
                    BeforeToolExecution.builder()
                            .request(toolRequest)
                            .invocationContext(invocationContext())
                            .build()));

            listener.afterAgentToolExecution(new AfterAgentToolExecution(agent,
                    ToolExecution.builder()
                            .request(toolRequest)
                            .result(ToolExecutionResult.builder().resultText("{\"hits\":2}").build())
                            .invocationContext(invocationContext())
                            .build()));

            listener.afterAgentInvocation(new AgentResponse(scope, agent, inputs, "done", null, null));
        }
        root.succeed(null);
        observability.flushForTest();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData agentSpan = spanByName(spans, "agent.planner");
        SpanData toolSpan = spanByName(spans, "tool.search");

        assertEquals(agentSpan.getSpanId(), toolSpan.getParentSpanId(),
                "tool span must be a child of the agent span");
        assertTrue(toolSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))
                .contains("h-agent"));
        assertTrue(toolSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))
                .contains("hits"));
    }

    @Test
    void agentListenerFailsObservationOnError() {
        observability = create();
        ObservingAgentListener listener = new ObservingAgentListener(observability);
        AgentInstance agent = agentInstance("planner");
        AgenticScope scope = agenticScope();
        Map<String, Object> inputs = Map.of("message", "hello");

        listener.beforeAgentInvocation(new AgentRequest(scope, agent, inputs));
        listener.onAgentInvocationError(new AgentInvocationError(
                scope, agent, inputs, new IllegalStateException("kaputt")));
        observability.flushForTest();

        SpanData agentSpan = spanByName(exporter.getFinishedSpanItems(), "agent.planner");
        assertEquals("java.lang.IllegalStateException",
                agentSpan.getAttributes().get(AttributeKey.stringKey("exception.type")));
    }

    @Test
    void streamingModelKeepsCausalTreeAcrossCallbackThreads() throws Exception {
        observability = create();
        AgentObservation[] secondRoundParent = new AgentObservation[1];

        StreamingChatModel delegate = modelInvokingDelegateOnOtherThread(otherThread,
                ChatResponse.builder().aiMessage(AiMessage.from("first round done")).build());
        ObservingStreamingChatModel model = new ObservingStreamingChatModel(delegate, observability, "anthropic");

        AgentExecutionObservation root = observability.start(executionStart());
        try (var ignored = observability.scope(root.observationContext())) {
            model.chat(ChatRequest.builder()
                            .messages(List.of(UserMessage.from("hi")))
                            .build(),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse completeResponse) {
                            // Framework follow-up work on the callback thread: dynamic tool
                            // provisioning and the next model round must see the generation.
                            secondRoundParent[0] = observability.span(
                                    ObservationSpec.of("gen_ai.claude-sonnet-2", HObsKind.GENERATION, "langchain4j"),
                                    observability.currentContext());
                            secondRoundParent[0].succeed();
                        }

                        @Override
                        public void onError(Throwable error) {
                        }
                    });
        }
        root.succeed(null);
        observability.flushForTest();

        assertNotNull(secondRoundParent[0]);
        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData firstRound = spanByName(spans, "gen_ai.model");
        SpanData secondRound = spanByName(spans, "gen_ai.claude-sonnet-2");

        assertEquals(root.traceId(), secondRoundParent[0].traceId());
        assertEquals(firstRound.getSpanId(), secondRound.getParentSpanId(),
                "next-round generation must be parented to the streaming generation span");
        assertTrue(firstRound.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))
                .contains("first round done"));
    }

    @Test
    void streamingModelFailsObservationOnError() throws Exception {
        observability = create();
        RuntimeException boom = new IllegalStateException("provider down");

        ObservingStreamingChatModel model = new ObservingStreamingChatModel(
                modelFailingOnOtherThread(otherThread, boom), observability, "anthropic");

        AgentExecutionObservation root = observability.start(executionStart());
        try (var ignored = observability.scope(root.observationContext())) {
            model.chat(ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build(),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse completeResponse) {
                        }

                        @Override
                        public void onError(Throwable error) {
                        }
                    });
        }
        root.succeed(null);
        observability.flushForTest();

        SpanData generationSpan = spanByName(exporter.getFinishedSpanItems(), "gen_ai.model");
        assertEquals("java.lang.IllegalStateException",
                generationSpan.getAttributes().get(AttributeKey.stringKey("exception.type")));
    }

    @Test
    void toolProviderCapturesRequestBuildContextForExecutionOnOtherThread() throws Exception {
        observability = create();
        ToolProvider delegate = request -> ToolProviderResult.builder()
                .add(AiServiceTool.builder()
                        .toolSpecification(ToolSpecification.builder().name("search").build())
                        .toolExecutor((toolRequest, memoryId) -> "{\"hits\":3}")
                        .build())
                .build();
        ObservingToolProvider provider = new ObservingToolProvider(delegate, observability, "langchain4j");

        AgentExecutionObservation root = observability.start(executionStart());
        ToolProviderResult observed;
        try (var ignored = observability.scope(root.observationContext())) {
            AgentObservation generation = observability.span(
                    ObservationSpec.of("gen_ai.claude-sonnet", HObsKind.GENERATION, "langchain4j"),
                    observability.currentContext());
            try (var generationScope = observability.scope(generation.context())) {
                observed = provider.provideTools(ToolProviderRequest.builder()
                        .userMessage(UserMessage.from("hi"))
                        .invocationContext(invocationContext())
                        .build());
            }
            generation.succeed();
        }

        // Framework runs concurrent tools on a pool thread: no ambient context there.
        String result = otherThread.submit(() -> observed.aiServiceTools().get(0)
                .toolExecutor()
                .execute(ToolExecutionRequest.builder()
                        .id("call-9").name("search").arguments("{}").build(), "memory-1"))
                .get();
        assertEquals("{\"hits\":3}", result);
        root.succeed(null);
        observability.flushForTest();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData generationSpan = spanByName(spans, "gen_ai.claude-sonnet");
        SpanData toolSpan = spanByName(spans, "tool.search");

        assertEquals(generationSpan.getSpanId(), toolSpan.getParentSpanId(),
                "tool span executed on another thread must be parented to the generation");
        assertTrue(toolSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))
                .contains("hits"));
        assertEquals("TO_LLM", observed.aiServiceTools().get(0).returnBehavior().name());
    }

    @Test
    void toolProviderAppendsCommittedArtifactReferencesToToolOutput() throws Exception {
        observability = create();
        ToolProvider delegate = request -> ToolProviderResult.builder()
                .add(AiServiceTool.builder()
                        .toolSpecification(ToolSpecification.builder().name("send_file_to_chat").build())
                        .toolExecutor((toolRequest, memoryId) -> {
                            ToolArtifactCollector.record(ArtifactReference.builder()
                                    .resourceId("res-77")
                                    .kind(ArtifactKind.FILE)
                                    .use(ArtifactUse.TOOL_OUTPUT)
                                    .businessRole("GENERATED")
                                    .mimeType("application/pdf")
                                    .byteSize(4321L)
                                    .fileName("report.pdf")
                                    .build());
                            return "文件已发送到聊天中。";
                        })
                        .build())
                .build();
        ObservingToolProvider provider = new ObservingToolProvider(delegate, observability, "langchain4j");

        AgentExecutionObservation root = observability.start(executionStart());
        ToolProviderResult observed;
        try (var ignored = observability.scope(root.observationContext())) {
            AgentObservation generation = observability.span(
                    ObservationSpec.of("gen_ai.claude-sonnet", HObsKind.GENERATION, "langchain4j"),
                    observability.currentContext());
            try (var generationScope = observability.scope(generation.context())) {
                observed = provider.provideTools(ToolProviderRequest.builder()
                        .userMessage(UserMessage.from("生成报告"))
                        .invocationContext(invocationContext())
                        .build());
            }
            generation.succeed();
        }

        String result = otherThread.submit(() -> observed.aiServiceTools().get(0)
                .toolExecutor()
                .execute(ToolExecutionRequest.builder()
                        .id("call-10").name("send_file_to_chat").arguments("{}").build(), "memory-1"))
                .get();
        assertEquals("文件已发送到聊天中。", result);
        root.succeed(null);
        observability.flushForTest();

        SpanData toolSpan = spanByName(exporter.getFinishedSpanItems(), "tool.send_file_to_chat");
        String output = toolSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"));
        assertTrue(output.contains("文件已发送到聊天中。"), "tool result text stays in the output");
        assertTrue(output.contains("\"type\":\"artifact\""));
        assertTrue(output.contains("res-77"));
        assertTrue(output.contains("TOOL_OUTPUT"));
        assertTrue(output.contains("report.pdf"));
        assertFalse(output.contains("storage"), "no storage location may leak into the reference");
    }

    @Test
    void toolArtifactCollectorIsDrainedWhenToolFailsSoNothingLeaksToNextExecution() throws Exception {
        observability = create();
        ToolProvider delegate = request -> ToolProviderResult.builder()
                .add(AiServiceTool.builder()
                        .toolSpecification(ToolSpecification.builder().name("failing").build())
                        .toolExecutor((toolRequest, memoryId) -> {
                            ToolArtifactCollector.record(ArtifactReference.builder()
                                    .resourceId("res-stale").build());
                            throw new IllegalStateException("boom");
                        })
                        .build())
                .add(AiServiceTool.builder()
                        .toolSpecification(ToolSpecification.builder().name("plain").build())
                        .toolExecutor((toolRequest, memoryId) -> "ok")
                        .build())
                .build();
        ObservingToolProvider provider = new ObservingToolProvider(delegate, observability, "langchain4j");

        AgentExecutionObservation root = observability.start(executionStart());
        ToolProviderResult observed;
        try (var ignored = observability.scope(root.observationContext())) {
            observed = provider.provideTools(ToolProviderRequest.builder()
                    .userMessage(UserMessage.from("hi"))
                    .invocationContext(invocationContext())
                    .build());
        }

        try {
            otherThread.submit(() -> observed.aiServiceTools().get(0)
                    .toolExecutor()
                    .execute(ToolExecutionRequest.builder()
                            .id("call-11").name("failing").arguments("{}").build(), "memory-1"))
                    .get();
        } catch (java.util.concurrent.ExecutionException expected) {
            // 工具失败：观测记录失败，收集器必须被清空。
        }
        String nextResult = otherThread.submit(() -> observed.aiServiceTools().get(1)
                .toolExecutor()
                .execute(ToolExecutionRequest.builder()
                        .id("call-12").name("plain").arguments("{}").build(), "memory-1"))
                .get();
        assertEquals("ok", nextResult);
        root.succeed(null);
        observability.flushForTest();

        SpanData plainSpan = spanByName(exporter.getFinishedSpanItems(), "tool.plain");
        String output = plainSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"));
        assertFalse(output.contains("res-stale"),
                "a failed tool execution must not leak its artifacts into the next one");
        SpanData failingSpan = spanByName(exporter.getFinishedSpanItems(), "tool.failing");
        assertEquals("failure", failingSpan.getAttributes().get(AttributeKey.stringKey("h.outcome")));
    }

    @Test
    void noopObservabilityKeepsAdaptersTransparent() {
        ObservingAgentListener listener = new ObservingAgentListener(NoopAgentObservability.getInstance());
        AgentInstance agent = agentInstance("planner");
        AgenticScope scope = agenticScope();
        listener.beforeAgentInvocation(new AgentRequest(scope, agent, Map.of("m", "x")));
        listener.afterAgentInvocation(new AgentResponse(scope, agent, Map.of("m", "x"), "ok", null, null));

        StreamingChatModel delegate = modelInvokingDelegateOnOtherThread(otherThread,
                ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
        ObservingStreamingChatModel model = new ObservingStreamingChatModel(
                delegate, NoopAgentObservability.getInstance(), "anthropic");
        model.chat(ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                    }

                    @Override
                    public void onError(Throwable error) {
                    }
                });
    }

    private SpanData spanByName(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found: " + name + " in "
                        + spans.stream().map(SpanData::getName).toList()));
    }
}
