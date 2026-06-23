package com.h.backend.chat.agent;

public interface ChatAgentExecutor {

    AgentRuntimeType runtimeType();

    void execute(ChatAgentExecutionCommand command);
}
