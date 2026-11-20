package com.h.backend.chat.domain.agent;

import com.h.backend.memory.application.AgentMemoryContext;

/** 产品聊天执行命令到可信记忆身份的映射（能力层不依赖聊天执行模型）。 */
public final class AgentMemoryContexts {

    public static AgentMemoryContext from(ChatAgentExecutionCommand command) {
        return new AgentMemoryContext(command.userId(), command.rootSessionId(), command.sessionId(),
                command.agent().agentId(), command.agent().agentId(), command.userMessageId(),
                command.runHandle().id().toString());
    }

    private AgentMemoryContexts() {
    }
}