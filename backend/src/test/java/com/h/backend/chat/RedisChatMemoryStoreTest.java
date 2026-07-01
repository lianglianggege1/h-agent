package com.h.backend.chat;

import com.h.backend.chat.domain.memory.ChatMemoryContext;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.chat.infrastructure.memory.RedisChatMemoryStore;
import com.h.backend.chat.application.ChatMemorySnapshotService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisChatMemoryStoreTest {

    @Test
    void shouldBeCreatedBySpringContainer() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                RedisChatMemoryStore.class,
                ChatMemoryIdFactory.class,
                RedisChatMemoryStoreSpringConfig.class
        )) {
            RedisChatMemoryStore store = context.getBean(RedisChatMemoryStore.class);
            ChatMemorySnapshotService snapshotService = context.getBean(ChatMemorySnapshotService.class);

            store.getMessages("1:22:session-1");

            verify(snapshotService).loadSnapshot(new ChatMemoryContext(1L, 22L, "session-1"));
        }
    }

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

    @Test
    void shouldParseScopedV2MemoryId() {
        ChatMemorySnapshotService snapshotService = mock(ChatMemorySnapshotService.class);
        RedisChatMemoryStore store = new RedisChatMemoryStore(snapshotService);

        store.getMessages("mem:v2:user:1:session:s1:agent:car-rental-assistant:scope:customer-info-extractor");

        verify(snapshotService).loadSnapshot(new ChatMemoryContext(
                1L,
                null,
                "s1",
                "car-rental-assistant",
                "customer-info-extractor"
        ));
    }

    @Configuration
    static class RedisChatMemoryStoreSpringConfig {

        @Bean
        ChatMemorySnapshotService chatMemorySnapshotService() {
            ChatMemorySnapshotService snapshotService = mock(ChatMemorySnapshotService.class);
            when(snapshotService.loadSnapshot(new ChatMemoryContext(1L, 22L, "session-1")))
                    .thenReturn(Optional.of(List.of()));
            return snapshotService;
        }
    }
}
