package com.h.backend.automation.infrastructure.execution;

import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationExecutionSessionRegistryTest {

    @Test
    void marksOnlyTheRegisteredAutomationSession() throws Exception {
        ChatMemoryIdFactory memoryIds = new ChatMemoryIdFactory();
        AutomationExecutionSessionRegistry registry = new AutomationExecutionSessionRegistry(memoryIds);
        String automationMemoryId = memoryIds.executionId(7L, "automation-session", "standard-chat");
        String normalMemoryId = memoryIds.executionId(7L, "normal-session", "standard-chat");

        try (AutoCloseable ignored = registry.register("automation-session")) {
            assertTrue(registry.isAutomation(automationMemoryId));
            assertFalse(registry.isAutomation(normalMemoryId));
        }
        assertFalse(registry.isAutomation(automationMemoryId));
    }
}
