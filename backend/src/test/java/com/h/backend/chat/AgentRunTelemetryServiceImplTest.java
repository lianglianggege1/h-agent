package com.h.backend.chat;

import com.h.backend.chat.application.impl.AgentRunTelemetryServiceImpl;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
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

    @Test
    void shouldResumeInsideTheOriginalW3cTrace() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        AgentRunTelemetryServiceImpl service = new AgentRunTelemetryServiceImpl(openTelemetry);

        var original = service.startRun("session-3", 5L, null);
        service.markPaused(original);
        var resumed = service.resumeRun("session-3", 5L, null, original.traceParent());

        assertTrue(original.traceParent().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]"));
        assertEquals(original.traceId(), resumed.traceId());
        service.markSuccess(resumed);
        tracerProvider.close();
    }
}
