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

    @Bean
    public com.h.otheragents.a2a.export.A2AAgentExports a2aAgentExports(
            Agents.CreativeWriter creativeWriter,
            Agents.AudienceEditor audienceEditor,
            Agents.StyleEditor styleEditor
    ) {
        return com.h.otheragents.a2a.export.A2AAgentExports.builder()
                .export("creative-writer", creativeWriter, Agents.CreativeWriter.class, "generateStory")
                .export("audience-editor", audienceEditor, Agents.AudienceEditor.class, "editStory")
                .export("style-editor", styleEditor, Agents.StyleEditor.class, "editStory")
                .build();
    }

    @Bean
    public com.h.otheragents.a2a.export.A2AAgentExportRegistry a2aAgentExportRegistry(
            com.h.otheragents.a2a.export.A2AAgentExports exports
    ) {
        return new com.h.otheragents.a2a.export.A2AAgentExportRegistry(exports);
    }

    @Bean
    public com.h.otheragents.a2a.server.A2AAgentServer a2aAgentServer(
            OtherAgentsA2AProperties properties,
            com.h.otheragents.a2a.export.A2AAgentExportRegistry registry
    ) {
        return com.h.otheragents.a2a.server.A2AAgentServer.create(
                properties,
                registry,
                new com.h.otheragents.a2a.server.LangChain4jAgentMethodInvoker(),
                new com.h.otheragents.a2a.server.A2AMessageMapper(),
                new com.h.otheragents.a2a.server.InMemoryA2ATaskStore()
        );
    }
}
