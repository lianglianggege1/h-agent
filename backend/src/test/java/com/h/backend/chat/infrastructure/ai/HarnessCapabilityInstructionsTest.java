package com.h.backend.chat.infrastructure.ai;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HarnessCapabilityInstructionsTest {
    public static class KnowledgeSearch {
        @Tool(name = "knowledge_search", description = "search")
        public String search() { return "result"; }
    }

    @Test
    void promptsFollowCallScopedGroupsInsteadOfSharedToolkitFlags() {
        var toolkit = new Toolkit();
        toolkit.createToolGroup("knowledge", "knowledge", false);
        toolkit.registration().tool(new KnowledgeSearch()).group("knowledge").apply();
        var agent = mock(ReActAgent.class);
        when(agent.getToolkit()).thenReturn(toolkit);
        var a = AgentState.builder().build();
        a.getToolContext().setActivatedGroups(List.of("knowledge"));
        var b = AgentState.builder().build();
        b.getToolContext().setActivatedGroups(List.of());

        assertThat(HarnessCapabilityInstructions.append(agent,
                RuntimeContext.builder().agentState(a).build(), "role", "knowledge"))
                .contains("knowledge_search").doesNotContain("knowledge_save");
        assertThat(HarnessCapabilityInstructions.append(agent,
                RuntimeContext.builder().agentState(b).build(), "role", "knowledge"))
                .isEqualTo("role");
        assertThat(toolkit.getToolSchemas()).isEmpty();
    }
}
