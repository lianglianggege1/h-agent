package com.h.backend.chat;

import com.h.backend.chat.memory.ChatMemoryContext;
import com.h.backend.chat.memory.RedisChatMemoryStore;
import com.h.backend.chat.service.ChatMemorySnapshotService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisChatMemoryStoreTest {

    @Test
    void shouldParseStandardThreePartMemoryId() {
        ChatMemorySnapshotService snapshotService = mock(ChatMemorySnapshotService.class);
        RedisChatMemoryStore store = new RedisChatMemoryStore(snapshotService);
        when(snapshotService.loadSnapshot(new ChatMemoryContext(1L, 22L, "session-1")))
                .thenReturn(Optional.of(List.of()));

        store.getMessages("1:22:session-1");

        verify(snapshotService).loadSnapshot(new ChatMemoryContext(1L, 22L, "session-1"));
    }

    @Test
    void shouldParseDomainAgentFourPartMemoryIdWithNullPrompt() {
        ChatMemorySnapshotService snapshotService = mock(ChatMemorySnapshotService.class);
        RedisChatMemoryStore store = new RedisChatMemoryStore(snapshotService);
        ArgumentCaptor<ChatMemoryContext> captor = ArgumentCaptor.forClass(ChatMemoryContext.class);
        when(snapshotService.loadSnapshot(captor.capture())).thenReturn(Optional.of(List.of()));

        store.getMessages("1:agent:car-rental-assistant:session-car");

        ChatMemoryContext context = captor.getValue();
        assertEquals(1L, context.userId());
        assertNull(context.promptId());
        assertEquals("session-car", context.sessionId());
    }
}
