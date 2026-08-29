package com.h.backend.observability.agentscope;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservabilityConfig;
import com.h.agent.observability.AgentObservabilityTesting;
import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
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
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentScope 观测 middleware 契约测试。复刻 SDK 真实线程模型：middleware 在调用线程
 * 串成洋葱链，事件流经 Reactor 调度线程回填；类型化 RuntimeContext 载体是嵌套
 * Span 与子 Agent 派生上下文的唯一因果来源（设计 12.2 / 12.4）。
 */
class HAgentObservabilityMiddlewareTest {

    private AgentObservability observability;
    private InMemorySpanExporter exporter;

    private AgentObservability create() {
        exporter = InMemorySpanExporter.create();
        AgentObservabilityConfig config = AgentObservabilityConfig.builder()
                .baseUrl("http://langfuse.local")
                .publicKey("pk-test")
                .secretKey("sk-test")
                .rootRatio(1.0)
                .scheduleDelayMillis(10)
                .build();
        return AgentObservabilityTesting.build(config, exporter);
    }

    @AfterEach
    void tearDown() {
        if (observability != null) {
            observability.close();
        }
    }

    @Test
    void keepsCausalTreeAcrossReactorThreadHops() {
        observability = create();
        HAgentObservabilityMiddleware middleware = new HAgentObservabilityMiddleware(observability);
        Agent agent = agent("harness-agent", "harness");
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("1")
                .sessionId("session-1")
                .build();

        AgentExecutionObservation root = observability.start(executionStart());
        try (var ignored = observability.scope(root.observationContext())) {
            Flux<AgentEvent> agentStream = middleware.onAgent(
                    agent,
                    runtimeContext,
                    new AgentInput(List.of(userMessage("整理来源"))),
                    input -> Flux.concat(
                            modelCallStream(middleware, agent, runtimeContext),
                            actingStream(middleware, agent, runtimeContext),
                            Flux.just(
                                    new AgentResultEvent(assistantMessage("final answer")),
                                    new AgentEndEvent("reply-1"))));
            // SDK 的事件流经 boundedElastic 回到订阅者；因果树必须跨线程保持。
            agentStream.publishOn(Schedulers.boundedElastic()).blockLast();
        }
        root.succeed(null);
        AgentObservabilityTesting.flush(observability);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData run = spanByName(spans, "agent.run");
        SpanData agentSpan = spanByName(spans, "agent.harness-agent");
        SpanData generation = spanByName(spans, "gen_ai.claude-sonnet");
        SpanData tool = spanByName(spans, "tool.search");

        assertEquals(run.getSpanId(), agentSpan.getParentSpanId(), "agent span must be child of agent.run");
        assertEquals(agentSpan.getSpanId(), generation.getParentSpanId(),
                "generation must be child of the agent span");
        assertEquals(agentSpan.getSpanId(), tool.getParentSpanId(),
                "tool must be child of the agent span");
        assertEquals(run.getTraceId(), agentSpan.getTraceId());
        assertEquals(run.getTraceId(), generation.getTraceId());
        assertEquals(run.getTraceId(), tool.getTraceId());
        assertEquals(900L, generation.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("gen_ai.usage.total_tokens")));
        assertNotNull(agentSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.input")));
        assertTrue(agentSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.output"))
                .contains("final answer"));
        assertTrue(tool.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.output"))
                .contains("3 results"));
    }

    @Test
    void runtimeContextTypedCarrierInheritedByDerivedChildContext() {
        observability = create();
        HAgentObservabilityMiddleware middleware = new HAgentObservabilityMiddleware(observability);
        Agent agent = agent("harness-agent", "harness");
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("1")
                .sessionId("session-1")
                .build();
        assertNull(runtimeContext.get(ObservationContext.class));

        AgentExecutionObservation root = observability.start(executionStart());
        try (var ignored = observability.scope(root.observationContext())) {
            middleware.onAgent(agent, runtimeContext,
                    new AgentInput(List.of(userMessage("hi"))),
                    input -> Flux.empty()).blockLast();
        }
        root.succeed(null);
        AgentObservabilityTesting.flush(observability);

        ObservationContext typed = runtimeContext.get(ObservationContext.class);
        assertNotNull(typed, "onAgent must publish its observation context into the RuntimeContext");
        // SDK DefaultAgentManager 用 RuntimeContext.builder(parentRc) 派生子上下文；
        // 类型化载体必须随之复制，子 Agent 的 middleware 才能挂到父 Agent Span 下。
        RuntimeContext childContext = RuntimeContext.builder(runtimeContext)
                .sessionId("child-session")
                .build();
        assertSame(typed, childContext.get(ObservationContext.class),
                "derived child RuntimeContext must inherit the typed observation carrier");
        assertEquals("child-session", childContext.getSessionId());
    }

