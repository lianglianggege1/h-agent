package com.h.otheragents.a2a.config;

import com.h.otheragents.a2a.domain.service.RemoteStoryAgents;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class A2AAgentConfig {

    @Bean
    public RemoteStoryAgents.CreativeWriter creativeWriter(ChatModel chatModel) {
        return AgenticServices.agentBuilder(RemoteStoryAgents.CreativeWriter.class)
                .chatModel(chatModel)
                .outputKey("story")
                .build();
    }

    @Bean
    public RemoteStoryAgents.AudienceEditor audienceEditor(ChatModel chatModel) {
        return AgenticServices.agentBuilder(RemoteStoryAgents.AudienceEditor.class)
                .chatModel(chatModel)
                .outputKey("story")
                .build();
    }

    @Bean
    public RemoteStoryAgents.StyleEditor styleEditor(ChatModel chatModel) {
        return AgenticServices.agentBuilder(RemoteStoryAgents.StyleEditor.class)
                .chatModel(chatModel)
                .outputKey("story")
                .build();
    }
}
