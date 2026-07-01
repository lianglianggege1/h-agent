package com.h.backend.chat.interfaces.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatStreamEventTest {

    @Test
    void existingTwoArgumentConstructorKeepsNullPayload() {
        ChatStreamEvent event = new ChatStreamEvent("chunk", "hello");

        assertEquals("chunk", event.type());
        assertEquals("hello", event.content());
        assertNull(event.message());
        assertNull(event.payload());
    }

    @Test
    void existingThreeArgumentConstructorKeepsNullPayload() {
        ChatSessionMessageDto message = new ChatSessionMessageDto(
                "m1",
                "assistant",
                "TEXT",
                "hello",
                null,
                null,
                null
        );

        ChatStreamEvent event = new ChatStreamEvent("done", "", message);

        assertEquals("done", event.type());
        assertEquals("", event.content());
        assertEquals(message, event.message());
        assertNull(event.payload());
    }

    @Test
    void supportsStructuredPayload() {
        Map<String, String> payload = Map.of("status", "running");

        ChatStreamEvent event = new ChatStreamEvent("agent_step", "正在执行", null, payload);

        assertEquals("agent_step", event.type());
        assertEquals("正在执行", event.content());
        assertNull(event.message());
        assertEquals(payload, event.payload());
    }
}
