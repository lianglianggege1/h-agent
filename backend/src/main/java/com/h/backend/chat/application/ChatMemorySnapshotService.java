package com.h.backend.chat.application;

import com.h.backend.chat.domain.memory.ChatMemoryContext;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Optional;

public interface ChatMemorySnapshotService {

    Optional<List<ChatMessage>> loadSnapshot(ChatMemoryContext context);

    void cacheMemory(ChatMemoryContext context, List<ChatMessage> messages);

    void deleteHotMemory(ChatMemoryContext context);

    void scheduleFlush(ChatMemoryContext context, List<ChatMessage> messages, long version);

    void flushNow(String sessionId);

    void evict(String sessionId);

    void markResident(String sessionId);

    void restoreToRedis(String sessionId);

    void deleteSnapshot(String sessionId);

    void deleteSnapshot(ChatMemoryContext context);
}
