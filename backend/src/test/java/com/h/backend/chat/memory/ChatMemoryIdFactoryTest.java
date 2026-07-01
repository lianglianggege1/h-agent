package com.h.backend.chat.domain.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatMemoryIdFactoryTest {

    @Test
    void buildsRootExecutionId() {
        ChatMemoryIdFactory factory = new ChatMemoryIdFactory();

        assertEquals(
                "exec:v2:user:1:session:s1:agent:car-rental-assistant",
                factory.executionId(1L, "s1", "car-rental-assistant")
        );
    }

    @Test
    void buildsScopedMemoryIdFromExecutionId() {
        ChatMemoryIdFactory factory = new ChatMemoryIdFactory();

        assertEquals(
                "mem:v2:user:1:session:s1:agent:car-rental-assistant:scope:customer-info-extractor",
                factory.scopedMemoryId(
                        "exec:v2:user:1:session:s1:agent:car-rental-assistant",
                        "customer-info-extractor"
                )
        );
    }

    @Test
    void parsesLegacyStandardMemoryId() {
        ChatMemoryIdFactory factory = new ChatMemoryIdFactory();

        ChatMemoryContext context = factory.parse("1:22:s1");

        assertEquals(1L, context.userId());
        assertEquals(22L, context.promptId());
        assertEquals("s1", context.sessionId());
        assertEquals("standard-chat", context.agentId());
        assertEquals("default", context.memoryScope());
    }

    @Test
    void parsesLegacyDomainAgentMemoryId() {
        ChatMemoryIdFactory factory = new ChatMemoryIdFactory();

        ChatMemoryContext context = factory.parse("1:agent:car-rental-assistant:s1");

        assertEquals(1L, context.userId());
        assertNull(context.promptId());
        assertEquals("s1", context.sessionId());
        assertEquals("car-rental-assistant", context.agentId());
        assertEquals("default", context.memoryScope());
    }

    @Test
    void parsesScopedMemoryId() {
        ChatMemoryIdFactory factory = new ChatMemoryIdFactory();

        ChatMemoryContext context = factory.parse(
                "mem:v2:user:1:session:s1:agent:car-rental-assistant:scope:customer-info-extractor"
        );

        assertEquals(1L, context.userId());
        assertNull(context.promptId());
        assertEquals("s1", context.sessionId());
        assertEquals("car-rental-assistant", context.agentId());
        assertEquals("customer-info-extractor", context.memoryScope());
    }
}
