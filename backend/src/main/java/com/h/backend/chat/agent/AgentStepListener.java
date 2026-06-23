package com.h.backend.chat.agent;

import com.h.backend.chat.dto.AgentStepPayloadDto;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.scope.AgenticScope;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AgentStepListener implements AgentListener {

    private final AgentStepEventBridge bridge;
    private final ConcurrentMap<InvocationKey, String> invocationIds = new ConcurrentHashMap<>();
    private final AtomicInteger eventSequence = new AtomicInteger();
    private final AtomicInteger invocationSequence = new AtomicInteger();

    public AgentStepListener(AgentStepEventBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest request) {
        InvocationKey key = key(request.agenticScope(), request.agent(), request.inputs());
        String invocationId = newInvocationId(request.agent());
        invocationIds.put(key, invocationId);
        bridge.emit(request.agenticScope().memoryId(), payload(request.agent(), "running", invocationId));
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        String invocationId = removeInvocationId(response.agenticScope(), response.agent(), response.inputs());
        bridge.emit(response.agenticScope().memoryId(), payload(response.agent(), "completed", invocationId));
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        String invocationId = removeInvocationId(error.agenticScope(), error.agent(), error.inputs());
        bridge.emit(error.agenticScope().memoryId(), payload(error.agent(), "failed", invocationId));
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }

    private AgentStepPayloadDto payload(AgentInstance agent, String status, String invocationId) {
        int next = eventSequence.incrementAndGet();
        String nodeId = agent.agentId();
        return new AgentStepPayloadDto(
                null,
                null,
                invocationId,
                nodeId,
                agent.name(),
                agent.topology() == null ? null : agent.topology().name(),
                status,
                depth(agent),
                next
        );
    }

    private String removeInvocationId(AgenticScope scope, AgentInstance agent, Map<String, Object> inputs) {
        String invocationId = invocationIds.remove(key(scope, agent, inputs));
        return invocationId == null ? newInvocationId(agent) : invocationId;
    }

    private InvocationKey key(AgenticScope scope, AgentInstance agent, Map<String, Object> inputs) {
        return new InvocationKey(scope, agent, inputs);
    }

    private String newInvocationId(AgentInstance agent) {
        return agent.agentId() + ":" + invocationSequence.incrementAndGet();
    }

    private int depth(AgentInstance agent) {
        int depth = 0;
        AgentInstance cursor = agent.parent();
        while (cursor != null) {
            depth++;
            cursor = cursor.parent();
        }
        return depth;
    }

    private static final class InvocationKey {
        private final AgenticScope scope;
        private final AgentInstance agent;
        private final Map<String, Object> inputs;
        private final int hash;

        private InvocationKey(AgenticScope scope, AgentInstance agent, Map<String, Object> inputs) {
            this.scope = scope;
            this.agent = agent;
            this.inputs = inputs;
            this.hash = 31 * (31 * System.identityHashCode(scope) + System.identityHashCode(agent))
                    + System.identityHashCode(inputs);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof InvocationKey that)) {
                return false;
            }
            return scope == that.scope && agent == that.agent && inputs == that.inputs;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
