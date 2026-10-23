package com.h.backend.chat.agui;

import com.h.backend.chat.application.agui.AguiRunProjection;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.agui.community.server.SseEventEncoder;
import com.agui.community.spring.server.core.JacksonSerializer;
import com.agui.community.core.event.Event;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AguiRunProjectionTest {
    @Test
    void mapsLangchainCallbacksToStandardToolAndTextEvents() {
        List<Event> events = new ArrayList<>();
        AguiRunProjection projection = new AguiRunProjection("thread", "run", events::add);
        projection.accept(new ChatStreamEvent("tool_start", "", null, Map.of("id", "tool-1", "name", "web_search")));
        projection.accept(new ChatStreamEvent("tool_result", "", null, Map.of("id", "tool-1", "name", "web_search", "result", "sources", "failed", false)));
        projection.accept(new ChatStreamEvent("chunk", "hello"));
        projection.accept(new ChatStreamEvent("done", ""));
        projection.closeMessages();
        projection.refreshProgress();
        assertTrue(events.stream().anyMatch(com.agui.community.core.event.ToolCallStartEvent.class::isInstance));
        assertTrue(events.stream().anyMatch(com.agui.community.core.event.ToolCallResultEvent.class::isInstance));
        assertEquals(1, events.stream().filter(com.agui.community.core.event.TextMessageStartEvent.class::isInstance).count());
        assertEquals(1, events.stream().filter(com.agui.community.core.event.TextMessageEndEvent.class::isInstance).count());
        assertEquals("COMPLETED", projection.snapshot().get("status"));
        assertTrue(new SseEventEncoder(new JacksonSerializer()).encode(events.getFirst()).startsWith("data: "));
    }

    @Test
    void reusesOfficialHarnessConverterWithoutDuplicatingLegacyParentChunks() {
        List<Event> events = new ArrayList<>();
        AguiRunProjection projection = new AguiRunProjection("thread", "run", events::add);
        projection.accept(new ChatStreamEvent("agent_event", "", null, new TextBlockDeltaEvent("reply", "block", "hello")));
        projection.accept(new ChatStreamEvent("chunk", "hello"));
        TextBlockDeltaEvent child = new TextBlockDeltaEvent("child-reply", "child-block", "private child answer");
        child.withSource("child");
        projection.accept(new ChatStreamEvent("agent_event", "", null, child));
        List<com.agui.community.core.event.TextMessageContentEvent> text = events.stream().filter(com.agui.community.core.event.TextMessageContentEvent.class::isInstance)
                .map(com.agui.community.core.event.TextMessageContentEvent.class::cast).toList();
        assertEquals(List.of("hello"), text.stream().map(com.agui.community.core.event.TextMessageContentEvent::delta).toList());
    }

    @Test
    void retainsToolIdentityWithoutSerializingRawArgumentsOrMetadata() {
        List<Event> events = new ArrayList<>();
        AguiRunProjection projection = new AguiRunProjection("thread", "run", events::add);
        projection.accept(new ChatStreamEvent("agent_event", "", null, new ToolCallStartEvent("reply", "call", "execute")));
        ToolCallDeltaEvent arguments = new ToolCallDeltaEvent("reply", "call", "execute", "{\"apiKey\":\"secret-value\"}");
        arguments.withMetadataEntry("apiKey", "secret-value");
        projection.accept(new ChatStreamEvent("agent_event", "", null, arguments));
        String wire = events.stream().map(new SseEventEncoder(new JacksonSerializer())::encode).reduce("", String::concat);
        assertTrue(wire.contains("TOOL_CALL_START"));
        assertFalse(wire.contains("secret-value"));
        assertFalse(wire.contains("rawEvent"));
    }
}
