package com.h.backend.chat.infrastructure.ai.a2a;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import org.springframework.stereotype.Component;

@Component
public class RemoteAudienceEditorAgent implements A2AAgents.AudienceEditor {

    private final OtherAgentsA2AClient client;

    public RemoteAudienceEditorAgent(OtherAgentsA2AClient client) {
        this.client = client;
    }

    @Override
    @Agent(name = "远端受众编辑器", description = "通过 A2A 调用 other-agents 修改故事适配指定受众群体", outputKey = "story")
    public String editStory(@V("story") String story, @V("audience") String audience) {
        return client.editForAudience(story, audience);
    }
}
