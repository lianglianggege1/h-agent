package com.h.backend.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.dto.ChatSessionBootstrapDto;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.dto.ChatSessionMessagesPageDto;
import com.h.backend.chat.dto.ChatSessionMetaDto;
import com.h.backend.chat.dto.ChatSessionOpenDto;
import com.h.backend.chat.dto.ChatSessionSummaryDto;
import com.h.backend.chat.entity.ChatSessionEntity;
import com.h.backend.chat.entity.ChatSessionMessageEntity;
import com.h.backend.chat.mapper.ChatSessionMapper;
import com.h.backend.chat.mapper.ChatSessionMessageMapper;
import com.h.backend.chat.model.ChatSessionMessage;
import com.h.backend.chat.service.ChatMemorySnapshotService;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.SystemPromptService;
import com.h.backend.common.exception.BusinessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ChatSessionServiceImpl implements ChatSessionService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final int DEFAULT_MESSAGE_PAGE_SIZE = 20;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionMessageMapper chatSessionMessageMapper;
    private final ChatMemorySnapshotService chatMemorySnapshotService;
    private final SystemPromptService systemPromptService;
    private final ObjectMapper objectMapper;

    public ChatSessionServiceImpl(
            ChatSessionMapper chatSessionMapper,
            ChatSessionMessageMapper chatSessionMessageMapper,
            ChatMemorySnapshotService chatMemorySnapshotService,
            SystemPromptService systemPromptService,
            ObjectMapper objectMapper
    ) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatSessionMessageMapper = chatSessionMessageMapper;
        this.chatMemorySnapshotService = chatMemorySnapshotService;
        this.systemPromptService = systemPromptService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ChatSessionBootstrapDto bootstrap(Long userId) {
        archiveExpiredSessionsForUser(userId);
        List<ChatSessionEntity> activeSessions = chatSessionMapper.selectActiveByUserId(userId);
        if (activeSessions.isEmpty()) {
            return new ChatSessionBootstrapDto("created", createSession(userId, null, null), List.of());
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
    public ChatSessionOpenDto createSession(Long userId, Long promptId, String currentSessionId) {
        archiveExpiredSessionsForUser(userId);
        if (StringUtils.isNotBlank(currentSessionId)) {
            ChatSessionEntity current = requireOwnedSession(userId, currentSessionId);
            archiveOrDeleteIfEmpty(current);
        }

        Long resolvedPromptId = systemPromptService.resolvePromptId(userId, promptId);
        LocalDateTime now = LocalDateTime.now();
        ChatSessionEntity entity = new ChatSessionEntity();
        entity.setUserId(userId);
        entity.setSessionId(UUID.randomUUID().toString());
        entity.setPromptId(resolvedPromptId);
        entity.setTitle("新会话");
        entity.setStatus(STATUS_ACTIVE);
        entity.setLastUserMessage(null);
        entity.setMessageCount(0);
        entity.setLastActiveAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
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
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        return buildMessagesPage(session, Math.max(limit, 1), beforeSeq);
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
    public void assertActiveSession(Long userId, String sessionId, Long promptId) {
        archiveExpiredSessionsForUser(userId);
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        if (!STATUS_ACTIVE.equals(session.getStatus())) {
            throw new BusinessException(40005, "会话已失效，请重新进入聊天页");
        }
        Long resolvedPromptId = systemPromptService.resolvePromptId(userId, promptId);
        if (!resolvedPromptId.equals(session.getPromptId())) {
            throw new BusinessException(40006, "会话提示词不匹配，请重新创建会话");
        }
    }

    @Override
    @Transactional
    public void appendConversation(Long userId, String sessionId, String userMessage, String assistantMessage) {
        ChatSessionEntity session = requireOwnedSession(userId, sessionId);
        if (!STATUS_ACTIVE.equals(session.getStatus())) {
            throw new BusinessException(40005, "会话已失效，请重新进入聊天页");
        }

        int nextSequence = session.getMessageCount() == null ? 1 : session.getMessageCount() + 1;
        LocalDateTime now = LocalDateTime.now();
        persistMessage(session, buildMessage("user", userMessage, now, nextSequence));
        persistMessage(session, buildMessage("assistant", assistantMessage, now, nextSequence + 1));

        session.setMessageCount(nextSequence + 1);
        session.setLastUserMessage(userMessage);
        session.setLastActiveAt(now);
        session.setUpdatedAt(now);
        if (session.getTitle() == null || "新会话".equals(session.getTitle())) {
            session.setTitle(buildTitle(userMessage));
        }
        chatSessionMapper.updateById(session);
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

    private void archiveOrDeleteIfEmpty(ChatSessionEntity session) {
        if ((session.getMessageCount() == null ? 0 : session.getMessageCount()) <= 0
                || StringUtils.isBlank(session.getLastUserMessage())) {
            chatMemorySnapshotService.evict(session.getSessionId());
            chatMemorySnapshotService.deleteSnapshot(session.getSessionId());
            chatSessionMessageMapper.delete(new QueryWrapper<ChatSessionMessageEntity>().eq("session_record_id", session.getId()));
            chatSessionMapper.deleteById(session.getId());
            return;
        }
        chatMemorySnapshotService.flushNow(session.getSessionId());
        chatMemorySnapshotService.evict(session.getSessionId());
        session.setStatus(STATUS_ARCHIVED);
        session.setUpdatedAt(LocalDateTime.now());
        chatSessionMapper.updateById(session);
    }

    private ChatSessionEntity requireOwnedSession(Long userId, String sessionId) {
        ChatSessionEntity session = chatSessionMapper.selectBySessionId(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BusinessException(40404, "会话不存在");
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
        return new ChatSessionOpenDto(
                toMeta(session),
                buildMessagesPage(session, limit, beforeSeq)
        );
    }

    private ChatSessionMetaDto toMeta(ChatSessionEntity session) {
        return new ChatSessionMetaDto(
                session.getSessionId(),
                session.getTitle(),
                session.getPromptId(),
                session.getMessageCount() == null ? 0 : session.getMessageCount(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                !STATUS_ACTIVE.equals(session.getStatus())
        );
    }

    private ChatSessionSummaryDto toSummary(ChatSessionEntity session) {
        return new ChatSessionSummaryDto(
                session.getSessionId(),
                session.getTitle(),
                session.getLastUserMessage(),
                session.getPromptId(),
                session.getMessageCount() == null ? 0 : session.getMessageCount(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                !STATUS_ACTIVE.equals(session.getStatus())
        );
    }

    private ChatSessionMessagesPageDto buildMessagesPage(ChatSessionEntity session, int limit, Integer beforeSeq) {
        List<ChatSessionMessageEntity> rows = chatSessionMessageMapper.selectPageBySessionRecordId(session.getId(), limit, beforeSeq);
        List<ChatSessionMessageEntity> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(ChatSessionMessageEntity::getSequenceNo));
        List<ChatSessionMessageDto> messages = ordered.stream()
                .map(this::toMessageDto)
                .toList();
        boolean hasMore = !ordered.isEmpty() && ordered.get(0).getSequenceNo() > 1;
        Integer nextBefore = hasMore ? ordered.get(0).getSequenceNo() : null;
        return new ChatSessionMessagesPageDto(session.getSessionId(), messages, hasMore, nextBefore);
    }

    private ChatSessionMessage buildMessage(String role, String content, LocalDateTime createdAt, int sequenceNo) {
        ChatSessionMessage message = new ChatSessionMessage();
        message.setId(UUID.randomUUID().toString());
        message.setSequenceNo(sequenceNo);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(createdAt);
        return message;
    }

    private void persistMessage(ChatSessionEntity session, ChatSessionMessage message) {
        ChatSessionMessageEntity row = new ChatSessionMessageEntity();
        row.setSessionRecordId(session.getId());
        row.setSessionId(session.getSessionId());
        row.setUserId(session.getUserId());
        row.setSequenceNo(message.getSequenceNo());
        row.setMessageType("assistant".equals(message.getRole()) ? "AI" : "USER");
        row.setRoleCode(message.getRole());
        row.setContentText(message.getContent());
        row.setPayloadJson(writeMessagePayload(message));
        row.setCreatedAt(message.getCreatedAt());
        chatSessionMessageMapper.insert(row);
    }

    private String writeMessagePayload(ChatSessionMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize chat session message payload", ex);
        }
    }

    private String buildTitle(String userMessage) {
        String compact = userMessage == null ? "" : userMessage.trim().replaceAll("\\s+", " ");
        if (compact.isBlank()) {
            return "新会话";
        }
        return compact.length() <= 20 ? compact : compact.substring(0, 20);
    }

    private ChatSessionMessageDto toMessageDto(ChatSessionMessageEntity row) {
        return new ChatSessionMessageDto(
                row.getId() == null ? UUID.randomUUID().toString() : String.valueOf(row.getId()),
                normalizeRole(row.getRoleCode()),
                row.getContentText() == null ? "" : row.getContentText(),
                row.getCreatedAt()
        );
    }

    private String normalizeRole(String roleCode) {
        return switch (roleCode) {
            case "assistant", "tool", "custom", "system" -> "assistant";
            default -> "user";
        };
    }
}
