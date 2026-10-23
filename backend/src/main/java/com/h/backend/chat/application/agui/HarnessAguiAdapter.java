package com.h.backend.chat.application.agui;

import com.agui.community.core.event.Event;
import com.agui.community.core.serialization.Serializer;
import com.agui.community.spring.server.core.JacksonSerializer;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;

import java.util.List;

/** AgentScope types stay inside this adapter; the application emits community SDK events. */
final class HarnessAguiAdapter {
    private static final Serializer SERIALIZER = new JacksonSerializer();
    private final AguiEventEncoder encoder = new AguiEventEncoder();
    private final AgentEventConverterRegistry converters = new AgentEventConverterRegistry();
    private final AguiStreamContext context;

    HarnessAguiAdapter(String threadId, String runId) {
        context = new AguiStreamContext(threadId, runId, AguiAdapterConfig.builder()
                .emitToolCallArgs(false).enableReasoning(false)
                .baseEventPropertiesEnricherEnabled(false).build());
    }

    List<Event> accept(AgentEvent source) {
        // Authorized binary deliveries use the product resource channel.
        if (source instanceof io.agentscope.core.event.ToolResultDataDeltaEvent) return List.of();
        return convert(converters.convert(source, context));
    }

    List<Event> finish() { return convert(context.finishPendingEvents()); }

    private List<Event> convert(List<AguiEvent> events) {
        return events.stream()
                // The product commit/approval boundary owns run completion.
                .filter(event -> !(event instanceof AguiEvent.RunStarted || event instanceof AguiEvent.RunFinished
                        || event instanceof AguiEvent.Raw || event instanceof AguiEvent.Custom))
                .map(event -> SERIALIZER.deserialize(encoder.encodeToJson(event), Event.class)).toList();
    }
}
