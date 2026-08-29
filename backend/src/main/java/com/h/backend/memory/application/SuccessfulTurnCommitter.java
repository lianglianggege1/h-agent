package com.h.backend.memory.application;

import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.domain.agent.ChatAgentExecutionCommand;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.memory.domain.AgentMemoryPolicy;
import com.h.backend.memory.domain.AgentMemoryPolicyCatalog;
import com.h.backend.memory.domain.CompletedTurn;
import com.h.backend.memory.domain.MemoryInvocationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成功 turn 提交器：assistant message、agent run success 与 memory capture outbox
 * 在同一 PostgreSQL 事务内完成。遥测状态与 SSE 在事务成功后由调用方更新；
 * Mem0 HTTP 永远不参与本地事务。
 */
@Service
public class SuccessfulTurnCommitter {

    private final ChatSessionService chatSessionService;
    private final AgentRunService agentRunService;
    private final LongTermMemoryRuntime memoryRuntime;
    private final AgentMemoryPolicyCatalog policyCatalog;

    public SuccessfulTurnCommitter(ChatSessionService chatSessionService,
                                   AgentRunService agentRunService,
                                   LongTermMemoryRuntime memoryRuntime,
                                   AgentMemoryPolicyCatalog policyCatalog) {
        this.chatSessionService = chatSessionService;
        this.agentRunService = agentRunService;
        this.memoryRuntime = memoryRuntime;
        this.policyCatalog = policyCatalog;
    }

    @Transactional
    public ChatSessionMessageDto commit(ChatAgentExecutionCommand command, String assistantReply) {
        Long assistantMessageId = chatSessionService.appendAssistantMessage(
                command.userId(),
                command.sessionId(),
                assistantReply
        );
        ChatSessionMessageDto assistantMessage = chatSessionService.getOwnedMessage(
                command.userId(),
                command.sessionId(),
                assistantMessageId
        );
        agentRunService.completeRun(command.runHandle().id(), assistantMessageId);
        stageCaptureIfConfigured(command, assistantMessageId);
        return assistantMessage;
    }

    private void stageCaptureIfConfigured(ChatAgentExecutionCommand command, Long assistantMessageId) {
        String logicalAgentId = command.agent().agentId();
        AgentMemoryPolicy policy = policyCatalog.policyOf(logicalAgentId);
        if (!policy.automaticCaptureEnabled() || command.userMessageId() == null) {
            return;
        }
        MemoryInvocationContext context = new MemoryInvocationContext(
                command.userId(),
                logicalAgentId,
                command.rootSessionId(),
                command.runHandle().id(),
                command.sessionId(),
                command.resolvedPromptId()
        );
        memoryRuntime.stageCapture(new CompletedTurn(
                context,
                command.userMessageId(),
                assistantMessageId,
                policy.automaticCaptureScope()
        ));
    }
}