    @Test
    void toolObservationsAreIndividualPerCall() {
        observability = create();
        HAgentObservabilityMiddleware middleware = new HAgentObservabilityMiddleware(observability);
        Agent agent = agent("harness-agent", "harness");
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("1")
                .sessionId("session-1")
                .build();

        AgentExecutionObservation root = observability.start(executionStart());
        try (var ignored = observability.scope(root.observationContext())) {
            middleware.onAgent(agent, runtimeContext,
                    new AgentInput(List.of(userMessage("hi"))),
                    input -> actingStream(middleware, agent, runtimeContext))
                    .publishOn(Schedulers.boundedElastic())
                    .blockLast();
        }
        root.succeed(null);
        AgentObservabilityTesting.flush(observability);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(4, spans.size(), "expected agent + two tool spans + root run, got: " + spans);
        SpanData search = spanByName(spans, "tool.search");
        SpanData read = spanByName(spans, "tool.read_file");
        SpanData agentSpan = spanByName(spans, "agent.harness-agent");
        assertEquals(agentSpan.getSpanId(), search.getParentSpanId());
        assertEquals(agentSpan.getSpanId(), read.getParentSpanId());
        assertTrue(search.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.output"))
                .contains("3 results"));
        assertTrue(read.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.output"))
                .contains("file body"));
        assertEquals("success", spanByName(spans, "agent.run").getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("h.outcome")));
    }

    private Flux<AgentEvent> modelCallStream(
            HAgentObservabilityMiddleware middleware, Agent agent, RuntimeContext runtimeContext) {
        return middleware.onModelCall(
                agent,
                runtimeContext,
                new ModelCallInput(
                        List.of(userMessage("整理来源")),
                        List.of(),
                        null,
                        model("claude-sonnet")),
                input -> Flux.just(
                        new TextBlockDeltaEvent("reply-1", "block-1", "thinking out loud"),
                        new ModelCallEndEvent("reply-1", ChatUsage.builder()
                                .inputTokens(300)
                                .outputTokens(600)
                                .build())));
    }

    private Flux<AgentEvent> actingStream(
            HAgentObservabilityMiddleware middleware, Agent agent, RuntimeContext runtimeContext) {
        return middleware.onActing(
                agent,
                runtimeContext,
                new ActingInput(List.of(
                        ToolUseBlock.builder().id("call-1").name("search")
                                .input(Map.of("query", "langfuse")).build(),
                        ToolUseBlock.builder().id("call-2").name("read_file")
                                .input(Map.of("path", "a.md")).build())),
                input -> Flux.just(
                        new ToolCallStartEvent("reply-1", "call-1", "search"),
                        new ToolResultTextDeltaEvent("reply-1", "call-1", "search", "3 results"),
                        new ToolResultEndEvent("reply-1", "call-1", "search", ToolResultState.SUCCESS),
                        new ToolCallStartEvent("reply-1", "call-2", "read_file"),
                        new ToolResultTextDeltaEvent("reply-1", "call-2", "read_file", "file body"),
                        new ToolResultEndEvent("reply-1", "call-2", "read_file", ToolResultState.SUCCESS)));
    }

    private AgentExecutionStart executionStart() {
        return new AgentExecutionStart(
                "agent.run", "session-1", 1L, "harness-agent", "as-1", "CHAT", "run-1",
                List.of("agentscope"), Map.of(),
                SemanticContent.ofMessages(List.of(SemanticMessage.of("user", "整理来源"))));
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

    private static SpanData spanByName(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found: " + name + " in " + spans));
    }
}
