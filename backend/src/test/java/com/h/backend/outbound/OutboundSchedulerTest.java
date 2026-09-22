package com.h.backend.outbound;

import com.h.backend.outbound.application.OutboundModule;
import com.h.backend.outbound.application.OutboundScheduler;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OutboundSchedulerTest {

    @Test
    void failedRecoveryBlocksAdvancementUntilARetrySucceeds() {
        OutboundModule module = mock(OutboundModule.class);
        doThrow(new IllegalStateException("database unavailable"))
                .doNothing()
                .when(module).recoverAfterRestart();
        OutboundScheduler scheduler = new OutboundScheduler(module);

        scheduler.onStartup();
        scheduler.tick();

        verify(module, times(2)).recoverAfterRestart();
        verify(module, never()).advance();

        scheduler.tick();
        verify(module).advance();
    }
}
