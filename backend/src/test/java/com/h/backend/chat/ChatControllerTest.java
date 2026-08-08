package com.h.backend.chat;

import com.h.backend.chat.infrastructure.config.ChatStreamProperties;
import com.h.backend.chat.interfaces.web.ChatController;
import com.h.backend.chat.interfaces.dto.ChatMessageRequest;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.application.ChatService;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    private ChatStreamProperties defaultProperties() {
        return new ChatStreamProperties();
    }

    private ChatMessageRequest request(String message, String sessionId, Long promptId, String agentId) {
        return new ChatMessageRequest(message, sessionId, promptId, agentId, null);
    }

    @Test
    void shouldExposeTextEventStreamContentType() throws Exception {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(new ChatStreamEvent("done", "")));

        ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> response = controller.streamMessage(principal, request);

        assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, controller.getClass()
                .getDeclaredMethod("streamMessage", AuthUserPrincipal.class, ChatMessageRequest.class)
                .getAnnotation(PostMapping.class).produces()[0]);
    }

    @Test
    void shouldExposeBlockedEventFromChatService() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(new ChatStreamEvent("blocked", "平台检测到您的消息不符合使用规范，已自动拦截。")));

        List<ServerSentEvent<ChatStreamEvent>> events = streamEvents(controller, principal, request);

        assertTrue(events.stream().anyMatch(event -> "blocked".equals(event.event())
                && "平台检测到您的消息不符合使用规范，已自动拦截。".equals(event.data().content())));
    }

    @Test
    void shouldExposeReasoningEventFromChatService() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(
                        new ChatStreamEvent("reasoning", "先看约束"),
                        new ChatStreamEvent("done", "")
                ));

        List<ServerSentEvent<ChatStreamEvent>> events = streamEvents(controller, principal, request);

        assertTrue(events.stream().anyMatch(event -> "reasoning".equals(event.event())
                && "先看约束".equals(event.data().content())));
    }

    @Test
    void shouldExposeErrorEventFromChatService() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(new ChatStreamEvent("error", "boom")));

        List<ServerSentEvent<ChatStreamEvent>> events = streamEvents(controller, principal, request);

        assertTrue(events.stream().anyMatch(event -> "error".equals(event.event())
                && "boom".equals(event.data().content())));
    }

    @Test
    void shouldMapChunkAndDoneEventsToServerSentEvents() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(
                        new ChatStreamEvent("chunk", "he"),
                        new ChatStreamEvent("done", "")
                ));

        List<ServerSentEvent<ChatStreamEvent>> events = streamEvents(controller, principal, request);

        assertTrue(events.stream().anyMatch(event -> "chunk".equals(event.event())
                && "he".equals(event.data().content())));
        assertTrue(events.stream().anyMatch(event -> "done".equals(event.event())));
    }

    @Test
    void shouldTrimRequestMessageBeforeDelegatingToService() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(new ChatStreamEvent("done", "")));

        streamEvents(controller, principal, request("  hello  ", "session-1", 2L, "standard-chat"));

        verify(chatService).streamChat(1L, 2L, "standard-chat", "session-1", "hello", null);
    }

    @Test
    void shouldForwardStructuredResourcesToChatService() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        List<ChatMessageResourceUseDto> resources = List.of(
                new ChatMessageResourceUseDto("resource-1", "REFERENCE", "HISTORY")
        );

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", resources))
                .thenReturn(Flux.just(new ChatStreamEvent("done", "")));

        controller.streamMessage(
                        principal,
                        new ChatMessageRequest("hello", "session-1", 2L, "standard-chat", resources)
                )
                .getBody()
                .collectList()
                .block();

        verify(chatService).streamChat(1L, 2L, "standard-chat", "session-1", "hello", resources);
    }

    @Test
    void shouldForwardAgentIdToChatService() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");

        when(chatService.streamChat(1L, null, "car-rental-assistant", "session-car", "need towing", null))
                .thenReturn(Flux.just(new ChatStreamEvent("done", "")));

        controller.streamMessage(
                        principal,
                        request(" need towing ", "session-car", null, "car-rental-assistant")
                )
                .getBody()
                .collectList()
                .block();

        verify(chatService).streamChat(1L, null, "car-rental-assistant", "session-car", "need towing", null);
    }

    @Test
    void shouldEmitHeartbeatBeforeDelayedDoneEvent() {
        ChatService chatService = mock(ChatService.class);
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setHeartbeatInterval(Duration.ofMillis(200));
        ChatController controller = new ChatController(chatService, properties);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(new ChatStreamEvent("done", ""))
                        .delaySubscription(Duration.ofMillis(450)));

        List<ServerSentEvent<ChatStreamEvent>> events = streamEvents(controller, principal, request);

        assertEquals("keepalive", events.getFirst().comment());
        assertTrue(events.stream().anyMatch(event -> "done".equals(event.event())));
    }

    @Test
    void shouldEmitHeartbeatBeforeImmediateDoneEvent() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(new ChatStreamEvent("done", "")));

        List<ServerSentEvent<ChatStreamEvent>> events = streamEvents(controller, principal, request);

        assertEquals("keepalive", events.getFirst().comment());
        assertEquals("done", events.get(1).event());
    }

    @Test
    void shouldReturnFirstHeartbeatWithoutWaitingForChatCompletion() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(new ChatStreamEvent("done", ""))
                        .delaySubscription(Duration.ofSeconds(5)));

        ServerSentEvent<ChatStreamEvent> firstEvent = controller.streamMessage(principal, request)
                .getBody()
                .blockFirst(Duration.ofSeconds(1));

        assertEquals("keepalive", firstEvent.comment());
    }

    private List<ServerSentEvent<ChatStreamEvent>> streamEvents(
            ChatController controller,
            AuthUserPrincipal principal,
            ChatMessageRequest request
    ) {
        return controller.streamMessage(principal, request).getBody().collectList().block();
    }
}
