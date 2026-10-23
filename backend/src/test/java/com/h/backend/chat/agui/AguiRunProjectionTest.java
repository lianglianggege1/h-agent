package com.h.backend.chat.agui;

import com.h.backend.chat.application.agui.AguiRunProjection;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
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
        List<AguiEvent> events = new ArrayList<>();
        AguiRunProjection projection = new AguiRunProjection("thread", "run", events::add);
        projection.accept(new ChatStreamEvent("tool_start", "", null, Map.of("id", "tool-1", "name", "web_search")));
        projection.accept(new ChatStreamEvent("tool_result", "", null, Map.of("id", "tool-1", "name", "web_search", "result", "sources", "failed", false)));
        projection.accept(new ChatStreamEvent("chunk", "hello"));
        projection.accept(new ChatStreamEvent("done", ""));
        projection.closeMessages();
        projection.refreshProgress();
        assertTrue(events.stream().anyMatch(AguiEvent.ToolCallStart.class::isInstance));
        assertTrue(events.stream().anyMatch(AguiEvent.ToolCallResult.class::isInstance));
        assertEquals(1, events.stream().filter(AguiEvent.TextMessageStart.class::isInstance).count());
        assertEquals(1, events.stream().filter(AguiEvent.TextMessageEnd.class::isInstance).count());
        assertEquals("COMPLETED", projection.snapshot().get("status"));
        assertTrue(new AguiEventEncoder().encode(events.getFirst()).startsWith("data: "));
    }

    @Test
    void reusesOfficialHarnessConverterWithoutDuplicatingLegacyParentChunks() {
        List<AguiEvent> events = new ArrayList<>();
        AguiRunProjection projection = new AguiRunProjection("thread", "run", events::add);
        projection.accept(new ChatStreamEvent("agent_event", "", null, new TextBlockDeltaEvent("reply", "block", "hello")));
        projection.accept(new ChatStreamEvent("chunk", "hello"));
        TextBlockDeltaEvent child = new TextBlockDeltaEvent("child-reply", "child-block", "private child answer");
        child.withSource("child");
        projection.accept(new ChatStreamEvent("agent_event", "", null, child));
        List<AguiEvent.TextMessageContent> text = events.stream().filter(AguiEvent.TextMessageContent.class::isInstance)
                .map(AguiEvent.TextMessageContent.class::cast).toList();
        assertEquals(List.of("hello"), text.stream().map(AguiEvent.TextMessageContent::delta).toList());
    }

    @Test
    void retainsToolIdentityWithoutSerializingRawArgumentsOrMetadata() {
        List<AguiEvent> events = new ArrayList<>();
        AguiRunProjection projection = new AguiRunProjection("thread", "run", events::add);
        projection.accept(new ChatStreamEvent("agent_event", "", null, new ToolCallStartEvent("reply", "call", "execute")));
        ToolCallDeltaEvent arguments = new ToolCallDeltaEvent("reply", "call", "execute", "{\"apiKey\":\"secret-value\"}");
        arguments.withMetadataEntry("apiKey", "secret-value");
        projection.accept(new ChatStreamEvent("agent_event", "", null, arguments));
        String wire = events.stream().map(new AguiEventEncoder()::encode).reduce("", String::concat);
        assertTrue(wire.contains("TOOL_CALL_START"));
        assertFalse(wire.contains("secret-value"));
        assertFalse(wire.contains("rawEvent"));
    }
}
