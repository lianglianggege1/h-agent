package com.h.backend.chat.infrastructure.subagent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SubagentToolkitPolicyTest {
    public static class Tools {
        @Tool(name = "read_file", description = "read")
        public String read() { return "read"; }
        @Tool(name = "hidden_write", description = "write")
        public String write() { return "write"; }
    }

    @Test
    void hiddenGroupsAreRemovedFromCompleteRegistryWithoutMutatingParent() {
        var parent = new Toolkit();
        parent.createToolGroup("hidden", "hidden", false);
        parent.registration().tool(new Tools()).group("hidden").apply();
        assertThat(parent.getToolSchemas()).isEmpty();
        var child = parent.copy();

        SubagentToolkitPolicy.retainAllowed(child, Set.of("read_file"));
        assertThat(child.getToolNames()).containsExactly("read_file");
        child.updateToolGroups(java.util.List.of("hidden"), true);
        assertThat(child.getTool("hidden_write")).isNull();
        assertThat(parent.getToolNames()).containsExactlyInAnyOrder("read_file", "hidden_write");
    }

    @Test
    void scopedDenyAndAbsentParentToolsRestrictChild() {
        var parent = new Toolkit();
        parent.registerTool(new Tools());
        var permissions = PermissionContextState.builder().addDenyRule("read_file",
                new PermissionRule("read_file", "/private/**", PermissionBehavior.DENY, "test")).build();
        var state = AgentState.builder().build();
        state.setPermissionContext(permissions);
        var context = RuntimeContext.builder().agentState(state).build();

        assertThat(SubagentToolkitPolicy.parentDenied(parent, Set.of("read_file", "missing"), context,
                PermissionContextState.builder().build())).containsExactlyInAnyOrder("read_file", "missing");
    }

    @Test
    void emptyAllowlistRemovesToolsAddedByHarnessBuilder(@TempDir Path workspace) {
        try (HarnessAgent child = HarnessAgent.builder().name("leaf").model(mock(Model.class))
                .workspace(workspace).toolkit(new Toolkit()).disableMemoryTools().disableMemoryHooks()
                .disableSubagents().disableShellTool().disableToolsConfig().disableDynamicSkills()
                .disableDefaultWorkspaceSkills().disableSessionPersistence().build()) {
            assertThat(child.getToolkit().getToolNames()).contains("read_file", "write_file");
            SubagentToolkitPolicy.retainAllowed(child.getToolkit(), Set.of());
            assertThat(child.getToolkit().getToolNames()).isEmpty();
        }
    }
}
