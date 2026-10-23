package com.h.backend.chat.domain.agent;

import com.h.backend.chat.interfaces.dto.AgentStepPayloadDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

@Component
public class AgentStepEventBridge {

    private final ConcurrentMap<String, Consumer<AgentStepPayloadDto>> emitters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Consumer<ChatStreamEvent>> tools = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Runnable> checkpoints = new ConcurrentHashMap<>();

    public void registerExecution(String memoryId, Consumer<ChatStreamEvent> emitter, Runnable checkpoint) {
        tools.put(memoryId, emitter);
        checkpoints.put(memoryId, checkpoint);
    }

    public void checkpoint(Object memoryId) {
        if (memoryId == null) return;
        Runnable checkpoint = checkpoints.get(String.valueOf(memoryId));
        if (checkpoint != null) checkpoint.run();
    }

    public void emitTool(Object memoryId, ChatStreamEvent event) {
        if (memoryId == null) return;
        Consumer<ChatStreamEvent> emitter = tools.get(String.valueOf(memoryId));
        if (emitter != null) emitter.accept(event);
    }

    public void register(String memoryId, Consumer<AgentStepPayloadDto> emitter) {
        if (memoryId == null) {
            throw new IllegalArgumentException("memoryId must not be null");
        }
        emitters.put(memoryId, Objects.requireNonNull(emitter, "emitter must not be null"));
    }

    public void emit(Object memoryId, AgentStepPayloadDto payload) {
        if (memoryId == null) {
            return;
        }
        Consumer<AgentStepPayloadDto> emitter = emitters.get(String.valueOf(memoryId));
        if (emitter != null) {
            emitter.accept(payload);
        }
    }

    public void unregister(String memoryId) {
        if (memoryId == null) {
            throw new IllegalArgumentException("memoryId must not be null");
        }
        emitters.remove(memoryId);
        tools.remove(memoryId);
        checkpoints.remove(memoryId);
    }
}
