package com.h.otheragents.a2a.config;

import com.h.otheragents.a2a.infrastructure.ai.Agents;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class A2AAgentConfig {

    @Bean
    public Agents.CreativeWriter creativeWriter(ChatModel chatModel) {
        return AgenticServices.agentBuilder(Agents.CreativeWriter.class)
                .chatModel(chatModel)
                .outputKey("story")
                .build();
    }

    @Bean
    public Agents.AudienceEditor audienceEditor(ChatModel chatModel) {
        return AgenticServices.agentBuilder(Agents.AudienceEditor.class)
                .chatModel(chatModel)
                .outputKey("story")
                .build();
    }

    @Bean
    public Agents.StyleEditor styleEditor(ChatModel chatModel) {
        return AgenticServices.agentBuilder(Agents.StyleEditor.class)
                .chatModel(chatModel)
                .outputKey("story")
                .build();
    }
}
