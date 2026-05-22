package com.h.backend.chat;

import com.h.backend.chat.controller.ChatController;
import com.h.backend.chat.dto.ChatMessageRequest;
import com.h.backend.chat.service.ChatService;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.security.AuthUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    void shouldEmitBlockedEventWhenChatServiceThrowsBlockedBusinessException() throws Exception {
        ChatService chatService = mock(ChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatController controller = new ChatController(chatService, objectMapper);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = new ChatMessageRequest("  hello  ", "session-1", 2L);

        when(chatService.streamChat(eq(1L), eq(2L), eq("session-1"), eq("hello"), any()))
                .thenThrow(new BusinessException(40301, "平台检测到您的消息不符合使用规范，已自动拦截。"));

        ResponseEntity<StreamingResponseBody> response = controller.streamMessage(principal, request);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        response.getBody().writeTo(outputStream);

        String line = outputStream.toString();
        JsonNode event = objectMapper.readTree(line.trim());
        assertEquals("blocked", event.get("type").asText());
        assertEquals("平台检测到您的消息不符合使用规范，已自动拦截。", event.get("content").asText());
    }

    @Test
    void shouldEmitErrorEventWhenChatServiceThrowsRuntimeException() throws Exception {
        ChatService chatService = mock(ChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatController controller = new ChatController(chatService, objectMapper);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = new ChatMessageRequest("hello", "session-1", 2L);

        when(chatService.streamChat(eq(1L), eq(2L), eq("session-1"), eq("hello"), any()))
                .thenThrow(new RuntimeException("boom"));

        ResponseEntity<StreamingResponseBody> response = controller.streamMessage(principal, request);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        response.getBody().writeTo(outputStream);

        String line = outputStream.toString();
        JsonNode event = objectMapper.readTree(line.trim());
        assertEquals("error", event.get("type").asText());
        assertEquals("boom", event.get("content").asText());
        assertTrue(line.contains("\"type\":\"error\""));
    }
}
