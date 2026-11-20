package com.h.capabilities.seam;

/** 会话附件元数据（归属校验已由 ConversationAssetPort 完成）。 */
public record AssetRef(
        String resourceId,
        long userId,
        String fileName,
        String mimeType,
        Long fileSize,
        String storageKey
) {
}