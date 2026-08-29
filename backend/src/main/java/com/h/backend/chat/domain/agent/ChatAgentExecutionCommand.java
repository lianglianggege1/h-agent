package com.h.backend.chat.domain.agent;

import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;
import com.h.backend.chat.application.AgentRunService;
import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.backend.chat.domain.subagentdefinition.model.DefinitionBinding;
import reactor.core.publisher.FluxSink;

import java.util.List;

public record ChatAgentExecutionCommand(
        FluxSink<ChatStreamEvent> sink,
        Long userId,
        Long resolvedPromptId,
        /** 请求直接指定的实际 Agent Session ID，也是写消息、运行和并发锁身份。 */
        String sessionId,
        /** 所属顶级聊天会话；父请求时与 sessionId 相同。 */
        String rootSessionId,
        /** Gateway 内部子 Agent 句柄；父请求为空，不从 HTTP 请求接收。 */
        String gatewaySubagentId,
        /** 子 Agent 类型、直接父节点和不可覆盖的原始委托；父请求为空。 */
        String subagentAgentId,
        String subagentParentSessionId,
        /** 原始任务委托（assignment）文本  */
        String subagentAssignment,
        /** 用户直达子 Agent 时由服务端生成的执行代次；父请求为空。 */
        String subagentExecutionId,
        /** Catalog 子会话固定的定义版本；父请求与声明式子 Agent 为空。 */
        DefinitionBinding subagentDefinitionBinding,
        String userMessage,
        List<ChatMessageResourceUseDto> resources,
        String memoryId,
        AgentDefinition agent,
        AgentRunService.AgentRunHandle runHandle,
        AgentExecutionObservation observation,
        Runnable onTerminal
) {
    public ChatAgentExecutionCommand(
            FluxSink<ChatStreamEvent> sink,
            Long userId,
            Long resolvedPromptId,
            String sessionId,
            String userMessage,
            List<ChatMessageResourceUseDto> resources,
            String memoryId,
            AgentDefinition agent,
            AgentRunService.AgentRunHandle runHandle,
            AgentExecutionObservation observation,
            Runnable onTerminal
    ) {
        this(sink, userId, resolvedPromptId, sessionId, sessionId, null, null, null, null, null, null,
                userMessage, resources, memoryId,
                agent, runHandle, observation, onTerminal);
    }

    /** 兼容无 Catalog 绑定的旧全参构造。 */
    public ChatAgentExecutionCommand(
            FluxSink<ChatStreamEvent> sink,
            Long userId,
            Long resolvedPromptId,
            String sessionId,
            String rootSessionId,
            String gatewaySubagentId,
            String subagentAgentId,
            String subagentParentSessionId,
            String subagentAssignment,
            String subagentExecutionId,
            String userMessage,
            List<ChatMessageResourceUseDto> resources,
            String memoryId,
            AgentDefinition agent,
            AgentRunService.AgentRunHandle runHandle,
            AgentExecutionObservation observation,
            Runnable onTerminal
    ) {
        this(sink, userId, resolvedPromptId, sessionId, rootSessionId, gatewaySubagentId,
                subagentAgentId, subagentParentSessionId, subagentAssignment, subagentExecutionId,
                null, userMessage, resources, memoryId,
                agent, runHandle, observation, onTerminal);
    }
}
