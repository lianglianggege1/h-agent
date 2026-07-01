package com.h.backend.chat.infrastructure.ai.a2a;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import org.springframework.stereotype.Component;

@Component
public class RemoteCreativeWriterAgent implements A2AAgents.CreativeWriter {

    private final OtherAgentsA2AClient client;

    public RemoteCreativeWriterAgent(OtherAgentsA2AClient client) {
        this.client = client;
    }

    @Override
    @Agent(name = "远端创意写作者", description = "通过 A2A 调用 other-agents 生成故事初稿", outputKey = "story")
    public String generateStory(@V("topic") String topic) {
        return client.generateStory(topic);
    }
}
