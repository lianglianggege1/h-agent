package com.h.backend.chat.application.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;
import com.h.backend.chat.interfaces.dto.ChatMessagePayloadDto;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatSessionBootstrapDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessagesPageDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMetaDto;
import com.h.backend.chat.interfaces.dto.ChatSessionOpenDto;
import com.h.backend.chat.interfaces.dto.ChatSessionSummaryDto;
import com.h.backend.chat.interfaces.dto.HarnessSubagentSummaryDto;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.entity.AgentSessionEntity;
import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionEntity;
import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionMessageEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentSessionMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatSessionMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatSessionMessageMapper;
import com.h.backend.chat.domain.model.ChatMessagePayload;
import com.h.backend.chat.domain.model.ChatSessionMessage;
import com.h.backend.chat.application.ChatMemorySnapshotService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.HarnessCollaborationService;
import com.h.backend.chat.application.SystemPromptService;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatSessionServiceImpl implements ChatSessionService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final int DEFAULT_MESSAGE_PAGE_SIZE = 20;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionMessageMapper chatSessionMessageMapper;
    private final ChatMessageResourceMapper chatMessageResourceMapper;
    private final ChatMemorySnapshotService chatMemorySnapshotService;
    private final SystemPromptService systemPromptService;
    private final ObjectMapper objectMapper;
    private final AgentRegistry agentRegistry;
    private final ObjectProvider<AgentRegistry> agentRegistryProvider;
    private final HarnessCollaborationService harnessCollaborationService;
    private final AgentSessionMapper agentSessionMapper;
    private final ChatMessageResourceBinder resourceBinder;

    @Autowired
    public ChatSessionServiceImpl(
            ChatSessionMapper chatSessionMapper,
            ChatSessionMessageMapper chatSessionMessageMapper,
            ChatMessageResourceMapper chatMessageResourceMapper,
            ChatMemorySnapshotService chatMemorySnapshotService,
            SystemPromptService systemPromptService,
            ObjectMapper objectMapper,
            ObjectProvider<AgentRegistry> agentRegistryProvider,
            HarnessCollaborationService harnessCollaborationService,
            AgentSessionMapper agentSessionMapper,
            ChatMessageResourceBinder resourceBinder
    ) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatSessionMessageMapper = chatSessionMessageMapper;
        this.chatMessageResourceMapper = chatMessageResourceMapper;
        this.chatMemorySnapshotService = chatMemorySnapshotService;
        this.systemPromptService = systemPromptService;
        this.objectMapper = objectMapper;
        this.agentRegistry = null;
        this.agentRegistryProvider = agentRegistryProvider;
        this.harnessCollaborationService = harnessCollaborationService;
        this.agentSessionMapper = Objects.requireNonNull(agentSessionMapper, "agentSessionMapper");
        this.resourceBinder = resourceBinder;
    }

    public ChatSessionServiceImpl(
            ChatSessionMapper chatSessionMapper,
            ChatSessionMessageMapper chatSessionMessageMapper,
            ChatMessageResourceMapper chatMessageResourceMapper,
            ChatMemorySnapshotService chatMemorySnapshotService,
            SystemPromptService systemPromptService,
            ObjectMapper objectMapper,
            ObjectProvider<AgentRegistry> agentRegistryProvider,
            AgentSessionMapper agentSessionMapper
    ) {
        this(
                chatSessionMapper,
                chatSessionMessageMapper,
                chatMessageResourceMapper,
                chatMemorySnapshotService,
                systemPromptService,
                objectMapper,
                agentRegistryProvider,
                null,
                agentSessionMapper,
                new ChatMessageResourceBinder(chatMessageResourceMapper, objectMapper)
        );
    }

    public ChatSessionServiceImpl(
            ChatSessionMapper chatSessionMapper,
            ChatSessionMessageMapper chatSessionMessageMapper,
            ChatMemorySnapshotService chatMemorySnapshotService,
            SystemPromptService systemPromptService,
            ObjectMapper objectMapper,
            AgentRegistry agentRegistry,
            AgentSessionMapper agentSessionMapper
    ) {
        this(
                chatSessionMapper,
                chatSessionMessageMapper,
                null,
                chatMemorySnapshotService,
                systemPromptService,
                objectMapper,
                agentRegistry,
                agentSessionMapper
        );
    }

    /** 供不启动 Spring 容器的单元测试显式提供必需依赖。 */
    public ChatSessionServiceImpl(
            ChatSessionMapper chatSessionMapper,
            ChatSessionMessageMapper chatSessionMessageMapper,
            ChatMessageResourceMapper chatMessageResourceMapper,
            ChatMemorySnapshotService chatMemorySnapshotService,
            SystemPromptService systemPromptService,
            ObjectMapper objectMapper,
            AgentRegistry agentRegistry,
            AgentSessionMapper agentSessionMapper
    ) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatSessionMessageMapper = chatSessionMessageMapper;
        this.chatMessageResourceMapper = chatMessageResourceMapper;
        this.chatMemorySnapshotService = chatMemorySnapshotService;
        this.systemPromptService = systemPromptService;
        this.objectMapper = objectMapper;
        this.agentRegistry = agentRegistry;
        this.agentRegistryProvider = null;
        this.harnessCollaborationService = null;
        this.agentSessionMapper = Objects.requireNonNull(agentSessionMapper, "agentSessionMapper");
        this.resourceBinder = new ChatMessageResourceBinder(chatMessageResourceMapper, objectMapper);
    }

    @Override
    @Transactional
    public ChatSessionBootstrapDto bootstrap(Long userId) {
        archiveExpiredSessionsForUser(userId);
        List<ChatSessionEntity> activeSessions = chatSessionMapper.selectActiveByUserId(userId);
        if (activeSessions.isEmpty()) {
            return new ChatSessionBootstrapDto("created", createSession(userId, null, null, null), List.of());
        }
        if (activeSessions.size() == 1) {
            ChatSessionEntity active = activeSessions.get(0);
            chatMemorySnapshotService.markResident(active.getSessionId());
            return new ChatSessionBootstrapDto("single", toOpen(active, DEFAULT_MESSAGE_PAGE_SIZE, null), List.of());
        }
        List<ChatSessionSummaryDto> candidates = activeSessions.stream()
                .sorted(Comparator.comparing(ChatSessionEntity::getUpdatedAt).reversed())
                .map(entity -> toSummary(entity))
                .toList();
        return new ChatSessionBootstrapDto("choose", null, candidates);
    }

    @Override
    @Transactional
    public ChatSessionOpenDto createSession(Long userId, Long promptId, String agentId, String currentSessionId) {
        archiveExpiredSessionsForUser(userId);
        if (StringUtils.isNotBlank(currentSessionId)) {
            ChatSessionEntity current = requireOwnedSession(userId, currentSessionId);
            archiveOrDeleteIfEmpty(current);
        }

        String resolvedAgentId = StringUtils.isBlank(agentId) ? ChatAgentIds.STANDARD_CHAT : agentId;
        Long resolvedPromptId = ChatAgentIds.STANDARD_CHAT.equals(resolvedAgentId)
                ? systemPromptService.resolvePromptId(userId, promptId)
                : null;
        LocalDateTime now = LocalDateTime.now();
        ChatSessionEntity entity = new ChatSessionEntity();
        entity.setUserId(userId);
        entity.setSessionId(UUID.randomUUID().toString());
        entity.setPromptId(resolvedPromptId);
        entity.setAgentId(resolvedAgentId);
        entity.setTitle("新会话");
        entity.setStatus(STATUS_ACTIVE);
        entity.setLastUserMessage(null);
        entity.setMessageCount(0);
        entity.setLastActiveAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        registerRootAgentSession(entity, now);
        chatSessionMapper.insert(entity);
        chatMemorySnapshotService.markResident(entity.getSessionId());
        return toOpen(entity, DEFAULT_MESSAGE_PAGE_SIZE, null);
    }

    @Override
    @Transactional
    public ChatSessionOpenDto chooseActiveSession(Long userId, String selectedSessionId) {
        archiveExpiredSessionsForUser(userId);
        List<ChatSessionEntity> activeSessions = chatSessionMapper.selectActiveByUserId(userId);
        ChatSessionEntity selected = null;
        for (ChatSessionEntity session : activeSessions) {
            if (session.getSessionId().equals(selectedSessionId)) {
                selected = session;
            } else {
                archiveOrDeleteIfEmpty(session);
            }
        }
        if (selected == null) {
            throw new BusinessException(40004, "所选会话不存在");
        }
        selected = refreshSession(selected.getSessionId());
        chatMemorySnapshotService.markResident(selected.getSessionId());
        return toOpen(selected, DEFAULT_MESSAGE_PAGE_SIZE, null);
    }

    @Override
    @Transactional
    public ChatSessionOpenDto activateHistorySession(Long userId, String targetSessionId, String currentSessionId) {
        if (StringUtils.isBlank(targetSessionId)) {
            throw new BusinessException(40007, "目标会话不能为空");
        }

        archiveExpiredSessionsForUser(userId);
        ChatSessionEntity target = requireOwnedSession(userId, targetSessionId);

        if (StringUtils.isNotBlank(currentSessionId) && currentSessionId.equals(targetSessionId)) {
            chatMemorySnapshotService.markResident(target.getSessionId());
            return toOpen(target, DEFAULT_MESSAGE_PAGE_SIZE, null);
        }

        if (StringUtils.isNotBlank(currentSessionId)) {
            ChatSessionEntity current = requireOwnedSession(userId, currentSessionId);
            if (!current.getSessionId().equals(targetSessionId)) {
                archiveOrDeleteIfEmpty(current);
            }
        }

        if (!STATUS_ACTIVE.equals(target.getStatus())) {
            LocalDateTime now = LocalDateTime.now();
            target.setStatus(STATUS_ACTIVE);
            target.setLastActiveAt(now);
            target.setUpdatedAt(now);
            chatSessionMapper.updateById(target);
        }

        ChatSessionEntity refreshed = refreshSession(target.getSessionId());
        chatMemorySnapshotService.markResident(refreshed.getSessionId());
        return toOpen(refreshed, DEFAULT_MESSAGE_PAGE_SIZE, null);
    }

    @Override
    public ChatSessionMetaDto getSessionDetail(Long userId, String sessionId) {
        archiveExpiredSessionsForUser(userId);
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        return toMeta(session);
    }

    @Override
    public ChatSessionMessagesPageDto getSessionMessages(Long userId, String sessionId, int limit, Integer beforeSeq) {
        archiveExpiredSessionsForUser(userId);
        ChatSessionEntity root = chatSessionMapper.selectBySessionId(sessionId);
        if (root != null) {
            if (!userId.equals(root.getUserId())) {
                throw new BusinessException(40404, "会话不存在");
            }
            return buildMessagesPage(root, Math.max(limit, 1), beforeSeq);
        }
        if (harnessCollaborationService == null) {
            throw new BusinessException(40404, "会话不存在");
        }
        var resolved = harnessCollaborationService.resolveExecutionSession(userId, sessionId);
        ChatSessionEntity rootSession = requireOwnedSession(userId, resolved.rootSessionId());
        return buildAgentSessionMessagesPage(
                rootSession,
                resolved.sessionId(),
                Math.max(limit, 1),
                beforeSeq
        );
    }

    @Override
    public ChatSessionMessageDto getOwnedMessage(Long userId, String sessionId, Long messageId) {
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        ChatSessionMessageEntity message = chatSessionMessageMapper.selectById(messageId);
        if (message == null
                || !session.getId().equals(message.getSessionRecordId())
                || !userId.equals(message.getUserId())) {
            throw new BusinessException(40404, "消息不存在");
        }
        // sessionId 是顶级页面授权边界；实际消息可属于该页面下任意 Agent Session。
        Map<Long, List<ChatMessageResourceDto>> resourcesByMessageId = loadResourcesByMessageId(List.of(message));
        return toMessageDto(message, resourcesByMessageId.getOrDefault(messageId, List.of()));
    }

    @Override
    public List<ChatSessionSummaryDto> listHistory(Long userId, int page, int size) {
        archiveExpiredSessionsForUser(userId);
        int offset = Math.max(page, 0) * size;
        return chatSessionMapper.selectHistoryByUserId(userId, size, offset).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    @Scheduled(fixedDelay = 300000) // 定时查看如果有超过24小时无用户消息则归档 此处是否可以优化为redis过期通知
    @Transactional
    public void archiveExpiredSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<ChatSessionEntity> activeSessions = chatSessionMapper.selectList(
                new QueryWrapper<ChatSessionEntity>()
                        .eq("status", STATUS_ACTIVE)
                        .lt("updated_at", cutoff)
        );
        for (ChatSessionEntity session : activeSessions) {
            archiveOrDeleteIfEmpty(session);
        }
    }

    @Override
    @Transactional
    public void assertActiveSession(Long userId, String sessionId, Long promptId, String agentId) {
        String requestedAgentId = StringUtils.isBlank(agentId) ? ChatAgentIds.STANDARD_CHAT : agentId;
        ChatSessionEntity session = requireActiveAgentSession(userId, sessionId, requestedAgentId);
        if (!ChatAgentIds.STANDARD_CHAT.equals(requestedAgentId)) {
            return;
        }
        Long resolvedPromptId = systemPromptService.resolvePromptId(userId, promptId);
        if (!resolvedPromptId.equals(session.getPromptId())) {
            throw new BusinessException(40006, "会话提示词不匹配，请重新创建会话");
        }
    }

    @Override
    @Transactional
    public void assertActiveAgentSession(Long userId, String sessionId, String agentId) {
        String requestedAgentId = StringUtils.isBlank(agentId) ? ChatAgentIds.STANDARD_CHAT : agentId;
        requireActiveAgentSession(userId, sessionId, requestedAgentId);
    }

    @Override
    @Transactional
    public Long appendUserMessage(Long userId, String sessionId, String userMessage, List<ChatMessageResourceUseDto> resources) {
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        if (!STATUS_ACTIVE.equals(session.getStatus())) {
            throw new BusinessException(40005, "会话已失效，请重新进入聊天页");
        }

        int nextSequence = allocateMessageSequence(session);
        LocalDateTime now = LocalDateTime.now();
        ChatSessionMessage message = buildMessage("user", "USER", userMessage, now, nextSequence);
        Long messageId = persistMessage(session, message);

        resourceBinder.bind(userId, messageId, resources);

        session.setMessageCount(nextSequence);
        session.setLastUserMessage(userMessage);
        session.setLastActiveAt(now);
        session.setUpdatedAt(now);
        if (session.getTitle() == null || "新会话".equals(session.getTitle())) {
            session.setTitle(buildTitle(userMessage));
        }
        persistParentActivity(session, now, userMessage);
        return messageId;
    }

    @Override
    @Transactional
    public Long appendBlockedMessage(Long userId, String sessionId, String blockedMessage) {
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        if (!STATUS_ACTIVE.equals(session.getStatus())) {
            throw new BusinessException(40005, "会话已失效，请重新进入聊天页");
        }

        int nextSequence = allocateMessageSequence(session);
        LocalDateTime now = LocalDateTime.now();
        ChatSessionMessage message = buildMessage("blocked", "SYSTEM", blockedMessage, now, nextSequence);
        Long messageId = persistMessage(session, message);

        session.setMessageCount(nextSequence);
        session.setLastActiveAt(now);
        session.setUpdatedAt(now);
        persistParentActivity(session, now, null);
        return messageId;
    }

    @Override
    @Transactional
    public Long appendReasoningMessage(Long userId, String sessionId, String reasoningMessage) {
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        if (!STATUS_ACTIVE.equals(session.getStatus())) {
            throw new BusinessException(40005, "会话已失效，请重新进入聊天页");
        }

        int nextSequence = allocateMessageSequence(session);
        LocalDateTime now = LocalDateTime.now();
        ChatSessionMessage message = buildMessage("assistant", "REASONING", reasoningMessage, now, nextSequence);
        Long messageId = persistMessage(session, message);

        session.setMessageCount(nextSequence);
        session.setLastActiveAt(now);
        session.setUpdatedAt(now);
        persistParentActivity(session, now, null);
        return messageId;
    }

    @Override
    @Transactional
    public Long appendAssistantMessage(Long userId, String sessionId, String assistantMessage) {
        return appendAssistantMessage(userId, sessionId, assistantMessage, null);
    }

    @Override
    @Transactional
    public Long appendAssistantMessage(Long userId, String sessionId, String assistantMessage, List<ChatMessageResourceUseDto> resources) {
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        if (!STATUS_ACTIVE.equals(session.getStatus())) {
            throw new BusinessException(40005, "会话已失效，请重新进入聊天页");
        }

        int nextSequence = allocateMessageSequence(session);
        LocalDateTime now = LocalDateTime.now();
        ChatSessionMessage message = buildMessage("assistant", "AI", assistantMessage, now, nextSequence);
        Long messageId = persistMessage(session, message);
        resourceBinder.bind(userId, messageId, resources);

        session.setMessageCount(nextSequence);
        session.setLastActiveAt(now);
        session.setUpdatedAt(now);
        persistParentActivity(session, now, null);
        return messageId;
    }

    @Override
    @Transactional
    public ChatMessageResourceDto bindStoredAudioResource(
            Long userId,
            String sessionId,
            Long messageId,
            String source,
            StoredResource storedResource,
            Map<String, Object> metadata
    ) {
        if (chatMessageResourceMapper == null) {
            throw new IllegalStateException("ChatMessageResourceMapper is required to bind audio resources");
        }

        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        ChatSessionMessageEntity message = chatSessionMessageMapper.selectById(messageId);
        if (message == null
                || !session.getId().equals(message.getSessionRecordId())
                || !sessionId.equals(message.getSessionId())
                || !userId.equals(message.getUserId())) {
            throw new BusinessException(40404, "消息不存在");
        }

        String normalizedSource = normalizeAudioSource(source);
        if ("USER_RECORDING".equals(normalizedSource)) {
            if (!"user".equals(message.getRoleCode())) {
                throw new BusinessException(40000, "用户录音只能绑定用户消息");
            }
        } else if ("ASSISTANT_TTS".equals(normalizedSource)) {
            if (!"assistant".equals(message.getRoleCode()) || !"AI".equals(message.getMessageType())) {
                throw new BusinessException(40000, "Assistant TTS 只能绑定 AI 回复消息");
            }
        } else {
            throw new BusinessException(40000, "不支持的音频来源");
        }

        ChatMessageResourceEntity row = new ChatMessageResourceEntity();
        row.setId(storedResource.id());
        row.setMessageId(messageId);
        row.setUserId(userId);
        row.setResourceType("AUDIO");
        row.setResourceRole("ATTACHMENT");
        row.setStorageType(storedResource.storageType());
        row.setStorageKey(storedResource.storageKey());
        row.setViewUrl("/api/chat/resources/" + storedResource.id() + "/content");
        row.setDownloadUrl("/api/chat/resources/" + storedResource.id() + "/download");
        row.setMimeType(storedResource.mimeType());
        row.setFileName(storedResource.fileName());
        row.setFileSize(storedResource.fileSize());
        row.setWidth(null);
        row.setHeight(null);
        row.setMetadataJson(toMetadataJson(metadata));
        row.setCreatedAt(LocalDateTime.now());
        chatMessageResourceMapper.insert(row);
        return toResourceDto(row);
    }

    @Override
    @Transactional
    public ChatSessionMessageDto appendImageMessage(
            Long userId,
            String sessionId,
            String imagePrompt,
            ChatMessagePayload payload,
            List<ChatMessageResourceDto> resources
    ) {
        if (chatMessageResourceMapper == null) {
            throw new IllegalStateException("ChatMessageResourceMapper is required to append image messages");
        }
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        if (!STATUS_ACTIVE.equals(session.getStatus())) {
            throw new BusinessException(40005, "会话已失效，请重新进入聊天页");
        }

        int nextSequence = allocateMessageSequence(session);
        LocalDateTime now = LocalDateTime.now();
        ChatSessionMessage message = buildMessage("assistant", "IMAGE", imagePrompt, now, nextSequence);
        message.setPayload(payload);
        Long messageId = persistMessagePayload(session, message, payload);

        List<ChatMessageResourceDto> safeResources = resources == null ? List.of() : resources;
        for (ChatMessageResourceDto resource : safeResources) {
            ChatMessageResourceEntity row = new ChatMessageResourceEntity();
            row.setId(resource.id());
            row.setMessageId(messageId);
            row.setUserId(userId);
            row.setResourceType(requireResourceField(resource.type(), "type"));
            row.setResourceRole(requireResourceField(resource.role(), "role"));
            row.setStorageType(resource.storageType() == null ? "LOCAL_FILE" : resource.storageType());
            row.setStorageKey(resource.storageKey() == null ? resource.id() : resource.storageKey());
            row.setViewUrl(resource.viewUrl());
            row.setDownloadUrl(resource.downloadUrl());
            row.setMimeType(resource.mimeType());
            row.setFileName(resource.fileName());
            row.setFileSize(resource.fileSize());
            row.setWidth(resource.width());
            row.setHeight(resource.height());
            row.setMetadataJson(toMetadataJson(resource.metadata()));
            row.setCreatedAt(now);
            chatMessageResourceMapper.insert(row);
        }

        session.setMessageCount(nextSequence);
        session.setLastActiveAt(now);
        session.setUpdatedAt(now);
        persistParentActivity(session, now, null);

        return new ChatSessionMessageDto(
                String.valueOf(messageId),
                "assistant",
                "IMAGE",
                imagePrompt,
                toPayloadDto(payload),
                safeResources,
                now
        );
    }

    @Override
    @Transactional
    public ChatSessionMessageDto appendResourceMessage(
            Long userId,
            String sessionId,
            String content,
            String messageType,
            List<ChatMessageResourceDto> resources
    ) {
        if (chatMessageResourceMapper == null) {
            throw new IllegalStateException("ChatMessageResourceMapper is required to append resource messages");
        }
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        if (!STATUS_ACTIVE.equals(session.getStatus())) {
            throw new BusinessException(40005, "会话已失效，请重新进入聊天页");
        }

        int nextSequence = allocateMessageSequence(session);
        LocalDateTime now = LocalDateTime.now();
        String normalizedMessageType = requireResourceField(messageType, "messageType").trim().toUpperCase();
        ChatSessionMessage message = buildMessage("assistant", normalizedMessageType, content, now, nextSequence);
        Long messageId = persistMessage(session, message);

        List<ChatMessageResourceDto> safeResources = resources == null ? List.of() : resources;
        for (ChatMessageResourceDto resource : safeResources) {
            ChatMessageResourceEntity row = new ChatMessageResourceEntity();
            row.setId(resource.id());
            row.setMessageId(messageId);
            row.setUserId(userId);
            row.setResourceType(requireResourceField(resource.type(), "type"));
            row.setResourceRole(requireResourceField(resource.role(), "role"));
            row.setStorageType(resource.storageType() == null ? "LOCAL_FILE" : resource.storageType());
            row.setStorageKey(resource.storageKey() == null ? resource.id() : resource.storageKey());
            row.setViewUrl(resource.viewUrl());
            row.setDownloadUrl(resource.downloadUrl());
            row.setMimeType(resource.mimeType());
            row.setFileName(resource.fileName());
            row.setFileSize(resource.fileSize());
            row.setWidth(resource.width());
            row.setHeight(resource.height());
            row.setMetadataJson(toMetadataJson(resource.metadata()));
            row.setCreatedAt(now);
            chatMessageResourceMapper.insert(row);
        }

        session.setMessageCount(nextSequence);
        session.setLastActiveAt(now);
        session.setUpdatedAt(now);
        persistParentActivity(session, now, null);

        return new ChatSessionMessageDto(
                String.valueOf(messageId),
                "assistant",
                normalizedMessageType,
                content == null ? "" : content,
                null,
                safeResources,
                now
        );
    }

    @Override
    @Transactional
    public ChatSessionMessageDto appendGeneratedMediaMessage(Long userId, String sessionId, String content) {
        return appendResourceMessage(userId, sessionId, content, "VIDEO", List.of());
    }

    @Override
    @Transactional
    public void updateGeneratedMediaMessage(
            Long userId,
            String sessionId,
            Long messageId,
            String content,
            List<ChatMessageResourceDto> resources
    ) {
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        ChatSessionMessageEntity message = chatSessionMessageMapper.selectById(messageId);
        if (message == null
                || !session.getId().equals(message.getSessionRecordId())
                || !userId.equals(message.getUserId())
                || !"VIDEO".equals(message.getMessageType())) {
            throw new BusinessException(40404, "视频生成消息不存在");
        }

        message.setContentText(content);
        chatSessionMessageMapper.updateById(message);
        for (ChatMessageResourceDto resource : resources == null ? List.<ChatMessageResourceDto>of() : resources) {
            ChatMessageResourceEntity row = new ChatMessageResourceEntity();
            row.setId(resource.id());
            row.setMessageId(messageId);
            row.setUserId(userId);
            row.setResourceType(requireResourceField(resource.type(), "type"));
            row.setResourceRole(requireResourceField(resource.role(), "role"));
            row.setStorageType(resource.storageType());
            row.setStorageKey(resource.storageKey());
            row.setViewUrl(resource.viewUrl());
            row.setDownloadUrl(resource.downloadUrl());
            row.setMimeType(resource.mimeType());
            row.setFileName(resource.fileName());
            row.setFileSize(resource.fileSize());
            row.setWidth(resource.width());
            row.setHeight(resource.height());
            row.setMetadataJson(toMetadataJson(resource.metadata()));
            row.setCreatedAt(LocalDateTime.now());
            chatMessageResourceMapper.insert(row);
        }
    }

    private String normalizeAudioSource(String source) {
        if (source == null || source.isBlank()) {
            throw new BusinessException(40000, "不支持的音频来源");
        }
        return source.trim().toUpperCase();
    }

    private void archiveExpiredSessionsForUser(Long userId) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<ChatSessionEntity> expired = chatSessionMapper.selectList(
                new QueryWrapper<ChatSessionEntity>()
                        .eq("user_id", userId)
                        .eq("status", STATUS_ACTIVE)
                        .lt("updated_at", cutoff)
        );
        for (ChatSessionEntity session : expired) {
            archiveOrDeleteIfEmpty(session);
        }
    }

    /** 分配当前实际 Agent Session 内的消息序号；数据库原子更新是唯一的分配方式。 */
    private int allocateMessageSequence(ChatSessionEntity session) {
        Integer sequence = agentSessionMapper.nextMessageSequence(session.getSessionId());
        if (sequence == null) {
            throw new IllegalStateException("Agent session does not exist: " + session.getSessionId());
        }
        return sequence;
    }

    private void persistParentActivity(
            ChatSessionEntity session,
            LocalDateTime now,
            String lastUserMessage
    ) {
        if (lastUserMessage != null) {
            chatSessionMapper.touchAfterUserMessage(
                    session.getId(), lastUserMessage, buildTitle(lastUserMessage),
                    session.getMessageCount(), now
            );
        } else {
            chatSessionMapper.touchRootMessage(session.getId(), session.getMessageCount(), now);
        }
    }

    private void archiveOrDeleteIfEmpty(ChatSessionEntity session) {
        if ((session.getMessageCount() == null ? 0 : session.getMessageCount()) <= 0) {
            chatMemorySnapshotService.evict(session.getSessionId());
            chatMemorySnapshotService.deleteSnapshot(session.getSessionId());
            chatSessionMessageMapper.delete(new QueryWrapper<ChatSessionMessageEntity>().eq("session_record_id", session.getId()));
            chatSessionMapper.deleteById(session.getId());
            AgentSessionEntity root = agentSessionMapper.selectBySessionId(session.getSessionId());
            if (root != null) {
                agentSessionMapper.deleteById(root.getId());
            }
            return;
        }
        chatMemorySnapshotService.flushNow(session.getSessionId());
        chatMemorySnapshotService.evict(session.getSessionId());
        session.setStatus(STATUS_ARCHIVED);
        session.setUpdatedAt(LocalDateTime.now());
        chatSessionMapper.updateById(session);
    }

    /** 将顶级聊天页面登记为统一 Agent Session 树的根节点。 */
    private void registerRootAgentSession(ChatSessionEntity chatSession, LocalDateTime now) {
        AgentSessionEntity root = new AgentSessionEntity();
        root.setSessionId(chatSession.getSessionId());
        root.setParentSessionId(null);
        root.setUserId(chatSession.getUserId());
        root.setAgentId(chatSession.getAgentId());
        root.setGatewaySubagentId(null);
        root.setDisplayOrder(null);
        root.setMessageCount(0);
        root.setCreatedAt(now);
        root.setUpdatedAt(now);
        agentSessionMapper.insert(root);
    }

    private ChatSessionEntity requireOwnedSession(Long userId, String sessionId) {
        ChatSessionEntity session = chatSessionMapper.selectBySessionId(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BusinessException(40404, "会话不存在");
        }
        return session;
    }

    private ChatSessionEntity requireActiveAgentSession(Long userId, String sessionId, String requestedAgentId) {
        archiveExpiredSessionsForUser(userId);
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        if (!STATUS_ACTIVE.equals(session.getStatus())) {
            throw new BusinessException(40005, "会话已失效，请重新进入聊天页");
        }
        String sessionAgentId = StringUtils.isBlank(session.getAgentId())
                ? ChatAgentIds.STANDARD_CHAT
                : session.getAgentId();
        if (!requestedAgentId.equals(sessionAgentId)) {
            throw new BusinessException(40008, "会话不属于当前 Agent，请重新创建会话");
        }
        return session;
    }

    private ChatSessionEntity refreshSession(String sessionId) {
        ChatSessionEntity refreshed = chatSessionMapper.selectBySessionId(sessionId);
        if (refreshed == null) {
            throw new BusinessException(40404, "会话不存在");
        }
        return refreshed;
    }

    private ChatSessionOpenDto toOpen(ChatSessionEntity session, int limit, Integer beforeSeq) {
        List<HarnessSubagentSummaryDto> subagents = null;
        if (ChatAgentIds.HARNESS.equals(session.getAgentId())) {
            subagents = harnessCollaborationService == null
                    ? List.of()
                    : harnessCollaborationService.listSubagents(session.getUserId(), session.getSessionId());
        }
        return new ChatSessionOpenDto(
                toMeta(session),
                buildMessagesPage(session, limit, beforeSeq),
                subagents
        );
    }

    private ChatSessionMetaDto toMeta(ChatSessionEntity session) {
        String agentId = StringUtils.isBlank(session.getAgentId()) ? ChatAgentIds.STANDARD_CHAT : session.getAgentId();
        AgentMetadata agentMetadata = resolveAgentMetadata(agentId);
        return new ChatSessionMetaDto(
                session.getSessionId(),
                session.getTitle(),
                session.getPromptId(),
                agentId,
                agentMetadata.displayName(),
                agentMetadata.domain(),
                agentMetadata.runtimeType(),
                session.getMessageCount() == null ? 0 : session.getMessageCount(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                !STATUS_ACTIVE.equals(session.getStatus())
        );
    }

    private ChatSessionSummaryDto toSummary(ChatSessionEntity session) {
        String agentId = StringUtils.isBlank(session.getAgentId()) ? ChatAgentIds.STANDARD_CHAT : session.getAgentId();
        AgentMetadata agentMetadata = resolveAgentMetadata(agentId);
        return new ChatSessionSummaryDto(
                session.getSessionId(),
                session.getTitle(),
                session.getLastUserMessage(),
                session.getPromptId(),
                agentId,
                agentMetadata.displayName(),
                agentMetadata.domain(),
                agentMetadata.runtimeType(),
                session.getMessageCount() == null ? 0 : session.getMessageCount(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                !STATUS_ACTIVE.equals(session.getStatus())
        );
    }

    private AgentMetadata resolveAgentMetadata(String agentId) {
        AgentRegistry agentRegistry = this.agentRegistry != null
                ? this.agentRegistry
                : agentRegistryProvider == null ? null : agentRegistryProvider.getIfAvailable();
        if (agentRegistry == null) {
            return new AgentMetadata(agentId, "未知", "UNKNOWN");
        }
        return agentRegistry.listEnabled().stream()
                .filter(definition -> definition.agentId().equals(agentId))
                .findFirst()
                .map(this::toAgentMetadata)
                .orElseGet(() -> new AgentMetadata(agentId, "未知", "UNKNOWN"));
    }

    private AgentMetadata toAgentMetadata(AgentDefinition definition) {
        return new AgentMetadata(
                definition.displayName(),
                definition.domain(),
                definition.runtimeType().name()
        );
    }

    private record AgentMetadata(
            String displayName,
            String domain,
            String runtimeType
    ) {
    }

    private ChatSessionMessagesPageDto buildMessagesPage(ChatSessionEntity session, int limit, Integer beforeSeq) {
        List<ChatSessionMessageEntity> rows = chatSessionMessageMapper.selectPageBySessionRecordId(
                session.getId(), session.getSessionId(), limit + 1, beforeSeq
        );
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        List<ChatSessionMessageEntity> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(ChatSessionMessageEntity::getSequenceNo));
        Map<Long, List<ChatMessageResourceDto>> resourcesByMessageId = loadResourcesByMessageId(ordered);
        List<ChatSessionMessageDto> messages = ordered.stream()
                .map(row -> toMessageDto(row, resourcesByMessageId.getOrDefault(row.getId(), List.of())))
                .toList();
        Integer nextBefore = hasMore ? ordered.get(0).getSequenceNo() : null;
        return new ChatSessionMessagesPageDto(session.getSessionId(), messages, hasMore, nextBefore);
    }

    /** 子会话与父会话共用同一历史接口，sessionId 始终表示实际消息会话。 */
    private ChatSessionMessagesPageDto buildAgentSessionMessagesPage(
            ChatSessionEntity root,
            String agentSessionId,
            int limit,
            Integer beforeSeq
    ) {
        List<ChatSessionMessageEntity> rows = chatSessionMessageMapper.selectPageByAgentSessionId(
                agentSessionId, limit + 1, beforeSeq
        );
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        List<ChatSessionMessageEntity> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(ChatSessionMessageEntity::getSequenceNo));
        for (ChatSessionMessageEntity row : ordered) {
            if (!root.getId().equals(row.getSessionRecordId()) || !agentSessionId.equals(row.getSessionId())) {
                throw new BusinessException(40404, "消息不存在");
            }
        }
        Map<Long, List<ChatMessageResourceDto>> resourcesByMessageId = loadResourcesByMessageId(ordered);
        List<ChatSessionMessageDto> messages = ordered.stream()
                .map(row -> toMessageDto(row, resourcesByMessageId.getOrDefault(row.getId(), List.of())))
                .toList();
        Integer nextBefore = hasMore ? ordered.getFirst().getSequenceNo() : null;
        return new ChatSessionMessagesPageDto(agentSessionId, messages, hasMore, nextBefore);
    }

    private ChatSessionMessage buildMessage(
            String role,
            String messageType,
            String content,
            LocalDateTime createdAt,
            int sequenceNo
    ) {
        ChatSessionMessage message = new ChatSessionMessage();
        message.setId(UUID.randomUUID().toString());
        message.setSequenceNo(sequenceNo);
        message.setRole(role);
        message.setMessageType(messageType);
        message.setContent(content);
        message.setCreatedAt(createdAt);
        return message;
    }

    private Long persistMessage(ChatSessionEntity session, ChatSessionMessage message) {
        ChatSessionMessageEntity row = new ChatSessionMessageEntity();
        row.setSessionRecordId(session.getId());
        row.setSessionId(session.getSessionId());
        row.setUserId(session.getUserId());
        row.setSequenceNo(message.getSequenceNo());
        row.setMessageType(message.getMessageType());
        row.setRoleCode(message.getRole());
        row.setContentText(message.getContent());
        row.setPayloadJson(writeMessagePayload(message));
        row.setCreatedAt(message.getCreatedAt());
        chatSessionMessageMapper.insert(row);
        return row.getId();
    }

    private Long persistMessagePayload(ChatSessionEntity session, ChatSessionMessage message, ChatMessagePayload payload) {
        ChatSessionMessageEntity row = new ChatSessionMessageEntity();
        row.setSessionRecordId(session.getId());
        row.setSessionId(session.getSessionId());
        row.setUserId(session.getUserId());
        row.setSequenceNo(message.getSequenceNo());
        row.setMessageType(message.getMessageType());
        row.setRoleCode(message.getRole());
        row.setContentText(message.getContent());
        row.setPayloadJson(writePayload(payload));
        row.setCreatedAt(message.getCreatedAt());
        chatSessionMessageMapper.insert(row);
        return row.getId();
    }

    private String writeMessagePayload(ChatSessionMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize chat session message payload", ex);
        }
    }

    private String writePayload(ChatMessagePayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize chat image message payload", ex);
        }
    }

    private String buildTitle(String userMessage) {
        String compact = userMessage == null ? "" : userMessage.trim().replaceAll("\\s+", " ");
        if (compact.isBlank()) {
            return "新会话";
        }
        return compact.length() <= 20 ? compact : compact.substring(0, 20);
    }

    private Map<Long, List<ChatMessageResourceDto>> loadResourcesByMessageId(List<ChatSessionMessageEntity> rows) {
        if (chatMessageResourceMapper == null || rows.isEmpty()) {
            return Map.of();
        }
        List<Long> messageIds = rows.stream()
                .map(ChatSessionMessageEntity::getId)
                .filter(id -> id != null)
                .toList();
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        return chatMessageResourceMapper.selectByMessageIds(messageIds).stream()
                .collect(Collectors.groupingBy(
                        ChatMessageResourceEntity::getMessageId,
                        Collectors.mapping(this::toResourceDto, Collectors.toList())
                ));
    }

    private ChatMessageResourceDto toResourceDto(ChatMessageResourceEntity row) {
        return new ChatMessageResourceDto(
                row.getId(),
                row.getResourceType(),
                row.getResourceRole(),
                row.getViewUrl(),
                row.getDownloadUrl(),
                row.getFileName(),
                row.getMimeType(),
                row.getFileSize(),
                row.getWidth(),
                row.getHeight(),
                parseMetadata(row.getMetadataJson()),
                row.getStorageType(),
                row.getStorageKey()
        );
    }

    private String requireResourceField(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("resource " + fieldName + " is required");
        }
        return value;
    }

    private Object parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(metadataJson, Object.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    private String toMetadataJson(Object metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException e) {
            return null;
        }
    }

    private ChatSessionMessageDto toMessageDto(ChatSessionMessageEntity row, List<ChatMessageResourceDto> resources) {
        String normalizedMessageType = normalizeMessageType(row.getMessageType(), row.getRoleCode());
        return new ChatSessionMessageDto(
                row.getId() == null ? UUID.randomUUID().toString() : String.valueOf(row.getId()),
                normalizeRole(row.getRoleCode()),
                normalizedMessageType,
                row.getContentText() == null ? "" : row.getContentText(),
                "IMAGE".equals(normalizedMessageType) ? readImagePayload(row.getPayloadJson()) : null,
                resources,
                row.getCreatedAt()
        );
    }

    private ChatMessagePayloadDto readImagePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            ChatMessagePayload payload = objectMapper.readValue(payloadJson, ChatMessagePayload.class);
            return toPayloadDto(payload);
        } catch (JacksonException ex) {
            return null;
        }
    }

    private ChatMessagePayloadDto toPayloadDto(ChatMessagePayload payload) {
        if (payload == null) {
            return null;
        }
        return new ChatMessagePayloadDto(
                payload.getPrompt(),
                payload.getProvider(),
                payload.getProviderRequestId(),
                payload.getModel(),
                payload.getAspectRatio(),
                payload.getStatus(),
                payload.getTriggerSource(),
                payload.getSourceResourceId(),
                payload.getParentImageMessageId(),
                payload.getOperationType()
        );
    }

    private String normalizeMessageType(String messageType, String roleCode) {
        if (messageType != null && !messageType.isBlank()) {
            return messageType;
        }
        return switch (roleCode) {
            case "assistant", "tool", "custom", "system" -> "AI";
            case "blocked" -> "SYSTEM";
            default -> "USER";
        };
    }

    private String normalizeRole(String roleCode) {
        return switch (roleCode) {
            case "assistant", "tool", "custom" -> "assistant";
            case "system" -> "system";
            case "blocked" -> "blocked";
            default -> "user";
        };
    }
}
