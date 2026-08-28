package com.h.backend.chat.infrastructure.config;

import com.h.backend.chat.domain.agent.AgenticChatMethodResolver;
import com.h.backend.chat.domain.agent.AgentRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/** 启动期验证所有 AGENTIC_SYNC 根 Agent 符合 chat(memoryId, message, InvocationParameters) 调用约定。 */
@Component
public class AgenticChatConventionValidator implements InitializingBean {

    private final AgentRegistry agentRegistry;

    public AgenticChatConventionValidator(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    @Override
    public void afterPropertiesSet() {
        AgenticChatMethodResolver.validateStartup(agentRegistry.listEnabled());
    }
}
