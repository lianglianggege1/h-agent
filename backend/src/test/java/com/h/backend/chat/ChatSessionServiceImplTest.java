package com.h.backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.entity.ChatSessionEntity;
import com.h.backend.chat.entity.ChatSessionMessageEntity;
import com.h.backend.chat.mapper.ChatSessionMapper;
import com.h.backend.chat.mapper.ChatSessionMessageMapper;
import com.h.backend.chat.service.ChatMemorySnapshotService;
import com.h.backend.chat.service.SystemPromptService;
import com.h.backend.chat.service.impl.ChatSessionServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionServiceImplTest {

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

        service.createSession(1L, null, "session-old");

        verify(chatSessionMapper, never()).deleteById(11L);
        verify(chatSessionMapper).updateById(currentSession);
        assertEquals("ARCHIVED", currentSession.getStatus());
    }
}
