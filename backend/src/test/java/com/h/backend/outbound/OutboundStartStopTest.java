package com.h.backend.outbound;

import com.h.backend.common.exception.BusinessException;
import com.h.backend.outbound.application.OutboundModule;
import com.h.backend.outbound.domain.Call;
import com.h.backend.outbound.domain.Task;
import com.h.backend.outbound.infrastructure.OutboundProperties;
import com.h.backend.outbound.infrastructure.OutboundStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboundStartStopTest {
    private OutboundStore store;
    private OutboundProperties properties;
    private OutboundModule module;

    @BeforeEach
    void setup() {
        store = mock(OutboundStore.class);
        properties = new OutboundProperties();
        properties.setEnabled(true);
        properties.setInternalToken("0123456789abcdef0123456789abcdef");
        properties.setMediaVerified(true);
        module = new OutboundModule(store, properties);
    }

    @SuppressWarnings("unchecked")
    private void mockLockedTask(Task task) {
        when(store.getTask(1L, 1L)).thenReturn(task);
        when(store.lockedTask(eq(1L), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, Function.class);
            return fn.apply(task);
        });
        when(store.transaction(any())).thenAnswer(inv -> {
            var fn = inv.getArgument(0, Supplier.class);
            return fn.get();
        });
    }

    @Test
    void startTask_readyTask_transitionsToRunning() {
        Task task = new Task();
        task.setId(1L);
        task.setStatus("READY");
        mockLockedTask(task);
        when(store.hasAnyActiveCall()).thenReturn(false);
        when(store.hasAnyRunningTask()).thenReturn(false);

        module.startTask(1L, 1L);

        assertEquals("RUNNING", task.getStatus());
        verify(store).saveTask(task);
    }

    @Test
    void startTask_alreadyRunning_isIdempotent() {
        Task task = new Task();
        task.setId(1L);
        task.setStatus("RUNNING");
        mockLockedTask(task);
        when(store.hasAnyActiveCall()).thenReturn(false);
        when(store.hasAnyRunningTask()).thenReturn(false);

        assertDoesNotThrow(() -> module.startTask(1L, 1L));
        verify(store, never()).saveTask(any());
    }

    @Test
    void startTask_concurrentRetrySeesRunningUnderLock_isIdempotent() {
        Task snapshot = new Task();
        snapshot.setId(1L);
        snapshot.setStatus("READY");
        Task locked = new Task();
        locked.setId(1L);
        locked.setStatus("RUNNING");
        when(store.getTask(1L, 1L)).thenReturn(snapshot);
        when(store.hasAnyActiveCall()).thenReturn(false);
        when(store.hasAnyRunningTask()).thenReturn(false);
        when(store.lockedTask(eq(1L), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, Function.class);
            return fn.apply(locked);
        });

        assertDoesNotThrow(() -> module.startTask(1L, 1L));
        verify(store, never()).saveTask(any());
    }

    @Test
    void startTask_hasActiveCall_throws() {
        Task task = new Task();
        task.setId(1L);
        task.setStatus("READY");
        mockLockedTask(task);
        when(store.hasAnyActiveCall()).thenReturn(true);

        assertThrows(BusinessException.class, () -> module.startTask(1L, 1L));
    }

    @Test
    void stopTask_runningTask_transitionsToStopped() {
        Task task = new Task();
        task.setId(1L);
        task.setStatus("RUNNING");
        mockLockedTask(task);

        module.stopTask(1L, 1L);

        assertEquals("STOPPED", task.getStatus());
        assertEquals("USER_STOPPED", task.getStatusReason());
        verify(store).saveTask(task);
        verify(store).cancelQueuedCalls(1L);
    }

    @Test
    void stopTask_concurrentRetrySeesStoppedUnderLock_hasNoNewSideEffect() {
        Task snapshot = new Task();
        snapshot.setId(1L);
        snapshot.setStatus("RUNNING");
        Task locked = new Task();
        locked.setId(1L);
        locked.setStatus("STOPPED");
        when(store.getTask(1L, 1L)).thenReturn(snapshot);
        when(store.lockedTask(eq(1L), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, Function.class);
            return fn.apply(locked);
        });

        assertDoesNotThrow(() -> module.stopTask(1L, 1L));
        verify(store, never()).saveTask(any());
        verify(store, never()).cancelQueuedCalls(anyLong());
    }

    @Test
    void stopTask_notRunning_throws() {
        Task task = new Task();
        task.setId(1L);
        task.setStatus("COMPLETED");
        mockLockedTask(task);

        assertThrows(BusinessException.class, () -> module.stopTask(1L, 1L));
    }

    @Test
    void startTask_outboundDisabled_throws() {
        properties.setEnabled(false);

        assertThrows(BusinessException.class, () -> module.startTask(1L, 1L));
    }

    @Test
    void startTask_mediaNotVerified_throwsBeforeStateChange() {
        properties.setMediaVerified(false);

        assertThrows(BusinessException.class, () -> module.startTask(1L, 1L));
        verify(store, never()).saveTask(any());
    }

    @Test
    void startTask_shortInternalToken_throwsBeforeStateChange() {
        properties.setInternalToken("short");

        assertThrows(BusinessException.class, () -> module.startTask(1L, 1L));
        verify(store, never()).saveTask(any());
    }
}
