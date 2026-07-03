package com.h.backend.chat.infrastructure.ai.a2a;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.a2a.A2AContextId;
import dev.langchain4j.agentic.a2a.A2ATaskId;
import dev.langchain4j.service.V;

public final class A2ARemoteAgents {

    private A2ARemoteAgents() {
    }

    public interface CreativeWriter {

        @Agent(outputKey = "story")
        String generateStory(
                @V("topic") String topic,
                @A2AContextId @V("a2aContextId") String contextId,
                @A2ATaskId @V("creativeWriterTaskId") String taskId);
    }

    public interface AudienceEditor {

        @Agent(outputKey = "story")
        String editStory(
                @V("story") String story,
                @V("audience") String audience,
                @A2AContextId @V("a2aContextId") String contextId,
                @A2ATaskId @V("audienceEditorTaskId") String taskId);
    }

    public interface StyleEditor {

        @Agent(outputKey = "story")
        String editStory(
                @V("story") String story,
                @V("style") String style,
                @A2AContextId @V("a2aContextId") String contextId,
                @A2ATaskId @V("styleEditorTaskId") String taskId);
    }
}
