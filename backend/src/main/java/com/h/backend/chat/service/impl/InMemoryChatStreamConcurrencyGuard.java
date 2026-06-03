package com.h.backend.chat.service.impl;

import com.h.backend.chat.config.ChatStreamProperties;
import com.h.backend.chat.service.ChatStreamConcurrencyGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class InMemoryChatStreamConcurrencyGuard implements ChatStreamConcurrencyGuard {

    private static final String SESSION_BUSY_MESSAGE = "当前会话正在处理中";
    private static final String SYSTEM_BUSY_MESSAGE = "当前系统繁忙，请稍后再试";

    private final Object monitor = new Object();
    private final Set<String> activeSessions = new HashSet<>();
    private final Map<Long, Integer> activeUsers = new HashMap<>();
    private final int maxConcurrentPerUser;
    private final int maxConcurrentGlobal;
    private int activeGlobal;

    @Autowired
    public InMemoryChatStreamConcurrencyGuard(ChatStreamProperties properties) {
        this(properties.getMaxConcurrentPerUser(), properties.getMaxConcurrentGlobal());
    }

    public InMemoryChatStreamConcurrencyGuard(int maxConcurrentPerUser, int maxConcurrentGlobal) {
        this.maxConcurrentPerUser = maxConcurrentPerUser;
        this.maxConcurrentGlobal = maxConcurrentGlobal;
    }

    @Override
    public Permit tryAcquire(String sessionId, Long userId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        synchronized (monitor) {
            if (activeSessions.contains(sessionId)) {
                return rejected(SESSION_BUSY_MESSAGE);
            }
            if (activeUsers.getOrDefault(userId, 0) >= maxConcurrentPerUser) {
                return rejected(SYSTEM_BUSY_MESSAGE);
            }
            if (activeGlobal >= maxConcurrentGlobal) {
                return rejected(SYSTEM_BUSY_MESSAGE);
            }

            activeSessions.add(sessionId);
            activeUsers.merge(userId, 1, Integer::sum);
            activeGlobal++;
            return new AcquiredPermit(sessionId, userId);
        }
    }

    private Permit rejected(String message) {
        return new RejectedPermit(message);
    }

    private void release(String sessionId, Long userId) {
        synchronized (monitor) {
            activeSessions.remove(sessionId);
            activeUsers.computeIfPresent(userId, (key, count) -> count > 1 ? count - 1 : null);
            activeGlobal--;
        }
    }

    private final class AcquiredPermit implements Permit {

        private final String sessionId;
        private final Long userId;
        private boolean released;

        private AcquiredPermit(String sessionId, Long userId) {
            this.sessionId = sessionId;
            this.userId = userId;
        }

        @Override
        public boolean acquired() {
            return true;
        }

        @Override
        public String message() {
            return "";
        }

        @Override
        public void release() {
            synchronized (monitor) {
                if (released) {
                    return;
                }
                released = true;
            }
            InMemoryChatStreamConcurrencyGuard.this.release(sessionId, userId);
        }
    }

    private record RejectedPermit(String message) implements Permit {

        @Override
        public boolean acquired() {
            return false;
        }

        @Override
        public void release() {
        }
    }
}
