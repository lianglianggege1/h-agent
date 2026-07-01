package com.h.otheragents.a2a.application;

import com.h.otheragents.a2a.domain.model.CreativeWritingDraft;
import com.h.otheragents.a2a.domain.model.CreativeWritingRequest;
import com.h.otheragents.a2a.domain.model.StoryEditRequest;
import com.h.otheragents.a2a.domain.service.LangChain4jStoryAgentService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CreativeWritingApplicationService {

    private final LangChain4jStoryAgentService storyAgentService;

    public CreativeWritingApplicationService(LangChain4jStoryAgentService storyAgentService) {
        this.storyAgentService = storyAgentService;
    }

    public CreativeWritingDraft writeDraft(List<String> prompts) {
        String topic = prompts == null || prompts.isEmpty() ? null : prompts.getFirst();
        return storyAgentService.writeDraft(new CreativeWritingRequest(topic));
    }

    public CreativeWritingDraft editForAudience(List<String> prompts) {
        return storyAgentService.editForAudience(editRequest(prompts));
    }

    public CreativeWritingDraft editForStyle(List<String> prompts) {
        return storyAgentService.editForStyle(editRequest(prompts));
    }

    private static StoryEditRequest editRequest(List<String> prompts) {
        if (prompts == null || prompts.size() < 2) {
            throw new IllegalArgumentException("story and edit instruction are required");
        }
        return new StoryEditRequest(prompts.get(0), prompts.get(1));
    }
}
