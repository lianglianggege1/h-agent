package com.h.backend.chat.domain.agent;

import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.interfaces.dto.HarnessAgentEventPayload;
import io.agentscope.core.event.DataBlockDeltaEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessEventMapperTest {

    @Test
    void shouldPreserveAgentScopeEventSemanticsInVersionedHarnessEnvelope() {
        HarnessEventMapper mapper = new HarnessEventMapper();
        TextBlockDeltaEvent sourceEvent = new TextBlockDeltaEvent(
                "event-1",
                "2026-08-10T10:00:00Z",
                "reply-1",
                "block-1",
                "child answer"
        );
        sourceEvent.withSource("parent-session/general-purpose");
        sourceEvent.withMetadataEntry("taskId", "task-9");
        sourceEvent.withMetadataEntry("apiKey", "must-not-reach-browser");

        ChatStreamEvent mapped = mapper.map(55L, 7L, sourceEvent);

        assertEquals("harness_event", mapped.type());
        HarnessAgentEventPayload payload = (HarnessAgentEventPayload) mapped.payload();
        assertEquals("harness.agent-event", payload.schema());
        assertEquals(3, payload.schemaVersion());
        assertEquals("2.0.1", payload.sdkVersion());
        assertEquals("55", payload.runId());
        assertEquals(7L, payload.sequence());
        assertEquals("event-1", payload.eventId());
        assertEquals("TEXT_BLOCK_DELTA", payload.eventType());
        assertEquals("MODEL_OUTPUT", payload.kind());
        assertEquals("DELTA", payload.phase());
        assertEquals("PRIMARY", payload.importance());
        assertEquals("SUBAGENT", payload.source().scope());
        assertEquals("parent-session/general-purpose", payload.source().path());
        assertEquals("reply-1", payload.correlation().get("replyId"));
        assertEquals("block-1", payload.correlation().get("blockId"));
        assertEquals("child answer", payload.data().get("delta"));
        assertEquals("task-9", payload.metadata().get("taskId"));
        assertEquals(false, payload.metadata().containsKey("apiKey"));
    }

    @Test
    void shouldAttachResolvedProductSessionToChildDelta() {
        HarnessEventMapper mapper = new HarnessEventMapper();
        TextBlockDeltaEvent sourceEvent = new TextBlockDeltaEvent(
                "event-child-delta",
                "2026-08-14T09:39:21Z",
                "reply-child",
                "block-child",
                "第一段"
        );
        sourceEvent.withSource("parent-session/general-purpose");

        HarnessAgentEventPayload payload = (HarnessAgentEventPayload) mapper
                .map(55L, 9L, sourceEvent, null, "child-session", null)
                .payload();

        assertEquals("child-session", payload.data().get("agentSessionId"));
        assertEquals("第一段", payload.data().get("delta"));
    }

    @Test
    void shouldExposeProductSessionWithoutLeakingGatewayHandle() {
        HarnessEventMapper mapper = new HarnessEventMapper();
        SubagentExposedEvent sourceEvent = new SubagentExposedEvent(
                "subagent-7",
                "general-purpose",
                "child-session-7",
                "资料整理"
        );

        HarnessAgentEventPayload payload = (HarnessAgentEventPayload) mapper
                .map(55L, 8L, sourceEvent, "parent-agent-session")
                .payload();

        assertEquals("SUBAGENT", payload.kind());
        assertEquals("child-session-7", payload.data().get("sessionId"));
        assertEquals(false, payload.data().containsKey("subagentId"));
        assertEquals(false, payload.metadata().containsKey("subagentId"));
        assertEquals("parent-agent-session", payload.data().get("parentSessionId"));
    }

    @Test
    void shouldKeepToolArgumentEventsWhileMarkingSensitiveFieldsAsOmitted() {
        HarnessEventMapper mapper = new HarnessEventMapper();
        ToolCallDeltaEvent sourceEvent = new ToolCallDeltaEvent(
                "reply-1",
                "tool-1",
                "execute",
                "{\"secret\":\"must-not-reach-browser\"}"
        );
        sourceEvent.withMetadataEntry("apiKey", "must-not-reach-browser");

        HarnessAgentEventPayload payload = (HarnessAgentEventPayload) mapper.map(55L, 9L, sourceEvent).payload();

        assertEquals("TOOL_CALL_DELTA", payload.eventType());
        assertEquals("ACTION", payload.kind());
        assertEquals("DELTA", payload.phase());
        assertEquals("execute", payload.data().get("toolCallName"));
        assertEquals(List.of("data.delta"), payload.omittedFields());
        assertTrue(payload.metadata().isEmpty());
    }

    @Test
    void shouldKeepDataBlockEventsWhenTheirLargePayloadIsOmitted() {
        HarnessEventMapper mapper = new HarnessEventMapper();
        DataBlockDeltaEvent sourceEvent = new DataBlockDeltaEvent(
                "reply-1",
                "block-1",
                "base64-or-binary-content"
        );

        HarnessAgentEventPayload payload = (HarnessAgentEventPayload) mapper.map(55L, 10L, sourceEvent).payload();

        assertEquals("DATA_BLOCK_DELTA", payload.eventType());
        assertEquals("DATA", payload.kind());
        assertEquals("DELTA", payload.phase());
        assertEquals(List.of("data.delta"), payload.omittedFields());
        assertTrue(payload.data().isEmpty());
    }
}
