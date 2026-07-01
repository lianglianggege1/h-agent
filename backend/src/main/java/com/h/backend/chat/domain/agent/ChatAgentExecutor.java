package com.h.backend.chat.domain.agent;

public interface ChatAgentExecutor {

    AgentRuntimeType runtimeType();

    void execute(ChatAgentExecutionCommand command);
}
