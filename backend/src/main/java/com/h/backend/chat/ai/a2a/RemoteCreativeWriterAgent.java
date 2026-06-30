package com.h.backend.chat.ai.a2a;

import com.h.backend.chat.config.OtherAgentsA2AProperties;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.service.V;
import org.springframework.stereotype.Component;

@Component
public class RemoteCreativeWriterAgent {

    private final OtherAgentsA2AProperties properties;
    private volatile RemoteCreativeWriter remoteCreativeWriter;

    public RemoteCreativeWriterAgent(OtherAgentsA2AProperties properties) {
        this.properties = properties;
    }

    @Agent(name = "远端创意写作者", description = "通过 A2A 调用 other-agents 生成故事初稿", outputKey = "draft")
    public String generateStory(@V("topic") String topic) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("other-agents A2A channel is disabled");
        }
        return remoteCreativeWriter().generateStory(topic);
    }

    private RemoteCreativeWriter remoteCreativeWriter() {
        RemoteCreativeWriter current = remoteCreativeWriter;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (remoteCreativeWriter == null) {
                remoteCreativeWriter = AgenticServices
                        .a2aBuilder(normalizedBaseUrl(), RemoteCreativeWriter.class)
                        .outputKey("draft")
                        .build();
            }
            return remoteCreativeWriter;
        }
    }

    private String normalizedBaseUrl() {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8082";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
