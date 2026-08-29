package com.h.backend.memory;

import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.backend.chat.domain.agent.ChatAgentExecutionCommand;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.memory.application.LongTermMemoryRuntime;
import com.h.backend.memory.application.SuccessfulTurnCommitter;
import com.h.backend.memory.domain.AgentMemoryPolicyCatalog;
import com.h.backend.memory.domain.CompletedTurn;
import com.h.backend.memory.domain.MemoryScopeKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.FluxSink;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuccessfulTurnCommitterTest {

    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final AgentRunService agentRunService = mock(AgentRunService.class);
    private final LongTermMemoryRuntime memoryRuntime = mock(LongTermMemoryRuntime.class);
    private final AgentMemoryPolicyCatalog policyCatalog = new AgentMemoryPolicyCatalog();
    private final SuccessfulTurnCommitter committer = new SuccessfulTurnCommitter(
            chatSessionService, agentRunService, memoryRuntime, policyCatalog);

    @Test
    @SuppressWarnings("unchecked")
    void commitPersistsAssistantMessageCompletesRunAndStagesCapture() {
        AgentDefinition standardAgent = standardAgent();
        ChatAgentExecutionCommand command = command(standardAgent, 11L);
        when(chatSessionService.appendAssistantMessage(7L, "session-actual", "答复"))
                .thenReturn(22L);
        when(chatSessionService.getOwnedMessage(7L, "session-actual", 22L))
                .thenReturn(assistantMessage());

        committer.commit(command, "答复");

        verify(agentRunService).completeRun(99L, 22L);
        ArgumentCaptor<CompletedTurn> turnCaptor = ArgumentCaptor.forClass(CompletedTurn.class);
        verify(memoryRuntime).stageCapture(turnCaptor.capture());
        CompletedTurn turn = turnCaptor.getValue();
        assertEquals(MemoryScopeKind.USER, turn.captureScope());
        assertEquals(11L, turn.userMessageId());
        assertEquals(22L, turn.assistantMessageId());
        assertEquals(7L, turn.context().userId());
        assertEquals(ChatAgentIds.STANDARD_CHAT, turn.context().logicalAgentId());
        assertEquals("session-root", turn.context().memoryRunId());
        assertEquals(99L, turn.context().sourceExecutionId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void commitWithoutUserMessageIdSkipsCapture() {
        ChatAgentExecutionCommand command = command(standardAgent(), null);
        when(chatSessionService.appendAssistantMessage(7L, "session-actual", "答复"))
                .thenReturn(22L);
        when(chatSessionService.getOwnedMessage(7L, "session-actual", 22L))
                .thenReturn(assistantMessage());

        committer.commit(command, "答复");

        verify(memoryRuntime, never()).stageCapture(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void commitWithCaptureDisabledAgentSkipsCapture() {
        AgentDefinition disabledAgent = new AgentDefinition(
                "unknown-agent", "未知", "通用", List.of(), "描述",
                new Object(), AgentRuntimeType.AGENTIC_SYNC, true);
        ChatAgentExecutionCommand command = command(disabledAgent, 11L);
        when(chatSessionService.appendAssistantMessage(7L, "session-actual", "答复"))
                .thenReturn(22L);
        when(chatSessionService.getOwnedMessage(7L, "session-actual", 22L))
                .thenReturn(assistantMessage());

        committer.commit(command, "答复");

        verify(memoryRuntime, never()).stageCapture(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void commitWithDomainAgentCapturesRunScope() {
        AgentDefinition domainAgent = new AgentDefinition(
                "export-assistant", "专家智能体", "专家服务", List.of("专家"), "专家",
                new Object(), AgentRuntimeType.AGENTIC_SYNC, true);
        ChatAgentExecutionCommand command = command(domainAgent, 11L);
        when(chatSessionService.appendAssistantMessage(7L, "session-actual", "答复"))
                .thenReturn(22L);
        when(chatSessionService.getOwnedMessage(7L, "session-actual", 22L))
                .thenReturn(assistantMessage());

        committer.commit(command, "答复");

        ArgumentCaptor<CompletedTurn> turnCaptor = ArgumentCaptor.forClass(CompletedTurn.class);
        verify(memoryRuntime).stageCapture(turnCaptor.capture());
        assertEquals(MemoryScopeKind.RUN, turnCaptor.getValue().captureScope());
    }

    private static AgentDefinition standardAgent() {
        return new AgentDefinition(
                ChatAgentIds.STANDARD_CHAT, "普通聊天", "通用", List.of("聊天"), "描述",
                new Object(), AgentRuntimeType.STANDARD_STREAMING_CHAT, true);
    }

    private ChatAgentExecutionCommand command(AgentDefinition agent, Long userMessageId) {
        return new ChatAgentExecutionCommand(
                mock(FluxSink.class),
                7L,
                null,
                "session-actual",
                "session-root",
                null,
                null,
                null,
                null,
                null,
                null,
                "用户消息",
                List.of(),
                "memory-id",
                agent,
                new AgentRunService.AgentRunHandle(99L),
                mock(AgentExecutionObservation.class),
                () -> {
                },
                userMessageId
        );
    }

    private static ChatSessionMessageDto assistantMessage() {
        return new ChatSessionMessageDto(
                "22", "assistant", "TEXT", "答复", null, List.of(), LocalDateTime.now());
    }
}
