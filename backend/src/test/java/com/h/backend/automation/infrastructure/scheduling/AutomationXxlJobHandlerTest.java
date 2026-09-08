package com.h.backend.automation.infrastructure.scheduling;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutomationXxlJobHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void decodesStableTaskMarkerAndRevision() {
        AutomationXxlJobHandler.DispatchRequest request = AutomationXxlJobHandler.decode(
                "{\"marker\":\"automation:v1:task-7\",\"revision\":3}", objectMapper
        );

        assertEquals("task-7", request.taskId());
        assertEquals(3, request.taskRevision());
    }

    @Test
    void rejectsUnversionedExecutorParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> AutomationXxlJobHandler.decode("task-7", objectMapper));
    }
}
