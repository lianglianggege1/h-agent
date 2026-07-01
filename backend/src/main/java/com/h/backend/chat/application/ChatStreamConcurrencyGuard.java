package com.h.backend.chat.application;

public interface ChatStreamConcurrencyGuard {

    Permit tryAcquire(String sessionId, Long userId);

    interface Permit {
        boolean acquired();

        String message();

        void release();
    }
}
