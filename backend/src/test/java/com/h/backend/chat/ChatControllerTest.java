package com.h.backend.chat;

import com.h.backend.chat.controller.ChatController;
import com.h.backend.chat.dto.ChatMessageRequest;
import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.ChatService;
import com.h.backend.security.AuthUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    void shouldExposeTextEventStreamContentType() throws Exception {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = new ChatMessageRequest("hello", "session-1", 2L);

        when(chatService.streamChat(1L, 2L, "session-1", "hello"))
                .thenReturn(Flux.just(new ChatStreamEvent("done", "")));

        Flux<ServerSentEvent<ChatStreamEvent>> response = controller.streamMessage(principal, request);

        List<ServerSentEvent<ChatStreamEvent>> events = response.collectList().block(Duration.ofSeconds(1));
        assertNotNull(events);
        assertEquals("done", events.getFirst().event());
        assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, controller.getClass()
                .getDeclaredMethod("streamMessage", AuthUserPrincipal.class, ChatMessageRequest.class)
                .getAnnotation(PostMapping.class).produces()[0]);
    }

    @Test
    void shouldExposeBlockedEventFromChatService() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = new ChatMessageRequest("hello", "session-1", 2L);

        when(chatService.streamChat(1L, 2L, "session-1", "hello"))
                .thenReturn(Flux.just(new ChatStreamEvent("blocked", "平台检测到您的消息不符合使用规范，已自动拦截。")));

        Flux<ServerSentEvent<ChatStreamEvent>> response = controller.streamMessage(principal, request);

        List<ServerSentEvent<ChatStreamEvent>> events = response.collectList().block(Duration.ofSeconds(1));
        assertNotNull(events);
        assertEquals("blocked", events.getFirst().event());
        assertEquals("平台检测到您的消息不符合使用规范，已自动拦截。", events.getFirst().data().content());
    }

    @Test
    void shouldExposeErrorEventFromChatService() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = new ChatMessageRequest("hello", "session-1", 2L);

        when(chatService.streamChat(1L, 2L, "session-1", "hello"))
                .thenReturn(Flux.just(new ChatStreamEvent("error", "boom")));

        Flux<ServerSentEvent<ChatStreamEvent>> response = controller.streamMessage(principal, request);

        List<ServerSentEvent<ChatStreamEvent>> events = response.collectList().block(Duration.ofSeconds(1));
        assertNotNull(events);
        assertEquals("error", events.getFirst().event());
        assertEquals("boom", events.getFirst().data().content());
    }

    @Test
    void shouldMapChunkAndDoneEventsToServerSentEvents() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = new ChatMessageRequest("hello", "session-1", 2L);

        when(chatService.streamChat(1L, 2L, "session-1", "hello"))
                .thenReturn(Flux.just(
                        new ChatStreamEvent("chunk", "he"),
                        new ChatStreamEvent("done", "")
                ));

        List<ServerSentEvent<ChatStreamEvent>> events = controller.streamMessage(principal, request)
                .collectList()
                .block(Duration.ofSeconds(1));

        assertNotNull(events);
        assertEquals("chunk", events.get(0).event());
        assertEquals("he", events.get(0).data().content());
        assertEquals("done", events.get(1).event());
        assertEquals("", events.get(1).data().content());
    }

    @Test
    void shouldTrimRequestMessageBeforeDelegatingToService() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");

        when(chatService.streamChat(1L, 2L, "session-1", "hello"))
                .thenReturn(Flux.just(new ChatStreamEvent("done", "")));

        controller.streamMessage(principal, new ChatMessageRequest("  hello  ", "session-1", 2L))
                .collectList()
                .block(Duration.ofSeconds(1));

        org.mockito.Mockito.verify(chatService).streamChat(1L, 2L, "session-1", "hello");
    }

    @Test
    void shouldEmitHeartbeatCommentsWhileWaitingForChatEvents() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = new ChatMessageRequest("hello", "session-1", 2L);

        when(chatService.streamChat(1L, 2L, "session-1", "hello"))
                .thenReturn(Flux.just(new ChatStreamEvent("done", ""))
                        .delaySubscription(Duration.ofMillis(1200)));

        List<ServerSentEvent<ChatStreamEvent>> events = controller.streamMessage(principal, request)
                .take(2)
                .collectList()
                .block(Duration.ofSeconds(3));

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("keepalive", events.get(0).comment());
        assertEquals("done", events.get(1).event());
    }
}
