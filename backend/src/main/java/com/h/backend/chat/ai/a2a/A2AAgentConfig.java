package com.h.backend.chat.ai.a2a;

import com.h.backend.chat.agent.AgentStepListener;
import dev.langchain4j.agentic.AgenticServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class A2AAgentConfig {

    @Bean
    public A2AStoryAssistant a2aStoryAssistant(
            AgentStepListener agentStepListener,
            RemoteCreativeWriterAgent remoteCreativeWriterAgent
    ) {
        return AgenticServices.sequenceBuilder(A2AStoryAssistant.class)
                .name("A2A故事协作助手")
                .description("由 backend 编排并通过 A2A 调用 other-agents 的故事协作示例")
                .listener(agentStepListener)
                .subAgents(
                        new StoryRequestParser(),
                        remoteCreativeWriterAgent,
                        new StoryResponseComposer()
                )
                .outputKey("response")
                .build();
    }
}
