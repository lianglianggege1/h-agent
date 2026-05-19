package com.h.backend.chat.memory;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.entity.ChatSessionEntity;
import com.h.backend.chat.entity.ChatSessionMessageEntity;
import com.h.backend.chat.mapper.ChatSessionMapper;
import com.h.backend.chat.mapper.ChatSessionMessageMapper;
import com.h.backend.utils.RedisUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final long MEMORY_TTL_SECONDS = Duration.ofHours(24).getSeconds();
    private static final int MEMORY_WINDOW_SIZE = 10;

    private final RedisUtil redisUtil;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionMessageMapper chatSessionMessageMapper;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryStore(
            RedisUtil redisUtil,
            ChatSessionMapper chatSessionMapper,
            ChatSessionMessageMapper chatSessionMessageMapper,
            ObjectMapper objectMapper
    ) {
        this.redisUtil = redisUtil;
        this.chatSessionMapper = chatSessionMapper;
        this.chatSessionMessageMapper = chatSessionMessageMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = extractSessionId(memoryId);
        String payload = redisUtil.get(memoryKey(sessionId), String.class);
        if (StringUtils.isNotBlank(payload)) {
            return ChatMessageDeserializer.messagesFromJson(payload);
        }

        List<ChatMessage> restored = rebuildFromPostgres(sessionId);
        if (!restored.isEmpty()) {
            redisUtil.set(memoryKey(sessionId), ChatMessageSerializer.messagesToJson(restored), MEMORY_TTL_SECONDS);
        }
        return restored;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = extractSessionId(memoryId);
        redisUtil.set(memoryKey(sessionId), ChatMessageSerializer.messagesToJson(messages), MEMORY_TTL_SECONDS);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisUtil.delete(memoryKey(extractSessionId(memoryId)));
    }

    private List<ChatMessage> rebuildFromPostgres(String sessionId) {
        ChatSessionEntity session = chatSessionMapper.selectBySessionId(sessionId);
        if (session == null) {
            return List.of();
        }

        List<ChatSessionMessageEntity> rows = chatSessionMessageMapper.selectLatestBySessionRecordId(session.getId(), MEMORY_WINDOW_SIZE);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<ChatSessionMessageEntity> ordered = new ArrayList<>(rows);
        ordered.sort(java.util.Comparator.comparing(ChatSessionMessageEntity::getSequenceNo));
        List<ChatMessage> messages = new ArrayList<>(ordered.size());
        for (ChatSessionMessageEntity row : ordered) {
            ChatMessage parsed = parsePayload(row.getPayloadJson());
            if (parsed != null) {
                messages.add(parsed);
                continue;
            }
            messages.add(buildFallbackMessage(row));
        }
        return messages;
    }

    private ChatMessage parsePayload(String payloadJson) {
        if (StringUtils.isBlank(payloadJson)) {
            return null;
        }
        try {
            return ChatMessageDeserializer.messageFromJson(payloadJson);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ChatMessage buildFallbackMessage(ChatSessionMessageEntity row) {
        if ("assistant".equals(row.getRoleCode())) {
            return AiMessage.from(row.getContentText() == null ? "" : row.getContentText());
        }
        if ("system".equals(row.getRoleCode())) {
            return new SystemMessage(row.getContentText() == null ? "" : row.getContentText());
        }
        return UserMessage.from(row.getContentText() == null ? "" : row.getContentText());
    }

    private String extractSessionId(Object memoryId) {
        String value = String.valueOf(memoryId);
        String[] parts = value.split(":", 3);
        return parts.length == 3 ? parts[2] : value;
    }

    private String memoryKey(String sessionId) {
        return "chat:memory:" + sessionId;
    }
}
