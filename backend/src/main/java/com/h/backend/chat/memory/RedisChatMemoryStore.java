package com.h.backend.chat.memory;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.h.backend.chat.service.ChatMemorySnapshotService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private final ChatMemorySnapshotService chatMemorySnapshotService;

    public RedisChatMemoryStore(
            ChatMemorySnapshotService chatMemorySnapshotService
    ) {
        this.chatMemorySnapshotService = chatMemorySnapshotService;
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
        String value = String.valueOf(memoryId);
        String[] parts = value.split(":", 3);
        if (parts.length != 3 || StringUtils.isBlank(parts[2])) {
            throw new IllegalArgumentException("Invalid memoryId: " + value);
        }

        return new ChatMemoryContext(Long.valueOf(parts[0]), Long.valueOf(parts[1]), parts[2]);
    }
}
