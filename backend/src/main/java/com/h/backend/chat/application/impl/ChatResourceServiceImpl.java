package com.h.backend.chat.application.impl;

import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.application.ChatResourceService;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import com.h.backend.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.nio.file.NoSuchFileException;

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
        return openResource(resource, false);
    }

    @Override
    public ResourceResponse openDownload(Long userId, String resourceId) {
        ChatMessageResourceEntity resource = requireOwnedResource(userId, resourceId);
        return openResource(resource, true);
    }

    private ResourceResponse openResource(ChatMessageResourceEntity resource, boolean attachment) {
        try {
            return new ResourceResponse(
                    resourceStorage.open(resource.getStorageKey()),
                    safeFileName(resource.getFileName()),
                    attachment
            );
        } catch (IllegalStateException exception) {
            if (hasCause(exception, NoSuchFileException.class)) {
                throw new BusinessException(40404, "资源文件已被清理");
            }
            throw exception;
        }
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
