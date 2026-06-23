package com.h.backend.chat.agent;

import com.h.backend.chat.dto.AgentStepPayloadDto;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.agentic.scope.AgenticScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentStepListenerTest {

    @Test
    void inheritedBySubagentsReturnsTrue() {
        AgentStepListener listener = new AgentStepListener(new AgentStepEventBridge());

        assertTrue(listener.inheritedBySubagents());
    }

    @Test
    void emitsRunningCompletedAndFailedPayloadsWithDepthAndSequence() {
        AgentStepEventBridge bridge = new AgentStepEventBridge();
        AgentStepListener listener = new AgentStepListener(bridge);
        List<AgentStepPayloadDto> events = new CopyOnWriteArrayList<>();
        AgenticScope scope = scope("memory-1");
        AgentInstance root = agent("root", "Root", AgenticSystemTopology.SEQUENCE, null);
        AgentInstance child = agent("child", "Child", AgenticSystemTopology.AI_AGENT, root);

        bridge.register("memory-1", events::add);

        listener.beforeAgentInvocation(new AgentRequest(scope, child, Map.of()));
        listener.afterAgentInvocation(new AgentResponse(scope, child, Map.of(), "ok", null, null));
        listener.onAgentInvocationError(new AgentInvocationError(scope, child, Map.of(), new RuntimeException("boom")));

        assertEquals(3, events.size());
        assertPayload(events.get(0), "running", 1, 1);
        assertPayload(events.get(1), "completed", 1, 2);
        assertPayload(events.get(2), "failed", 1, 3);
    }

    private static void assertPayload(AgentStepPayloadDto payload, String status, int depth, int sequence) {
        assertNull(payload.runId());
        assertNull(payload.agentId());
        assertEquals("child:" + sequence, payload.invocationId());
        assertEquals("child", payload.nodeId());
        assertEquals("Child", payload.nodeName());
        assertEquals("AI_AGENT", payload.topology());
        assertEquals(status, payload.status());
        assertEquals(depth, payload.depth());
        assertEquals(sequence, payload.sequence());
    }

    private static AgenticScope scope(Object memoryId) {
        AgenticScope scope = mock(AgenticScope.class);
        when(scope.memoryId()).thenReturn(memoryId);
        return scope;
    }

    private static AgentInstance agent(
            String agentId,
            String name,
            AgenticSystemTopology topology,
            AgentInstance parent
    ) {
        AgentInstance agent = mock(AgentInstance.class);
        when(agent.agentId()).thenReturn(agentId);
        when(agent.name()).thenReturn(name);
        when(agent.topology()).thenReturn(topology);
        when(agent.parent()).thenReturn(parent);
        return agent;
    }
}
