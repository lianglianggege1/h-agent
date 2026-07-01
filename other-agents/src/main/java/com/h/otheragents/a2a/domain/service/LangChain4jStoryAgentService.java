package com.h.otheragents.a2a.domain.service;

import com.h.otheragents.a2a.domain.model.CreativeWritingDraft;
import com.h.otheragents.a2a.domain.model.CreativeWritingRequest;
import com.h.otheragents.a2a.domain.model.StoryEditRequest;
import org.springframework.stereotype.Service;

@Service
public class LangChain4jStoryAgentService implements CreativeWriter {

    private final RemoteStoryAgents.CreativeWriter creativeWriter;
    private final RemoteStoryAgents.AudienceEditor audienceEditor;
    private final RemoteStoryAgents.StyleEditor styleEditor;

    public LangChain4jStoryAgentService(
            RemoteStoryAgents.CreativeWriter creativeWriter,
            RemoteStoryAgents.AudienceEditor audienceEditor,
            RemoteStoryAgents.StyleEditor styleEditor
    ) {
        this.creativeWriter = creativeWriter;
        this.audienceEditor = audienceEditor;
        this.styleEditor = styleEditor;
    }

    @Override
    public CreativeWritingDraft writeDraft(CreativeWritingRequest request) {
        return new CreativeWritingDraft(creativeWriter.generateStory(request.topic()));
    }

    public CreativeWritingDraft editForAudience(StoryEditRequest request) {
        return new CreativeWritingDraft(audienceEditor.editStory(request.story(), request.instruction()));
    }

    public CreativeWritingDraft editForStyle(StoryEditRequest request) {
        return new CreativeWritingDraft(styleEditor.editStory(request.story(), request.instruction()));
    }
}
