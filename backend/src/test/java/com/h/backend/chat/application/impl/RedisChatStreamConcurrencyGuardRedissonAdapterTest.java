package com.h.backend.chat.application.impl;

import org.junit.jupiter.api.Test;
import org.redisson.api.RPermitExpirableSemaphore;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisChatStreamConcurrencyGuardRedissonAdapterTest {

    @Test
    void shouldAcquireImmediatelyWithFiniteLeaseInsteadOfWaitingWithPermanentLease() throws Exception {
        RPermitExpirableSemaphore semaphore = mock(RPermitExpirableSemaphore.class);
        when(semaphore.tryAcquire(0L, 180_000L, TimeUnit.MILLISECONDS)).thenReturn("permit-1");
        var adapter = new RedisChatStreamConcurrencyGuard.RedissonExpirableSemaphore(semaphore);

        String permitId = adapter.tryAcquire(180_000L, TimeUnit.MILLISECONDS);

        assertEquals("permit-1", permitId);
        verify(semaphore).tryAcquire(0L, 180_000L, TimeUnit.MILLISECONDS);
    }
}
