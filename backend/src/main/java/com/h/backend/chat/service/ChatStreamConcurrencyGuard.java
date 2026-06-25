package com.h.backend.chat.service;

public interface ChatStreamConcurrencyGuard {

    Permit tryAcquire(String sessionId, Long userId);

    interface Permit {
        boolean acquired();

        String message();

        void renew();

        void release();
    }
}
