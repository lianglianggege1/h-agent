package com.h.backend.chat.agent;

import com.h.backend.chat.dto.AgentStepPayloadDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStepEventBridgeTest {

    @Test
    void routesEventsByMemoryId() {
        AgentStepEventBridge bridge = new AgentStepEventBridge();
        List<AgentStepPayloadDto> first = new CopyOnWriteArrayList<>();
        List<AgentStepPayloadDto> second = new CopyOnWriteArrayList<>();

        bridge.register("m1", first::add);
        bridge.register("m2", second::add);

        AgentStepPayloadDto payload = new AgentStepPayloadDto(
                "r1",
                "car",
                "i1",
                "n1",
                "Node",
                "AI_AGENT",
                "running",
                1,
                1
        );
        bridge.emit("m1", payload);

        assertEquals(List.of(payload), first);
        assertTrue(second.isEmpty());
    }

    @Test
    void routesEventsByStringValueOfMemoryId() {
        AgentStepEventBridge bridge = new AgentStepEventBridge();
        List<AgentStepPayloadDto> events = new CopyOnWriteArrayList<>();
        Object memoryId = 42L;
        AgentStepPayloadDto payload = new AgentStepPayloadDto(
                null,
                null,
                "i1",
                "n1",
                "Node",
                "AI_AGENT",
                "running",
                0,
                1
        );

        bridge.register("42", events::add);
        bridge.emit(memoryId, payload);

        assertEquals(List.of(payload), events);
    }

    @Test
    void unregisterStopsEvents() {
        AgentStepEventBridge bridge = new AgentStepEventBridge();
        List<AgentStepPayloadDto> events = new CopyOnWriteArrayList<>();

        bridge.register("m1", events::add);
        bridge.unregister("m1");
        bridge.emit("m1", new AgentStepPayloadDto(
                "r1",
                "car",
                "i1",
                "n1",
                "Node",
                "AI_AGENT",
                "running",
                1,
                1
        ));

        assertTrue(events.isEmpty());
    }
}
