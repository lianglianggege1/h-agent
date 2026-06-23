package com.h.backend.chat.agent;

import com.h.backend.chat.dto.AgentStepPayloadDto;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.planner.AgentInstance;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AgentStepListener implements AgentListener {

    private final AgentStepEventBridge bridge;
    private final AtomicInteger sequence = new AtomicInteger();

    public AgentStepListener(AgentStepEventBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest request) {
        bridge.emit(request.agenticScope().memoryId(), payload(request.agent(), "running"));
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        bridge.emit(response.agenticScope().memoryId(), payload(response.agent(), "completed"));
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        bridge.emit(error.agenticScope().memoryId(), payload(error.agent(), "failed"));
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }

    private AgentStepPayloadDto payload(AgentInstance agent, String status) {
        int next = sequence.incrementAndGet();
        String nodeId = agent.agentId();
        return new AgentStepPayloadDto(
                null,
                null,
                nodeId + ":" + next,
                nodeId,
                agent.name(),
                agent.topology() == null ? null : agent.topology().name(),
                status,
                depth(agent),
                next
        );
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
}
