package com.h.otheragents.a2a.server;

import com.h.otheragents.a2a.export.A2AAgentExport;
import com.h.otheragents.a2a.export.A2AAgentExports;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LangChain4jAgentMethodInvokerTest {

    interface AudienceAgent {

        @Agent(outputKey = "story")
        String edit(@V("story") String story, @V("audience") String audience);
    }

    @Test
    void invokesAgentBeanWithTextPartsInVParameterOrder() {
        AudienceAgent bean = (story, audience) -> "edited:" + story + ":" + audience;
        A2AAgentExport export = A2AAgentExports.builder()
                .export("audience-editor", bean, AudienceAgent.class, "edit")
                .build()
                .list()
                .getFirst();

        A2AInvocationContext context = new A2AInvocationContext(
                "audience-editor",
                "context-1",
                "task-1",
                "user-1",
                "session-1",
                "memory-1"
        );

        String result = new LangChain4jAgentMethodInvoker().invoke(export, context, List.of("原故事", "儿童"));

        assertEquals("edited:原故事:儿童", result);
    }

    @Test
    void invocationContextReadsOptionalMetadata() {
        A2AInvocationContext context = A2AInvocationContext.fromMetadata(
                "creative-writer",
                "context-1",
                "task-1",
                Map.of("userId", "u1", "sessionId", "s1", "memoryId", "m1")
        );

        assertEquals("u1", context.userId());
        assertEquals("s1", context.sessionId());
        assertEquals("m1", context.memoryId());
    }

    @Test
    void memoryKeyFallsBackFromMemoryIdToSessionIdToContextId() {
        assertEquals("memory-1", new A2AInvocationContext("a", "context-1", "task-1", null, "session-1", "memory-1").memoryKey());
        assertEquals("session-1", new A2AInvocationContext("a", "context-1", "task-1", null, "session-1", null).memoryKey());
        assertEquals("context-1", new A2AInvocationContext("a", "context-1", "task-1", null, null, null).memoryKey());
    }
}
