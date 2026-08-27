package com.h.backend.chat.application.impl;

import com.h.backend.chat.application.ChatResourceService;
import com.h.backend.chat.application.ResourceContentPolicy;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.infrastructure.storage.ResourceRange;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import com.h.backend.chat.infrastructure.storage.ResourceStorageErrorKind;
import com.h.backend.chat.infrastructure.storage.ResourceStorageException;
import com.h.backend.chat.infrastructure.storage.ResourceStorageType;
import com.h.backend.common.exception.BusinessException;
import org.springframework.stereotype.Service;

/**
 * 内容读取服务（新计划 §6.3/§6.4 / §10 任务 4）。
 *
 * <p>读取顺序安全不变量：owner 鉴权（PostgreSQL metadata）先于对象读取
 * （计划 §11.3）——非 owner 与不存在统一按 40404「不存在」语义拒绝，
 * 存储层从未被触碰。
 *
 * <p>响应处置由 {@link ResourceContentPolicy} 决定：白名单图片/音视频允许
 * inline，其余（含历史/污染数据）强制 attachment；未知 MIME 兕底
 * application/octet-stream。download 恒 attachment。
 */
@Service
public class ChatResourceServiceImpl implements ChatResourceService {

    private final ChatMessageResourceMapper chatMessageResourceMapper;
    private final ResourceStorage resourceStorage;
    private final ResourceContentPolicy contentPolicy;

    public ChatResourceServiceImpl(
            ChatMessageResourceMapper chatMessageResourceMapper,
            ResourceStorage resourceStorage,
            ResourceContentPolicy contentPolicy
    ) {
        this.chatMessageResourceMapper = chatMessageResourceMapper;
        this.resourceStorage = resourceStorage;
        this.contentPolicy = contentPolicy;
    }

    @Override
    public ResourceResponse openPreview(Long userId, String resourceId, ResourceRange range) {
        ChatMessageResourceEntity resource = requireOwnedResource(userId, resourceId);
        ResourceContentPolicy.Disposition disposition = contentPolicy.dispositionFor(resource.getMimeType());
        return openResource(resource, !disposition.inlineSafe(), disposition.responseContentType(), range);
    }

    @Override
    public ResourceResponse openDownload(Long userId, String resourceId) {
        ChatMessageResourceEntity resource = requireOwnedResource(userId, resourceId);
        ResourceContentPolicy.Disposition disposition = contentPolicy.dispositionFor(resource.getMimeType());
        return openResource(resource, true, disposition.responseContentType(), ResourceRange.fullRead());
    }

    private ResourceResponse openResource(
            ChatMessageResourceEntity resource,
            boolean attachment,
            String responseContentType,
            ResourceRange range
    ) {
        try {
            return new ResourceResponse(
                    resourceStorage.open(resource.getStorageKey(), range),
                    safeFileName(resource.getFileName()),
                    attachment,
                    responseContentType
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
        // 计划 §4.4：只服务 OBJECT_STORAGE 行；读到其他存储类型（历史/污染数据）
        // 按内部数据错误 fail closed（IO_ERROR→全局映射 500），
        // 消息为固定安全文案，不暴露 storage key，且存储层从未被触碰。
        if (!ResourceStorageType.OBJECT_STORAGE.value().equals(resource.getStorageType())) {
            throw new ResourceStorageException(
                    ResourceStorageErrorKind.IO_ERROR, "资源存储类型异常");
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
