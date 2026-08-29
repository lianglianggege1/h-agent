package com.h.backend.observability.agentscope;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ExecutionObservationCarrier;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.semantic.SemanticBlock;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.TextBlock;
import com.h.agent.observability.semantic.ThinkingBlock;
import com.h.agent.observability.semantic.ToolCallBlock;
import com.h.agent.observability.semantic.ToolResultBlock;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * AgentScope 统一观测 middleware（设计 12.1 / 12.2）。
 *
 * <p>不使用 SDK 内置 {@code OtelTracingMiddleware}：名称、属性、语义化输入输出与
 * instrumentation scope 都需要按 H Agent schema 控制。Span 所有者：</p>
 * <ul>
 *   <li>{@code onAgent} — Agent Observation（输入 msgs / 输出最终回复）</li>
 *   <li>{@code onModelCall} — Generation Observation（含 usage）</li>
 *   <li>{@code onReasoning} / {@code onActing} — 不建批量 Span；单个 Tool Observation
 *       在 {@code onActing} 内按 toolCallId 独立开闭，事件流提供逐工具的输入与结果，
 *       不改变 Toolkit 注册顺序或权限元数据</li>
 * </ul>
 *
 * <p>上下文传播（设计 12.4）：根执行把 {@link ObservationContext} 作为类型化值放入
 * {@link RuntimeContext}；本 middleware 在 {@code onAgent} 把当前 Agent 的 observation
 * context 写回同一实例，嵌套模型调用、工具执行与 SDK 子 Agent 派生
 * （{@code RuntimeContext.builder(parent)} 会复制类型化值）都挂到本 Agent Span 下。
 * 跨 Reactor 调度沿用 SDK 的 {@link ContextPropagationOperator} 全局 hook。</p>
 */
public final class HAgentObservabilityMiddleware implements MiddlewareBase {

    private static volatile boolean reactorHookRegistered = false;

    private final AgentObservability observability;

    public HAgentObservabilityMiddleware(AgentObservability observability) {
        this.observability = observability;
        ensureReactorPropagationHook();
    }

    static void ensureReactorPropagationHook() {
        if (!reactorHookRegistered) {
            synchronized (HAgentObservabilityMiddleware.class) {
                if (!reactorHookRegistered) {
                    ContextPropagationOperator.builder().build().registerOnEachOperator();
                    reactorHookRegistered = true;
                }
            }
        }
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (!observability.enabled()) {
            return next.apply(input);
        }
        ObservationContext parent = resolveParent(ctx);
        AgentObservation observation = observability.span(
                ObservationSpec.of("agent." + safeName(agent.getName()), HObsKind.AGENT, "agentscope",
                        Map.of(HAttrs.AGENT_ID, agentId(agent))),
                parent);
        observation.input(AgentScopeSemantic.fromMsgs(input.msgs()));
        if (ctx != null) {
            ctx.put(ObservationContext.class, observation.context());
        }
        AtomicReference<Msg> result = new AtomicReference<>();
        StringBuilder text = new StringBuilder();
        Flux<AgentEvent> stream = next.apply(input)
                .doOnNext(event -> {
                    if (!isOwn(event)) {
                        return;
                    }
                    if (event instanceof AgentResultEvent resultEvent) {
                        result.set(resultEvent.getResult());
                    } else if (event instanceof TextBlockDeltaEvent delta
                            && delta.getDelta() != null) {
                        text.append(delta.getDelta());
                    }
                })
                .doOnComplete(() -> {
                    observation.output(agentOutput(result.get(), text.toString()));
                    observation.succeed();
                })
                .doOnError(observation::fail)
                .doOnCancel(() -> observation.cancel("cancelled"));
        return runWithObservationContext(stream, observation.context());
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (!observability.enabled()) {
            return next.apply(input);
        }
        ObservationContext parent = resolveParent(ctx);
        String modelName = input.model() != null ? input.model().getModelName() : "unknown";
        AgentObservation observation = observability.span(
                ObservationSpec.of("gen_ai." + safeName(modelName), HObsKind.GENERATION, "agentscope",
                        Map.of(HAttrs.GEN_AI_REQUEST_MODEL, modelName)),
                parent);
        observation.input(AgentScopeSemantic.fromMsgs(input.messages()));
        if (input.tools() != null && !input.tools().isEmpty()) {
            observation.attribute("gen_ai.request.tool_count", String.valueOf(input.tools().size()));
        }
        List<SemanticBlock> outputBlocks = new ArrayList<>();
        List<ToolCallStartEvent> toolCalls = new ArrayList<>();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        Flux<AgentEvent> stream = next.apply(input)
                .doOnNext(event -> {
                    if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                        outputBlocks.add(new TextBlock(delta.getDelta()));
                    } else if (event instanceof ThinkingBlockDeltaEvent delta
                            && delta.getDelta() != null) {
                        outputBlocks.add(new ThinkingBlock(delta.getDelta()));
                    } else if (event instanceof ToolCallStartEvent toolCall) {
                        toolCalls.add(toolCall);
                    } else if (event instanceof ModelCallEndEvent end
                            && end.getUsage() != null) {
                        usage.set(end.getUsage());
                    }
                })
                .doOnComplete(() -> {
                    recordUsage(observation, usage.get());
                    observation.output(generationOutput(outputBlocks, toolCalls));
                    observation.succeed();
                })
                .doOnError(observation::fail)
                .doOnCancel(() -> observation.cancel("cancelled"));
        return runWithObservationContext(stream, observation.context());
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (!observability.enabled() || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        ObservationContext parent = resolveParent(ctx);
        // 一个工具调用一个 Observation；toolCallId 是 SDK 稳定身份，事件流据此回填结果。
        Map<String, ActiveToolObservation> active = new ConcurrentHashMap<>();
        for (ToolUseBlock toolCall : input.toolCalls()) {
            AgentObservation observation = observability.span(
                    ObservationSpec.of("tool." + safeName(toolCall.getName()), HObsKind.TOOL,
                            "agentscope", Map.of(HAttrs.TOOL_NAME, toolCall.getName())),
                    parent);
            observation.input(SemanticContent.ofBlocks(List.of(AgentScopeSemantic.toolCall(toolCall))));
            active.put(toolCall.getId(), new ActiveToolObservation(
                    toolCall.getId(), observation, new StringBuilder()));
        }
        return next.apply(input)
                .doOnNext(event -> {
                    if (event instanceof ToolResultTextDeltaEvent delta && delta.getDelta() != null) {
                        ActiveToolObservation tool = active.get(delta.getToolCallId());
                        if (tool != null) {
                            tool.result().append(delta.getDelta());
                        }
                    } else if (event instanceof ToolResultEndEvent end) {
                        ActiveToolObservation tool = active.remove(end.getToolCallId());
                        if (tool != null) {
                            closeToolObservation(tool, end.getToolCallName(), end.getState());
                        }
                    }
                })
                .doOnError(error -> active.values().forEach(tool -> tool.observation().fail(error)))
                .doOnComplete(() -> active.values().forEach(tool ->
                        closeToolObservation(tool, null, null)))
                .doOnCancel(() -> active.values().forEach(tool ->
                        tool.observation().cancel("cancelled")));
    }

