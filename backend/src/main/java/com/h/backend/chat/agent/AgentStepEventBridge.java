package com.h.backend.chat.agent;

import com.h.backend.chat.dto.AgentStepPayloadDto;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

@Component
public class AgentStepEventBridge {

    private final ConcurrentMap<String, Consumer<AgentStepPayloadDto>> emitters = new ConcurrentHashMap<>();

    public void register(String memoryId, Consumer<AgentStepPayloadDto> emitter) {
        emitters.put(String.valueOf(memoryId), emitter);
    }

    public void emit(Object memoryId, AgentStepPayloadDto payload) {
        Consumer<AgentStepPayloadDto> emitter = emitters.get(String.valueOf(memoryId));
        if (emitter != null) {
            emitter.accept(payload);
        }
    }

    public void unregister(String memoryId) {
        emitters.remove(String.valueOf(memoryId));
    }
}
