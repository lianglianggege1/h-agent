package com.h.backend.chat.application.impl;

import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.application.ChatResourceService;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import com.h.backend.common.exception.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class ChatResourceServiceImpl implements ChatResourceService {

    private final ChatMessageResourceMapper chatMessageResourceMapper;
    private final ResourceStorage resourceStorage;

    public ChatResourceServiceImpl(ChatMessageResourceMapper chatMessageResourceMapper, ResourceStorage resourceStorage) {
        this.chatMessageResourceMapper = chatMessageResourceMapper;
        this.resourceStorage = resourceStorage;
    }

    @Override
    public ResourceResponse openPreview(Long userId, String resourceId) {
        ChatMessageResourceEntity resource = requireOwnedResource(userId, resourceId);
        return new ResourceResponse(
                resourceStorage.open(resource.getStorageKey()),
                safeFileName(resource.getFileName()),
                false
        );
    }

    @Override
    public ResourceResponse openDownload(Long userId, String resourceId) {
        ChatMessageResourceEntity resource = requireOwnedResource(userId, resourceId);
        return new ResourceResponse(
                resourceStorage.open(resource.getStorageKey()),
                safeFileName(resource.getFileName()),
                true
        );
    }

    private ChatMessageResourceEntity requireOwnedResource(Long userId, String resourceId) {
        ChatMessageResourceEntity resource = chatMessageResourceMapper.selectByResourceId(resourceId);
        if (resource == null || !userId.equals(resource.getUserId())) {
            throw new BusinessException(40404, "资源不存在");
        }
        return resource;
    }

    private String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "generated-image.png";
        }
        return fileName.replaceAll("[\\r\\n\\\\/]", "_");
    }
}
