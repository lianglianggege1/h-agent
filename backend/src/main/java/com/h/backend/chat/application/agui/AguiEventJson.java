package com.h.backend.chat.application.agui;

import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Map;

/** Compatibility for AgentScope 2.0.2's older extra base fields and AG-UI 1.x strict event schemas. */
public final class AguiEventJson {
    private final AguiEventEncoder encoder = new AguiEventEncoder();

    public String encode(AguiEvent event) {
        String encoded = encoder.encodeToJson(event);
        if (event instanceof AguiEvent.RunStarted || event instanceof AguiEvent.RunFinished) return encoded;
        Map<String, Object> fields = JsonUtils.getJsonCodec().fromJson(encoded, new TypeReference<>() { });
        fields.remove("threadId");
        fields.remove("runId");
        return JsonUtils.getJsonCodec().toJson(fields);
    }
}
