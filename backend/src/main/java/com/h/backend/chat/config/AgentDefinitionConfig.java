package com.h.backend.chat.config;

import com.h.backend.chat.agent.AgentDefinition;
import com.h.backend.chat.agent.AgentRegistry;
import com.h.backend.chat.agent.AgentRuntimeType;
import com.h.backend.chat.ai.Agents;
import com.h.backend.chat.ai.HAssistant;
import com.h.backend.chat.ai.carrentalassistant.services.CarRentalAssistant;
import com.h.backend.chat.ai.carrentalassistant.services.ExportAssistant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AgentDefinitionConfig {

    @Bean
    public AgentDefinition standardChatAgent(HAssistant hAssistant) {
        return new AgentDefinition(
                AgentRegistry.STANDARD_CHAT_AGENT_ID,
                "普通聊天",
                "通用",
                List.of("聊天", "知识库"),
                "使用系统提示词和知识库的普通聊天助手",
                hAssistant,
                AgentRuntimeType.STANDARD_STREAMING_CHAT,
                true
        );
    }

    @Bean
    public AgentDefinition carRentalAgent(CarRentalAssistant carRentalAssistant) {
        return new AgentDefinition(
                "car-rental-assistant",
                "租车应急协助 Agent",
                "出行服务",
                List.of("拖车", "应急", "客户协助"),
                "面向租车客户的拖车与紧急事件协助",
                carRentalAssistant,
                AgentRuntimeType.AGENTIC_SYNC,
                true
        );
    }

    @Bean
    public AgentDefinition exportAgent(ExportAssistant exportAssistant) {
        return new AgentDefinition(
                "export-assistant",
                "专家智能体",
                "专家服务",
                List.of("专家"),
                "法律类、医疗类、技术类专家协助",
                exportAssistant,
                AgentRuntimeType.AGENTIC_SYNC,
                true
        );
    }

    @Bean
    public AgentDefinition storyChatAgent(Agents.StoryChatAgent storyChatAgent) {
        return new AgentDefinition(
                "story-chat-agent",
                "故事创作代理",
                "创作",
                List.of("故事创作"),
                "故事创作",
                storyChatAgent,
                AgentRuntimeType.AGENTIC_SYNC,
                true
        );
    }


}
