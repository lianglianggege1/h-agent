package com.h.backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.agent.ChatAgentIds;
import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.entity.ChatSessionEntity;
import com.h.backend.chat.entity.ChatSessionMessageEntity;
import com.h.backend.chat.entity.ChatMessageResourceEntity;
import com.h.backend.chat.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.mapper.ChatSessionMapper;
import com.h.backend.chat.mapper.ChatSessionMessageMapper;
import com.h.backend.chat.model.ChatMessagePayload;
import com.h.backend.chat.service.ChatMemorySnapshotService;
import com.h.backend.chat.service.SystemPromptService;
import com.h.backend.chat.service.impl.ChatSessionServiceImpl;
import com.h.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionServiceImplTest {

    @Test
    void createSessionStoresAgentId() {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatSessionMessageMapper messageMapper = mock(ChatSessionMessageMapper.class);
        ChatMemorySnapshotService snapshotService = mock(ChatMemorySnapshotService.class);
        SystemPromptService promptService = mock(SystemPromptService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(promptService.resolvePromptId(1L, null)).thenReturn(10L);

        ChatSessionServiceImpl service = new ChatSessionServiceImpl(
                sessionMapper,
                messageMapper,
                snapshotService,
                promptService,
                objectMapper
        );

        service.createSession(1L, null, "car-rental-assistant", null);

        ArgumentCaptor<ChatSessionEntity> captor = ArgumentCaptor.forClass(ChatSessionEntity.class);
        verify(sessionMapper).insert(captor.capture());
        assertEquals("car-rental-assistant", captor.getValue().getAgentId());
    }

    @Test
    void assertActiveSessionRejectsMismatchedAgentId() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setUserId(1L);
        session.setSessionId("s1");
        session.setPromptId(null);
        session.setAgentId("car-rental-assistant");
        session.setStatus("ACTIVE");

        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        when(sessionMapper.selectBySessionId("s1")).thenReturn(session);

        ChatSessionServiceImpl service = new ChatSessionServiceImpl(
                sessionMapper,
                mock(ChatSessionMessageMapper.class),
                mock(ChatMemorySnapshotService.class),
                mock(SystemPromptService.class),
                new ObjectMapper()
        );

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.assertActiveSession(1L, "s1", null, ChatAgentIds.STANDARD_CHAT)
        );
        assertTrue(ex.getMessage().contains("会话不属于当前 Agent"));
    }

    @Test
    void shouldMapImageMessageWithResourceMetadata() throws Exception {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        ChatSessionMessageMapper chatSessionMessageMapper = mock(ChatSessionMessageMapper.class);
        ChatMessageResourceMapper chatMessageResourceMapper = mock(ChatMessageResourceMapper.class);
        ChatMemorySnapshotService chatMemorySnapshotService = mock(ChatMemorySnapshotService.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(
                chatSessionMapper,
                chatSessionMessageMapper,
                chatMessageResourceMapper,
                chatMemorySnapshotService,
                systemPromptService,
                objectMapper
        );

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(11L);
        session.setUserId(1L);
        session.setSessionId("session-1");
        session.setPromptId(22L);
        session.setTitle("图片会话");
        session.setStatus("ACTIVE");
        session.setMessageCount(2);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        ChatSessionMessageEntity imageRow = new ChatSessionMessageEntity();
        imageRow.setId(501L);
        imageRow.setSessionRecordId(11L);
        imageRow.setSessionId("session-1");
        imageRow.setUserId(1L);
        imageRow.setSequenceNo(2);
        imageRow.setMessageType("IMAGE");
        imageRow.setRoleCode("assistant");
        imageRow.setContentText("一只白猫");
        imageRow.setPayloadJson("""
                {"prompt":"一只白猫","provider":"MINIMAX","model":"image-01","aspectRatio":"1:1","status":"READY","triggerSource":"COMMAND"}
                """);
        imageRow.setCreatedAt(LocalDateTime.now());

        ChatMessageResourceEntity resourceRow = new ChatMessageResourceEntity();
        resourceRow.setId("resource-701");
        resourceRow.setMessageId(501L);
        resourceRow.setUserId(1L);
        resourceRow.setSessionId("session-1");
        resourceRow.setResourceKind("IMAGE");
        resourceRow.setStorageType("LOCAL_FILE");
        resourceRow.setStorageKey("generated-images/2026/05/27/cat.png");
        resourceRow.setViewUrl("/api/chat/resources/resource-701/content");
        resourceRow.setDownloadUrl("/api/chat/resources/resource-701/download");
        resourceRow.setMimeType("image/png");
        resourceRow.setFileName("generated-cat.png");
        resourceRow.setFileSize(1234L);
        resourceRow.setWidth(1024);
        resourceRow.setHeight(1024);
        resourceRow.setSha256("abc");
        resourceRow.setCreatedAt(LocalDateTime.now());

        when(chatSessionMapper.selectList(any())).thenReturn(List.of());
        when(chatSessionMapper.selectBySessionId("session-1")).thenReturn(session);
        when(chatSessionMessageMapper.selectPageBySessionRecordId(11L, 20, null)).thenReturn(List.of(imageRow));
        when(chatMessageResourceMapper.selectByMessageIds(List.of(501L))).thenReturn(List.of(resourceRow));

        var page = service.getSessionMessages(1L, "session-1", 20, null);

        var dto = page.messages().getFirst();
        assertEquals("assistant", dto.role());
        assertEquals("IMAGE", dto.messageType());
        assertEquals("一只白猫", dto.content());
        assertEquals("MINIMAX", dto.payload().provider());
        assertEquals(1, dto.resources().size());
        assertEquals("/api/chat/resources/resource-701/content", dto.resources().getFirst().viewUrl());
        assertEquals("/api/chat/resources/resource-701/download", dto.resources().getFirst().downloadUrl());
    }

    @Test
    void shouldPersistImageMessageAndResourceRows() throws Exception {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        ChatSessionMessageMapper chatSessionMessageMapper = mock(ChatSessionMessageMapper.class);
        ChatMessageResourceMapper chatMessageResourceMapper = mock(ChatMessageResourceMapper.class);
        ChatMemorySnapshotService chatMemorySnapshotService = mock(ChatMemorySnapshotService.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(
                chatSessionMapper,
                chatSessionMessageMapper,
                chatMessageResourceMapper,
                chatMemorySnapshotService,
                systemPromptService,
                objectMapper
        );

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(11L);
        session.setUserId(1L);
        session.setSessionId("session-1");
        session.setPromptId(22L);
        session.setTitle("图片会话");
        session.setStatus("ACTIVE");
        session.setMessageCount(1);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        when(chatSessionMapper.selectBySessionId("session-1")).thenReturn(session);
        doAnswer(invocation -> {
            ChatSessionMessageEntity row = invocation.getArgument(0);
            row.setId(501L);
            return 1;
        }).when(chatSessionMessageMapper).insert(any(ChatSessionMessageEntity.class));

        ChatMessagePayload payload = new ChatMessagePayload();
        payload.setPrompt("一只白猫");
        payload.setProvider("MINIMAX");
        payload.setModel("image-01");
        payload.setAspectRatio("1:1");
        payload.setStatus("READY");
        payload.setTriggerSource("COMMAND");

        ChatMessageResourceDto resource = new ChatMessageResourceDto(
                "resource-701",
                "IMAGE",
                "/api/chat/resources/resource-701/content",
                "/api/chat/resources/resource-701/download",
                "generated-cat.png",
                "image/png",
                1234L,
                1024,
                1024,
                "LOCAL_FILE",
                "generated-images/2026/05/27/cat.png",
                "abc"
        );

        var message = service.appendImageMessage(1L, "session-1", "一只白猫", payload, List.of(resource));

        assertEquals("IMAGE", message.messageType());
        assertEquals("一只白猫", message.content());
        assertEquals("/api/chat/resources/resource-701/download", message.resources().getFirst().downloadUrl());
        verify(chatSessionMessageMapper).insert(any(ChatSessionMessageEntity.class));
        ArgumentCaptor<ChatMessageResourceEntity> resourceCaptor = ArgumentCaptor.forClass(ChatMessageResourceEntity.class);
        verify(chatMessageResourceMapper).insert(resourceCaptor.capture());
        ChatMessageResourceEntity resourceRow = resourceCaptor.getValue();
        assertEquals("LOCAL_FILE", resourceRow.getStorageType());
        assertEquals("generated-images/2026/05/27/cat.png", resourceRow.getStorageKey());
        assertEquals("abc", resourceRow.getSha256());
    }

    @Test
    void shouldPersistReasoningMessageWithReasoningTypeAndAssistantRole() throws Exception {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        ChatSessionMessageMapper chatSessionMessageMapper = mock(ChatSessionMessageMapper.class);
        ChatMemorySnapshotService chatMemorySnapshotService = mock(ChatMemorySnapshotService.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(
                chatSessionMapper,
                chatSessionMessageMapper,
                chatMemorySnapshotService,
                systemPromptService,
                objectMapper
        );

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(11L);
        session.setUserId(1L);
        session.setSessionId("session-1");
        session.setPromptId(22L);
        session.setTitle("新会话");
        session.setStatus("ACTIVE");
        session.setMessageCount(1);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        when(chatSessionMapper.selectBySessionId("session-1")).thenReturn(session);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"messageType\":\"REASONING\"}");
        doAnswer(invocation -> {
            ChatSessionMessageEntity row = invocation.getArgument(0);
            row.setId(404L);
            return 1;
        }).when(chatSessionMessageMapper).insert(any(ChatSessionMessageEntity.class));

        Long reasoningMessageId = service.appendReasoningMessage(1L, "session-1", "先拆解问题，再给答案");

        assertEquals(404L, reasoningMessageId);

        ArgumentCaptor<ChatSessionMessageEntity> rowCaptor = ArgumentCaptor.forClass(ChatSessionMessageEntity.class);
        verify(chatSessionMessageMapper).insert(rowCaptor.capture());

        ChatSessionMessageEntity reasoningRow = rowCaptor.getValue();
        assertEquals(2, reasoningRow.getSequenceNo());
        assertEquals("REASONING", reasoningRow.getMessageType());
        assertEquals("assistant", reasoningRow.getRoleCode());
        assertEquals("先拆解问题，再给答案", reasoningRow.getContentText());
        assertEquals("{\"messageType\":\"REASONING\"}", reasoningRow.getPayloadJson());
        assertNotNull(reasoningRow.getCreatedAt());

        assertEquals(2, session.getMessageCount());
        verify(chatSessionMapper).updateById(session);
    }

    @Test
    void shouldExposeReasoningMessageTypeWhenReadingHistory() throws Exception {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        ChatSessionMessageMapper chatSessionMessageMapper = mock(ChatSessionMessageMapper.class);
        ChatMemorySnapshotService chatMemorySnapshotService = mock(ChatMemorySnapshotService.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(
                chatSessionMapper,
                chatSessionMessageMapper,
                chatMemorySnapshotService,
                systemPromptService,
                objectMapper
        );

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(11L);
        session.setUserId(1L);
        session.setSessionId("session-1");
        session.setPromptId(22L);
        session.setStatus("ACTIVE");
        session.setMessageCount(2);

        ChatSessionMessageEntity reasoningRow = new ChatSessionMessageEntity();
        reasoningRow.setId(501L);
        reasoningRow.setSessionRecordId(11L);
        reasoningRow.setSessionId("session-1");
        reasoningRow.setUserId(1L);
        reasoningRow.setSequenceNo(1);
        reasoningRow.setMessageType("REASONING");
        reasoningRow.setRoleCode("assistant");
        reasoningRow.setContentText("先列约束");
        reasoningRow.setPayloadJson("{\"messageType\":\"REASONING\"}");
        reasoningRow.setCreatedAt(LocalDateTime.now());

        ChatSessionMessageEntity answerRow = new ChatSessionMessageEntity();
        answerRow.setId(502L);
        answerRow.setSessionRecordId(11L);
        answerRow.setSessionId("session-1");
        answerRow.setUserId(1L);
        answerRow.setSequenceNo(2);
        answerRow.setMessageType("AI");
        answerRow.setRoleCode("assistant");
        answerRow.setContentText("最终答案");
        answerRow.setPayloadJson("{\"messageType\":\"ASSISTANT\"}");
        answerRow.setCreatedAt(LocalDateTime.now());

        when(chatSessionMapper.selectList(any())).thenReturn(List.of());
        when(chatSessionMapper.selectBySessionId("session-1")).thenReturn(session);
        when(chatSessionMessageMapper.selectPageBySessionRecordId(11L, 20, null))
                .thenReturn(List.of(answerRow, reasoningRow));

        var page = service.getSessionMessages(1L, "session-1", 20, null);

        assertEquals("REASONING", page.messages().get(0).messageType());
        assertEquals("assistant", page.messages().get(0).role());
        assertEquals("AI", page.messages().get(1).messageType());
    }

    @Test
    void shouldPersistUserThenAssistantMessagesWithExpectedSequenceAndMetadata() throws Exception {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        ChatSessionMessageMapper chatSessionMessageMapper = mock(ChatSessionMessageMapper.class);
        ChatMemorySnapshotService chatMemorySnapshotService = mock(ChatMemorySnapshotService.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(
                chatSessionMapper,
                chatSessionMessageMapper,
                chatMemorySnapshotService,
                systemPromptService,
                objectMapper
        );

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(11L);
        session.setUserId(1L);
        session.setSessionId("session-1");
        session.setPromptId(22L);
        session.setTitle("新会话");
        session.setStatus("ACTIVE");
        session.setMessageCount(0);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        when(chatSessionMapper.selectBySessionId("session-1")).thenReturn(session);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");
        doAnswer(invocation -> {
            ChatSessionMessageEntity row = invocation.getArgument(0);
            if (row.getSequenceNo() == 1) {
                row.setId(101L);
            } else if (row.getSequenceNo() == 2) {
                row.setId(202L);
            }
            return 1;
        }).when(chatSessionMessageMapper).insert(any(ChatSessionMessageEntity.class));

        Long userMessageId = service.appendUserMessage(1L, "session-1", "hello");
        Long assistantMessageId = service.appendAssistantMessage(1L, "session-1", "world");

        assertEquals(101L, userMessageId);
        assertEquals(202L, assistantMessageId);

        ArgumentCaptor<ChatSessionMessageEntity> rowCaptor = ArgumentCaptor.forClass(ChatSessionMessageEntity.class);
        verify(chatSessionMessageMapper, times(2)).insert(rowCaptor.capture());

        ChatSessionMessageEntity userRow = rowCaptor.getAllValues().get(0);
        assertEquals(11L, userRow.getSessionRecordId());
        assertEquals("session-1", userRow.getSessionId());
        assertEquals(1L, userRow.getUserId());
        assertEquals(1, userRow.getSequenceNo());
        assertEquals("USER", userRow.getMessageType());
        assertEquals("user", userRow.getRoleCode());
        assertEquals("hello", userRow.getContentText());
        assertEquals("{\"ok\":true}", userRow.getPayloadJson());
        assertNotNull(userRow.getCreatedAt());

        ChatSessionMessageEntity assistantRow = rowCaptor.getAllValues().get(1);
        assertEquals(11L, assistantRow.getSessionRecordId());
        assertEquals("session-1", assistantRow.getSessionId());
        assertEquals(1L, assistantRow.getUserId());
        assertEquals(2, assistantRow.getSequenceNo());
        assertEquals("AI", assistantRow.getMessageType());
        assertEquals("assistant", assistantRow.getRoleCode());
        assertEquals("world", assistantRow.getContentText());
        assertEquals("{\"ok\":true}", assistantRow.getPayloadJson());
        assertNotNull(assistantRow.getCreatedAt());

        assertEquals(2, session.getMessageCount());
        assertEquals("hello", session.getLastUserMessage());
        verify(chatSessionMapper, times(2)).updateById(session);
    }

    @Test
    void shouldArchiveSessionWithMessagesEvenWhenLastUserMessageIsBlank() throws Exception {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        ChatSessionMessageMapper chatSessionMessageMapper = mock(ChatSessionMessageMapper.class);
        ChatMemorySnapshotService chatMemorySnapshotService = mock(ChatMemorySnapshotService.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(
                chatSessionMapper,
                chatSessionMessageMapper,
                chatMemorySnapshotService,
                systemPromptService,
                objectMapper
        );

        ChatSessionEntity currentSession = new ChatSessionEntity();
        currentSession.setId(11L);
        currentSession.setUserId(1L);
        currentSession.setSessionId("session-old");
        currentSession.setPromptId(22L);
        currentSession.setTitle("旧会话");
        currentSession.setStatus("ACTIVE");
        currentSession.setMessageCount(1);
        currentSession.setLastUserMessage(null);
        currentSession.setCreatedAt(LocalDateTime.now());
        currentSession.setUpdatedAt(LocalDateTime.now());

        when(chatSessionMapper.selectList(any())).thenReturn(java.util.List.of());
        when(chatSessionMapper.selectBySessionId("session-old")).thenReturn(currentSession);
        when(systemPromptService.resolvePromptId(1L, null)).thenReturn(99L);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");
        doAnswer(invocation -> {
            ChatSessionEntity inserted = invocation.getArgument(0);
            inserted.setId(22L);
            return 1;
        }).when(chatSessionMapper).insert(any(ChatSessionEntity.class));

        service.createSession(1L, null, null, "session-old");

        verify(chatSessionMapper, never()).deleteById(11L);
        verify(chatSessionMapper).updateById(currentSession);
        assertEquals("ARCHIVED", currentSession.getStatus());
    }
}
