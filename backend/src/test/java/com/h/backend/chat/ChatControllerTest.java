package com.h.backend.chat;

import com.h.backend.chat.controller.ChatController;
import com.h.backend.chat.dto.ChatMessageRequest;
import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.ChatService;
import com.h.backend.security.AuthUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    void shouldExposeTextEventStreamContentType() throws Exception {
        ChatService chatService = mock(ChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatController controller = new ChatController(chatService, objectMapper);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = new ChatMessageRequest("hello", "session-1", 2L);

        when(chatService.streamChat(1L, 2L, "session-1", "hello"))
                .thenReturn(Flux.just(new ChatStreamEvent("done", "")));

        Flux<ServerSentEvent<ChatStreamEvent>> response = controller.streamMessage(principal, request);

        List<ServerSentEvent<ChatStreamEvent>> events = response.collectList().block();
        assertEquals("done", events.getFirst().event());
        assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, controller.getClass()
                .getDeclaredMethod("streamMessage", AuthUserPrincipal.class, ChatMessageRequest.class)
                .getAnnotation(PostMapping.class).produces()[0]);
    }

    @Test
    void shouldExposeBlockedEventFromChatService() {
        ChatService chatService = mock(ChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatController controller = new ChatController(chatService, objectMapper);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = new ChatMessageRequest("hello", "session-1", 2L);

        when(chatService.streamChat(1L, 2L, "session-1", "hello"))
                .thenReturn(Flux.just(new ChatStreamEvent("blocked", "平台检测到您的消息不符合使用规范，已自动拦截。")));

        Flux<ServerSentEvent<ChatStreamEvent>> response = controller.streamMessage(principal, request);

        List<ServerSentEvent<ChatStreamEvent>> events = response.collectList().block();
        assertEquals("blocked", events.getFirst().event());
        assertEquals("平台检测到您的消息不符合使用规范，已自动拦截。", events.getFirst().data().content());
    }

    @Test
    void shouldExposeErrorEventFromChatService() {
        ChatService chatService = mock(ChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatController controller = new ChatController(chatService, objectMapper);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = new ChatMessageRequest("hello", "session-1", 2L);

        when(chatService.streamChat(1L, 2L, "session-1", "hello"))
                .thenReturn(Flux.just(new ChatStreamEvent("error", "boom")));

        Flux<ServerSentEvent<ChatStreamEvent>> response = controller.streamMessage(principal, request);

        List<ServerSentEvent<ChatStreamEvent>> events = response.collectList().block();
        assertEquals("error", events.getFirst().event());
        assertEquals("boom", events.getFirst().data().content());
    }
}
