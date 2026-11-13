package com.h.backend.chat.infrastructure.ai;

import com.h.backend.chat.application.capability.AgentPromptComposer;
import com.h.backend.chat.application.capability.CapabilityCatalog;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.List;

/** Request-only guidance follows the tools actually supplied by static and dynamic providers. */
public final class CapabilityRequestInstructions {
    private CapabilityRequestInstructions() {}

    public static ChatRequest apply(ChatRequest request) {
        var tools = request.toolSpecifications();
        if (tools == null || tools.isEmpty()) return request;
        var names = tools.stream().map(dev.langchain4j.agent.tool.ToolSpecification::name).toList();
        String instructions = AgentPromptComposer.capabilities(CapabilityCatalog.Runtime.STANDARD_CHAT,
                names, List.of("video", "document", "automation", "memory", "knowledge"));
        var messages = new ArrayList<>(request.messages());
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage system) {
                messages.set(i, SystemMessage.from(system.text() + "\n\n" + instructions));
                return request.toBuilder().messages(messages).build();
            }
        }
        messages.addFirst(SystemMessage.from(instructions));
        return request.toBuilder().messages(messages).build();
    }
}
