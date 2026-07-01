package com.h.backend.chat.infrastructure.ai.a2a;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import org.springframework.stereotype.Component;

@Component
public class RemoteStyleEditorAgent implements A2AAgents.StyleEditor {

    private final OtherAgentsA2AClient client;

    public RemoteStyleEditorAgent(OtherAgentsA2AClient client) {
        this.client = client;
    }

    @Override
    @Agent(name = "远端风格编辑器", description = "通过 A2A 调用 other-agents 调整故事文风", outputKey = "story")
    public String editStory(@V("story") String story, @V("style") String style) {
        return client.editForStyle(story, style);
    }
}
