package com.h.backend.chat.infrastructure.memory;

import com.h.backend.chat.application.ChatMemorySnapshotService;
import com.h.backend.chat.domain.memory.ChatMemoryContext;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private final ChatMemorySnapshotService chatMemorySnapshotService;
    private final ChatMemoryIdFactory chatMemoryIdFactory;

    public RedisChatMemoryStore(
            ChatMemorySnapshotService chatMemorySnapshotService
    ) {
        this(chatMemorySnapshotService, new ChatMemoryIdFactory());
    }

    @Autowired
    public RedisChatMemoryStore(ChatMemorySnapshotService chatMemorySnapshotService, ChatMemoryIdFactory chatMemoryIdFactory) {
        this.chatMemorySnapshotService = chatMemorySnapshotService;
        this.chatMemoryIdFactory = chatMemoryIdFactory;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        ChatMemoryContext context = parseContext(memoryId);
        return chatMemorySnapshotService.loadSnapshot(context).orElse(List.of());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        chatMemorySnapshotService.cacheMemory(parseContext(memoryId), messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        chatMemorySnapshotService.deleteHotMemory(parseContext(memoryId));
    }

    private ChatMemoryContext parseContext(Object memoryId) {
        return chatMemoryIdFactory.parse(memoryId);
    }
}
