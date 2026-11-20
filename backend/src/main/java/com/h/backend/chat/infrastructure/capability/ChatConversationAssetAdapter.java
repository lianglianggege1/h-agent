package com.h.backend.chat.infrastructure.capability;

import com.h.backend.capability.AnchorMessage;
import com.h.backend.capability.AssetRef;
import com.h.backend.capability.port.ConversationAssetPort;
import com.h.backend.chat.application.ChatResourceService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionMessageEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatSessionMessageMapper;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.document.infrastructure.persistence.mapper.DocumentResourceMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 产品聊天附件读取：归属/引用校验后走受鉴权资源服务。 */
@Component
public class ChatConversationAssetAdapter implements ConversationAssetPort {

    private final ChatSessionService sessions;
    private final ChatResourceService resources;
    private final ChatMessageResourceMapper metadata;
    private final ChatSessionMessageMapper messages;
    private final DocumentResourceMapper documentResources;

    public ChatConversationAssetAdapter(
            ChatSessionService sessions,
            ChatResourceService resources,
            ChatMessageResourceMapper metadata,
            ChatSessionMessageMapper messages,
            DocumentResourceMapper documentResources) {
        this.sessions = sessions;
        this.resources = resources;
        this.metadata = metadata;
        this.messages = messages;
        this.documentResources = documentResources;
    }

    @Override
    public void requireScope(long userId, String scopeId) {
        var owned = sessions.getSessionDetail(userId, scopeId);
        if (!List.of("standard-chat", "harness-agent").contains(owned.agentId())) {
            throw new BusinessException(40000, "此 Agent 尚未接入文档能力");
        }
    }

    @Override
    public AssetRef requireAsset(long userId, String scopeId, String resourceId) {
        requireScope(userId, scopeId);
        ChatMessageResourceEntity row = metadata.selectByResourceId(resourceId);
        if (row == null || row.getUserId() == null || row.getUserId() != userId) {
            throw new BusinessException(40404, "附件不存在");
        }
        Integer count = documentResources.countOwnedReference(userId, scopeId, row.getStorageKey());
        if (count == null || count == 0) {
            throw new BusinessException(40404, "请先在当前会话引用此附件");
        }
        return new AssetRef(
                resourceId, row.getUserId(), row.getFileName(), row.getMimeType(),
                row.getFileSize(), row.getStorageKey());
    }

    @Override
    public byte[] readAsset(long userId, String scopeId, String resourceId, long maxBytes) {
        AssetRef ref = requireAsset(userId, scopeId, resourceId);
        if (ref.fileSize() != null && ref.fileSize() > maxBytes) {
            throw new BusinessException(40000, "文件超过文档处理大小上限，请拆分文件");
        }
        try (var content = resources.openDownload(userId, resourceId).content()) {
            byte[] bytes = content.inputStream().readNBytes(Math.toIntExact(maxBytes + 1));
            if (bytes.length > maxBytes) {
                throw new BusinessException(40000, "文件超过文档处理大小上限");
            }
            return bytes;
        } catch (java.io.IOException e) {
            var failure = new BusinessException(40000, "附件读取失败，请重试");
            failure.initCause(e);
            throw failure;
        }
    }

    @Override
    public List<Map<String, Object>> inputs(long userId, String scopeId) {
        requireScope(userId, scopeId);
        return documentResources.inputs(userId, scopeId);
    }

    @Override
    public AnchorMessage latestUserMessage(String scopeId) {
        return anchor(messages.selectLatestUser(scopeId));
    }

    @Override
    public AnchorMessage messageById(long messageId) {
        return anchor(messages.selectById(messageId));
    }

    private static AnchorMessage anchor(ChatSessionMessageEntity entity) {
        return entity == null ? null
                : new AnchorMessage(entity.getId(), entity.getUserId(), entity.getContentText());
    }
}