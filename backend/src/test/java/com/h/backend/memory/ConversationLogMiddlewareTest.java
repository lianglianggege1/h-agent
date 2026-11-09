package com.h.backend.memory;

import com.h.backend.memory.infrastructure.agentscope.ConversationLogMiddleware;
import com.h.backend.chat.infrastructure.config.HarnessAgentConfig;
import io.agentscope.core.agent.*;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.message.*;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationLogMiddlewareTest {
    @TempDir Path directory;
    @Test void logsPersistWithoutAnyMemoryExtractionModel() throws Exception {
        var state=mock(AgentState.class);
        when(state.getContext()).thenReturn(List.of(Msg.builder().id("stable-msg").role(MsgRole.USER)
                .content(TextBlock.builder().text("source conversation").build()).build()));
        var agent=mock(Agent.class);when(agent.getName()).thenReturn("worker");
        try(var workspace=new WorkspaceManager(directory)) {
            var ctx=RuntimeContext.builder().userId("7").sessionId("root").agentState(state)
                    .put(WorkspaceManager.class,workspace).build();
            new ConversationLogMiddleware().onAgent(agent,ctx,null,ignored->Flux.empty()).collectList().block();
            Path file=workspace.resolveSessionContextFile(ctx,"worker","root");
            assertTrue(Files.readString(file).contains("source conversation"));
            assertFalse(Files.exists(directory.resolve("memory")),"Logging must not create memory ledgers");
            new ConversationLogMiddleware().onAgent(agent,ctx,null,ignored->Flux.empty()).collectList().block();
            assertEquals(1,Files.readString(file).lines().filter(line->line.contains("source conversation")).count());
        }
    }
    @Test void onlyOrdinaryHarnessDisablesCompactionMemoryFlush() {
        var config=new HarnessAgentConfig();
        assertFalse(config.directedMemoryCompactionConfig().isFlushBeforeCompact());
        assertTrue(config.directedMemoryCompactionConfig().isOffloadBeforeCompact());
        assertTrue(config.harnessCompactionConfig().isFlushBeforeCompact());
    }
}
