package com.h.backend.automation.infrastructure.execution;

import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标记当前由无人值守自动化驱动的聊天会话。LangChain4j 的动态 ToolProvider
 * 据此返回空工具集，直到产品具备可冻结、可审计的任务级能力授权。
 */
@Component
public class AutomationExecutionSessionRegistry {

    private final ChatMemoryIdFactory memoryIdFactory;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    public AutomationExecutionSessionRegistry(ChatMemoryIdFactory memoryIdFactory) {
        this.memoryIdFactory = memoryIdFactory;
    }

    public Registration register(String sessionId) {
        sessions.add(sessionId);
        return new Registration(sessionId);
    }

    public boolean isAutomation(Object memoryId) {
        try {
            return sessions.contains(memoryIdFactory.parse(memoryId).sessionId());
        } catch (RuntimeException invalidMemoryId) {
            return false;
        }
    }

    public final class Registration implements AutoCloseable {
        private final String sessionId;
        private boolean closed;

        private Registration(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void close() {
            if (!closed) {
                sessions.remove(sessionId);
                closed = true;
            }
        }
    }
}
