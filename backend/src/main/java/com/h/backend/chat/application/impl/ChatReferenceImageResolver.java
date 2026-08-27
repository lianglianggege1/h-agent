package com.h.backend.chat.application.impl;

import com.h.backend.chat.application.reference.ReferenceImageResolver;
import com.h.backend.chat.application.reference.ResolvedReferenceImage;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.infrastructure.storage.ResourceContent;
import com.h.backend.chat.infrastructure.storage.ResourceRange;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class ChatReferenceImageResolver implements ReferenceImageResolver {
    private final ChatMessageResourceMapper resourceMapper;
    private final ResourceStorage resourceStorage;

    public ChatReferenceImageResolver(ChatMessageResourceMapper resourceMapper, ResourceStorage resourceStorage) {
        this.resourceMapper = resourceMapper;
        this.resourceStorage = resourceStorage;
    }

    @Override
    public ResolvedReferenceImage resolve(Long userId, String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("参考图片资源不能为空");
        }
        ChatMessageResourceEntity resource = resourceMapper.selectByResourceId(resourceId);
        if (resource == null || !userId.equals(resource.getUserId())) {
            throw new IllegalArgumentException("参考图片资源不存在: " + resourceId);
        }
        if (!"IMAGE".equalsIgnoreCase(resource.getResourceType())) {
            throw new IllegalArgumentException("参考资源必须是图片: " + resourceId);
        }
        ResourceContent content = resourceStorage.open(resource.getStorageKey(), ResourceRange.fullRead());
        try (InputStream inputStream = content.inputStream()) {
            String mimeType = hasText(content.mimeType()) ? content.mimeType() : resource.getMimeType();
            return new ResolvedReferenceImage(resourceId, mimeType, inputStream.readAllBytes(), content.totalSize(),
                    resource.getWidth(), resource.getHeight());
        } catch (IOException exception) {
            throw new IllegalStateException("读取参考图片失败: " + resourceId, exception);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
