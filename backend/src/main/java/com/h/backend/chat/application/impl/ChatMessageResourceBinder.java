package com.h.backend.chat.application.impl;

import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将已上传或历史资源绑定到一条已经持久化的聊天消息。
 *
 * <p>资源的会话归属由 {@code message_id -> chat_session_messages.session_id} 确定，
 * 本模块只维护消息关联，不保存重复的资源 {@code session_id}。</p>
 */
@Component
public class ChatMessageResourceBinder {

    private final ChatMessageResourceMapper resourceMapper;
    private final ObjectMapper objectMapper;

    public ChatMessageResourceBinder(
            ChatMessageResourceMapper resourceMapper,
            ObjectMapper objectMapper
    ) {
        this.resourceMapper = resourceMapper;
        this.objectMapper = objectMapper;
    }

    public void bind(Long userId, Long messageId, List<ChatMessageResourceUseDto> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        if (resourceMapper == null) {
            throw new IllegalStateException("ChatMessageResourceMapper is required to bind message resources");
        }
        for (ChatMessageResourceUseDto resourceUse : resources) {
            ChatMessageResourceEntity resource = resourceMapper.selectByResourceId(resourceUse.resourceId());
            if (resource == null || !userId.equals(resource.getUserId()) || resource.getMessageId() != null) {
                if (resource != null && userId.equals(resource.getUserId()) && resource.getMessageId() != null) {
                    resourceMapper.insert(copyForMessage(resource, messageId, resourceUse));
                }
                continue;
            }
            resourceMapper.bindMessage(
                    resourceUse.resourceId(),
                    userId,
                    messageId,
                    normalize(resourceUse.role(), "role"),
                    toMetadataJson(Map.of("source", normalize(resourceUse.source(), "source")))
            );
        }
    }

    private ChatMessageResourceEntity copyForMessage(
            ChatMessageResourceEntity original,
            Long messageId,
            ChatMessageResourceUseDto resourceUse
    ) {
        ChatMessageResourceEntity copy = new ChatMessageResourceEntity();
        copy.setId(UUID.randomUUID().toString());
        copy.setMessageId(messageId);
        copy.setUserId(original.getUserId());
        copy.setResourceType(original.getResourceType());
        copy.setResourceRole(normalize(resourceUse.role(), "role"));
        copy.setStorageType(original.getStorageType());
        copy.setStorageKey(original.getStorageKey());
        copy.setViewUrl(original.getViewUrl());
        copy.setDownloadUrl(original.getDownloadUrl());
        copy.setMimeType(original.getMimeType());
        copy.setFileName(original.getFileName());
        copy.setFileSize(original.getFileSize());
        copy.setWidth(original.getWidth());
        copy.setHeight(original.getHeight());
        copy.setMetadataJson(toMetadataJson(Map.of(
                "source", normalize(resourceUse.source(), "source"),
                "sourceResourceId", original.getId()
        )));
        copy.setCreatedAt(LocalDateTime.now());
        return copy;
    }

    private String normalize(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("resource " + fieldName + " is required");
        }
        return value.trim().toUpperCase();
    }

    private String toMetadataJson(Object metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException exception) {
            return null;
        }
    }
}
