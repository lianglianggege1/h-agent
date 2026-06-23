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
        String[] parts = value.split(":", 4);
        if (parts.length == 3 && StringUtils.isNotBlank(parts[2])) {
            return new ChatMemoryContext(Long.valueOf(parts[0]), Long.valueOf(parts[1]), parts[2]);
        }
        if (parts.length == 4 && "agent".equals(parts[1]) && StringUtils.isNotBlank(parts[3])) {
            return new ChatMemoryContext(Long.valueOf(parts[0]), null, parts[3]);
        }
        if (parts.length == 4 && StringUtils.isNotBlank(parts[3])) {
            throw new IllegalArgumentException("Invalid memoryId: " + value);
        }
        throw new IllegalArgumentException("Invalid memoryId: " + value);
    }
}
