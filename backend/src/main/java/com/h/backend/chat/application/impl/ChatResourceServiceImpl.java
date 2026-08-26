package com.h.backend.chat.application.impl;

import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.application.ChatResourceService;
import com.h.backend.chat.infrastructure.storage.ResourceRange;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import com.h.backend.chat.infrastructure.storage.ResourceStorageErrorKind;
import com.h.backend.chat.infrastructure.storage.ResourceStorageException;
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
    public ResourceResponse openPreview(Long userId, String resourceId, ResourceRange range) {
        ChatMessageResourceEntity resource = requireOwnedResource(userId, resourceId);
        return openResource(resource, false, range);
    }

    @Override
    public ResourceResponse openDownload(Long userId, String resourceId) {
        ChatMessageResourceEntity resource = requireOwnedResource(userId, resourceId);
        return openResource(resource, true, ResourceRange.fullRead());
    }

    private ResourceResponse openResource(ChatMessageResourceEntity resource, boolean attachment, ResourceRange range) {
        try {
            return new ResourceResponse(
                    resourceStorage.open(resource.getStorageKey(), range),
                    safeFileName(resource.getFileName()),
                    attachment
            );
        } catch (ResourceStorageException exception) {
            if (exception.kind() == ResourceStorageErrorKind.NOT_FOUND) {
                throw new BusinessException(40404, "资源文件已被清理");
            }
            // 其他错误语义（SIZE_LIMIT/UNAVAILABLE/IO_ERROR）原样上抛，
            // 全局映射由后续任务统一处理。
            throw exception;
        }
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
