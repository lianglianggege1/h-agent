package com.h.backend.memory;

import com.h.backend.memory.application.AgentMemory;
import com.h.backend.memory.infrastructure.MemoryExecutionContextResolver;
import com.h.backend.memory.infrastructure.agentscope.MemoryTools;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HarnessNativeMemoryCoexistenceTest {
    @TempDir Path workspace;
    @Test void nativeMemoryAndSessionToolsCoexistWithMem0WithoutNameCollisions() {
        var module = mock(AgentMemory.class);
        var toolkit = new Toolkit();
        new MemoryTools(module, mock(MemoryExecutionContextResolver.class)).register(toolkit);
        try (var agent = HarnessAgent.builder().name("memory-coexistence")
                .model(mock(Model.class)).toolkit(toolkit).workspace(workspace)
                .disableSubagents().disableShellTool().skillsEnabled(false)
                .disableDynamicSkills().disableDefaultWorkspaceSkills().build()) {
            var tools = agent.getToolkit();
            for (String name : new String[]{"memory_search", "memory_get", "memory_save",
                    "session_search", "session_list", "session_history", "mem0_search", "mem0_get", "mem0_save"}) {
                assertNotNull(tools.getTool(name), "Missing tool: " + name);
            }
            var nativeGet = (Map<?,?>) tools.getTool("memory_get").getParameters().get("properties");
            var mem0Get = (Map<?,?>) tools.getTool("mem0_get").getParameters().get("properties");
            assertFalse(nativeGet.containsKey("memoryId"));
            assertTrue(mem0Get.containsKey("memoryId"));
            verifyNoInteractions(module);
        }
    }
}
