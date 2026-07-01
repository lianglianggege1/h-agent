package com.h.otheragents.a2a.application;

import com.h.otheragents.a2a.domain.model.CreativeWritingDraft;
import com.h.otheragents.a2a.domain.model.StoryAgentType;
import io.a2a.spec.Message;
import io.a2a.spec.TextPart;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class A2AMessageApplicationService {

    private final CreativeWritingApplicationService creativeWritingApplicationService;

    public A2AMessageApplicationService(CreativeWritingApplicationService creativeWritingApplicationService) {
        this.creativeWritingApplicationService = creativeWritingApplicationService;
    }

    public Message handleMessage(List<String> prompts, String agentType) {
        StoryAgentType type = StoryAgentType.fromMetadata(
                agentType,
                prompts == null ? 0 : prompts.size(),
                prompts == null || prompts.size() < 2 ? null : prompts.get(1)
        );
        CreativeWritingDraft draft = switch (type) {
            case CREATIVE_WRITER -> creativeWritingApplicationService.writeDraft(prompts);
            case AUDIENCE_EDITOR -> creativeWritingApplicationService.editForAudience(prompts);
            case STYLE_EDITOR -> creativeWritingApplicationService.editForStyle(prompts);
        };
        return new Message.Builder()
                .role(Message.Role.AGENT)
                .parts(new TextPart(draft.content()))
                .metadata(Map.of("provider", "other-agents", "agent", type.name()))
                .build();
    }
}