    private static void closeToolObservation(
            ActiveToolObservation tool, String toolName, ToolResultState state) {
        String name = toolName != null ? toolName : "unknown";
        boolean failed = state == ToolResultState.ERROR
                || state == ToolResultState.DENIED
                || state == ToolResultState.INTERRUPTED;
        tool.observation().output(SemanticContent.ofBlocks(List.of(new ToolResultBlock(
                tool.toolCallId(), name, tool.result().toString(), failed))));
        if (failed) {
            tool.observation().fail(new IllegalStateException(
                    "tool execution " + (state != null ? state.name().toLowerCase() : "failed")));
        } else {
            tool.observation().succeed();
        }
    }

    private static void recordUsage(AgentObservation observation, ChatUsage usage) {
        if (usage == null) {
            return;
        }
        observation.usage(usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens());
    }

    private static SemanticContent agentOutput(Msg result, String fallbackText) {
        if (result != null) {
            return AgentScopeSemantic.fromMsg(result);
        }
        if (fallbackText == null || fallbackText.isBlank()) {
            return null;
        }
        return SemanticContent.ofBlocks(List.of(new TextBlock(fallbackText)));
    }

    private static SemanticContent generationOutput(
            List<SemanticBlock> outputBlocks, List<ToolCallStartEvent> toolCalls) {
        if (outputBlocks.isEmpty() && toolCalls.isEmpty()) {
            return null;
        }
        List<SemanticBlock> blocks = new ArrayList<>(outputBlocks.size() + toolCalls.size());
        blocks.addAll(outputBlocks);
        for (ToolCallStartEvent toolCall : toolCalls) {
            blocks.add(new ToolCallBlock(toolCall.getToolCallId(), toolCall.getToolCallName(), null));
        }
        return SemanticContent.ofBlocks(blocks);
    }

    private ObservationContext resolveParent(RuntimeContext ctx) {
        if (ctx != null) {
            // 阶段载体（设计 7.3）优先：PRIMARY 沿用当前 Agent span，MAINTENANCE 延迟
            // 创建并返回 Maintenance trace 根；无载体时保持既有类型化上下文语义。
            ExecutionObservationCarrier carrier = ctx.get(ExecutionObservationCarrier.class);
            ObservationContext currentAgent = ctx.get(ObservationContext.class);
            if (carrier != null) {
                return carrier.parentForNewObservation(currentAgent);
            }
            if (currentAgent != null) {
                return currentAgent;
            }
        }
        return observability.currentContext();
    }

    private static Flux<AgentEvent> runWithObservationContext(
            Flux<AgentEvent> stream, ObservationContext context) {
        Context otelContext = context != null ? context.otelContext() : null;
        if (otelContext == null) {
            return stream;
        }
        return ContextPropagationOperator.runWithContext(stream, otelContext);
    }

    private static boolean isOwn(AgentEvent event) {
        String source = event.getSource();
        return source == null || source.isBlank();
    }

    private static String agentId(Agent agent) {
        if (agent == null) {
            return "unknown";
        }
        return agent.getAgentId() != null ? agent.getAgentId() : agent.getName();
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        return name.trim().replaceAll("\\s+", "-");
    }

    private record ActiveToolObservation(
            String toolCallId, AgentObservation observation, StringBuilder result) {
    }
}
