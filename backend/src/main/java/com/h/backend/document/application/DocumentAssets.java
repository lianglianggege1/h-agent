package com.h.backend.document.application;

import com.h.backend.chat.application.ChatResourceService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.document.infrastructure.config.DocumentProperties;
import com.h.backend.document.infrastructure.persistence.mapper.DocumentResourceMapper;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 附件访问应用服务，先验证用户与会话归属，再读取受大小限制的对象存储内容。 */
@Component
public class DocumentAssets {
    private final ChatSessionService sessions;
    private final ChatResourceService resources;
    private final ChatMessageResourceMapper metadata;
    private final DocumentResourceMapper db;
    private final DocumentProperties properties;

    public DocumentAssets(
            ChatSessionService sessions,
            ChatResourceService resources,
            ChatMessageResourceMapper metadata,
            DocumentResourceMapper db,
            DocumentProperties properties) {
        this.sessions = sessions;
        this.resources = resources;
        this.metadata = metadata;
        this.db = db;
        this.properties = properties;
    }

    public void session(Long user, String session) {
        var owned = sessions.getSessionDetail(user, session);
        if (!List.of("standard-chat", "harness-agent").contains(owned.agentId()))
            throw new BusinessException(40000, "此 Agent 尚未接入文档能力");
    }

    public ChatMessageResourceEntity require(Long user, String session, String id) {
        session(user, session);
        var row = metadata.selectByResourceId(id);
        if (row == null || !user.equals(row.getUserId()))
            throw new BusinessException(40404, "附件不存在");
        Integer count = db.countOwnedReference(user, session, row.getStorageKey());
        if (count == null || count == 0) throw new BusinessException(40404, "请先在当前会话引用此附件");
        return row;
    }

    public byte[] read(Long user, String session, String id) {
        var row = require(user, session, id);
        if (row.getFileSize() != null && row.getFileSize() > properties.getMaxBytes())
            throw new BusinessException(40000, "文件超过文档处理大小上限，请拆分文件");
        try (var content = resources.openDownload(user, id).content()) {
            byte[] bytes =
                    content.inputStream().readNBytes(Math.toIntExact(properties.getMaxBytes() + 1));
            if (bytes.length > properties.getMaxBytes())
                throw new BusinessException(40000, "文件超过文档处理大小上限");
            return bytes;
        } catch (java.io.IOException e) {
            var failure = new BusinessException(40000, "附件读取失败，请重试");
            failure.initCause(e);
            throw failure;
        }
    }

    public List<Map<String, Object>> inputs(Long user, String session) {
        session(user, session);
        return db.inputs(user, session);
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
