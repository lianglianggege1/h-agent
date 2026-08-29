package com.h.agent.observability.langchain4j;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ObservationScope;
import com.h.agent.observability.semantic.SemanticBlock;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.TextBlock;
import com.h.agent.observability.semantic.ToolCallBlock;
import com.h.agent.observability.semantic.ToolResultBlock;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.BeforeAgentToolExecution;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * LangChain4j AgentListener producing one agent observation per agent invocation, plus one
 * tool observation per tool execution via the framework's before/after tool callbacks.
 * <p>
 * Invocation identity uses the strong triple (AgenticScope, AgentInstance, inputs) -
 * identical to the product AgentStepListener - so reentrant and parallel calls never collide.
 * <p>
 * The agent observation is also opened as a thread-local scope for the duration of the
 * invocation, so model calls and dynamic tool provisioning started inside the agent body
 * are parented to the agent span instead of its parent. The framework fires before/after/error
 * and the agent body on one and the same thread (sync and async invocations alike).
 */
public final class ObservingAgentListener implements AgentListener {

    private final AgentObservability observability;
    private final ConcurrentMap<InvocationKey, ActiveInvocation> active = new ConcurrentHashMap<>();

    public ObservingAgentListener(AgentObservability observability) {
        this.observability = observability;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest request) {
        InvocationKey key = key(request.agenticScope(), request.agent(), request.inputs());
        AgentObservation observation = observability.span(
                ObservationSpec.of("agent." + safeName(agentName(request)), HObsKind.AGENT, "langchain4j",
                        Map.of(HAttrs.AGENT_ID, agentName(request))),
                observability.currentContext());
        observation.input(semanticInput(request.inputs()));
        ObservationScope scope = observability.scope(observation.context());
        active.put(key, new ActiveInvocation(observation, scope));
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        ActiveInvocation invocation = active.remove(key(response.agenticScope(), response.agent(), response.inputs()));
        if (invocation == null) {
            return;
        }
        try (ObservationScope scope = invocation.scope()) {
            invocation.observation().output(semanticOutput(response.output()));
            invocation.observation().succeed();
        }
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        ActiveInvocation invocation = active.remove(key(error.agenticScope(), error.agent(), error.inputs()));
        if (invocation == null) {
            return;
        }
        try (ObservationScope scope = invocation.scope()) {
            invocation.observation().fail(error.error());
        }
    }

    @Override
    public void beforeAgentToolExecution(BeforeAgentToolExecution execution) {
        ToolExecutionRequest request = execution.toolExecution().request();
        AgentObservation observation = observability.span(
                ObservationSpec.of("tool." + toolName(request), HObsKind.TOOL, "langchain4j",
                        Map.of(HAttrs.TOOL_NAME, toolName(request))),
                observability.currentContext());
        observation.input(SemanticContent.ofBlocks(List.of(
                new ToolCallBlock(request.id(), toolName(request), request.arguments()))));
        toolObservations.put(toolKey(request), observation);
    }

    @Override
    public void afterAgentToolExecution(AfterAgentToolExecution execution) {
        ToolExecutionRequest request = execution.toolExecution().request();
        AgentObservation observation = toolObservations.remove(toolKey(request));
        if (observation == null) {
            return;
        }
        boolean failed = execution.toolExecution().hasFailed();
        observation.output(SemanticContent.ofBlocks(List.of(new ToolResultBlock(
                request.id(), toolName(request), execution.toolExecution().result(), failed))));
        if (failed) {
            observation.fail(new IllegalStateException("tool execution failed"));
        } else {
            observation.succeed();
        }
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }

    private final ConcurrentMap<String, AgentObservation> toolObservations = new ConcurrentHashMap<>();

    private static String toolKey(ToolExecutionRequest request) {
        return request == null || request.id() == null ? String.valueOf(System.identityHashCode(request))
                : request.id();
    }

    private static String toolName(ToolExecutionRequest request) {
        return request == null || request.name() == null ? "unknown" : request.name();
    }

    private static String agentName(AgentRequest request) {
        return request.agentId() != null ? request.agentId() : request.agentName();
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        return name.trim().replaceAll("\\s+", "-");
    }

    private static SemanticContent semanticInput(Map<String, Object> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }
        List<SemanticBlock> blocks = inputs.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .limit(64)
                .map(entry -> new TextBlock(entry.getKey() + ": " + String.valueOf(entry.getValue())))
                .map(block -> (SemanticBlock) block)
                .toList();
        if (blocks.isEmpty()) {
            return null;
        }
        return SemanticContent.ofBlocks(blocks);
    }

    private static SemanticContent semanticOutput(Object output) {
        if (output == null) {
            return null;
        }
        return SemanticContent.ofBlocks(List.of(new TextBlock(String.valueOf(output))));
    }

    private record ActiveInvocation(AgentObservation observation, ObservationScope scope) {
    }

    private record InvocationKey(AgenticScope scope, AgentInstance agent, Map<String, Object> inputs) {
    }

    private static InvocationKey key(AgenticScope scope, AgentInstance agent, Map<String, Object> inputs) {
        return new InvocationKey(scope, agent, inputs);
    }
}
