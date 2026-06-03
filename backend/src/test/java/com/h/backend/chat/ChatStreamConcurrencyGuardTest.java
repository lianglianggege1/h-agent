package com.h.backend.chat;

import com.h.backend.chat.service.ChatStreamConcurrencyGuard;
import com.h.backend.chat.service.impl.InMemoryChatStreamConcurrencyGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamConcurrencyGuardTest {

    @Test
    void shouldRejectSecondRunForSameSession() {
        InMemoryChatStreamConcurrencyGuard guard = new InMemoryChatStreamConcurrencyGuard(2, 100);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-1", 1L);

        assertTrue(first.acquired());
        assertFalse(second.acquired());
        assertEquals("当前会话正在处理中", second.message());
        first.release();
    }

    @Test
    void shouldRejectWhenUserLimitExceeded() {
        InMemoryChatStreamConcurrencyGuard guard = new InMemoryChatStreamConcurrencyGuard(1, 100);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-2", 1L);

        assertTrue(first.acquired());
        assertFalse(second.acquired());
        assertEquals("当前系统繁忙，请稍后再试", second.message());
        first.release();
    }

    @Test
    void shouldRejectWhenGlobalLimitExceeded() {
        InMemoryChatStreamConcurrencyGuard guard = new InMemoryChatStreamConcurrencyGuard(10, 1);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-2", 2L);

        assertTrue(first.acquired());
        assertFalse(second.acquired());
        assertEquals("当前系统繁忙，请稍后再试", second.message());
        first.release();
    }

    @Test
    void shouldAcquireAgainAfterRelease() {
        InMemoryChatStreamConcurrencyGuard guard = new InMemoryChatStreamConcurrencyGuard(1, 1);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        first.release();
        first.release();

        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-1", 1L);

        assertTrue(first.acquired());
        assertTrue(second.acquired());
        second.release();
    }
}
