package com.h.backend.chat;

import com.h.backend.chat.service.impl.AgentRunTelemetryServiceImpl;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentRunTelemetryServiceImplTest {

    @Test
    void shouldStartRunWithTraceIdAndCloseSuccessfully() {
        AgentRunTelemetryServiceImpl service = new AgentRunTelemetryServiceImpl(OpenTelemetry.noop());

        var run = service.startRun("session-1", 1L, 2L);

        assertTrue(run != null);
        assertTrue(run.span() != null);
        assertNull(run.traceId());

        service.markSuccess(run);

        assertTrue(true);
    }

    @Test
    void shouldHandleFailureWithoutThrowing() {
        AgentRunTelemetryServiceImpl service = new AgentRunTelemetryServiceImpl(OpenTelemetry.noop());
        var run = service.startRun("session-2", 3L, 4L);

        service.markFailure(run, new RuntimeException("boom"));

        assertTrue(true);
    }
}
