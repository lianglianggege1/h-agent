package com.h.backend.capability;

/**
 * 一次已交付的业务对象。产品聊天里 resourceId 是 chat_message_resources 主键，
 * 团队房间里是 mxc:// 地址；viewUrl/downloadUrl 由交付实现给出。
 */
public record DeliveredArtifact(
        String resourceId,
        String resourceType,
        String viewUrl,
        String downloadUrl,
        String fileName,
        String mimeType,
        Long fileSize,
        Integer width,
        Integer height,
        String storageType,
        String storageKey
) {
}