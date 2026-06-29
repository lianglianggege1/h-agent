package com.h.backend.chat;

import com.h.backend.chat.config.ChatStreamProperties;
import com.h.backend.chat.controller.ChatController;
import com.h.backend.chat.dto.ChatMessageRequest;
import com.h.backend.chat.dto.ChatMessageResourceUseDto;
import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.ChatService;
import com.h.backend.security.AuthUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

        ResponseEntity<StreamingResponseBody> response = controller.streamMessage(principal, request);

        assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, controller.getClass()
                .getDeclaredMethod("streamMessage", AuthUserPrincipal.class, ChatMessageRequest.class)
                .getAnnotation(PostMapping.class).produces()[0]);
    }

    @Test
    void shouldExposeBlockedEventFromChatService() throws IOException {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(new ChatStreamEvent("blocked", "平台检测到您的消息不符合使用规范，已自动拦截。")));

        String body = streamBody(controller, principal, request);

        assertTrue(body.contains("event: blocked\n"));
        assertTrue(body.contains("\"content\":\"平台检测到您的消息不符合使用规范，已自动拦截。\""));
    }

    @Test
    void shouldExposeReasoningEventFromChatService() throws IOException {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(
                        new ChatStreamEvent("reasoning", "先看约束"),
                        new ChatStreamEvent("done", "")
                ));

        String body = streamBody(controller, principal, request);

        assertTrue(body.contains("event: reasoning\n"));
        assertTrue(body.contains("\"content\":\"先看约束\""));
    }

    @Test
    void shouldExposeErrorEventFromChatService() throws IOException {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(new ChatStreamEvent("error", "boom")));

        String body = streamBody(controller, principal, request);

        assertTrue(body.contains("event: error\n"));
        assertTrue(body.contains("\"content\":\"boom\""));
    }

    @Test
    void shouldMapChunkAndDoneEventsToServerSentEvents() throws IOException {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(
                        new ChatStreamEvent("chunk", "he"),
                        new ChatStreamEvent("done", "")
                ));

        String body = streamBody(controller, principal, request);

        assertTrue(body.contains("event: chunk\n"));
        assertTrue(body.contains("\"content\":\"he\""));
        assertTrue(body.contains("event: done\n"));
    }

    @Test
    void shouldTrimRequestMessageBeforeDelegatingToService() throws IOException {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(new ChatStreamEvent("done", "")));

        streamBody(controller, principal, request("  hello  ", "session-1", 2L, "standard-chat"));

        verify(chatService).streamChat(1L, 2L, "standard-chat", "session-1", "hello", null);
    }

    @Test
    void shouldForwardStructuredResourcesToChatService() throws IOException {
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
                .writeTo(new ByteArrayOutputStream());

        verify(chatService).streamChat(1L, 2L, "standard-chat", "session-1", "hello", resources);
    }

    @Test
    void shouldForwardAgentIdToChatService() throws IOException {
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
                .writeTo(new ByteArrayOutputStream());

        verify(chatService).streamChat(1L, null, "car-rental-assistant", "session-car", "need towing", null);
    }

    @Test
    void shouldEmitHeartbeatBeforeDelayedDoneEvent() throws IOException {
        ChatService chatService = mock(ChatService.class);
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setHeartbeatInterval(Duration.ofMillis(200));
        ChatController controller = new ChatController(chatService, properties);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(new ChatStreamEvent("done", ""))
                        .delaySubscription(Duration.ofMillis(450)));

        String body = streamBody(controller, principal, request);

        assertTrue(body.indexOf(":keepalive\n") < body.indexOf("event: done\n"));
    }

    @Test
    void shouldWriteAndFlushEachServerSentEventImmediately() throws IOException {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, defaultProperties());

        FlushCountingOutputStream outputStream = new FlushCountingOutputStream();

        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        ChatMessageRequest request = request("hello", "session-1", 2L, "standard-chat");

        when(chatService.streamChat(1L, 2L, "standard-chat", "session-1", "hello", null))
                .thenReturn(Flux.just(
                        new ChatStreamEvent("chunk", "he"),
                        new ChatStreamEvent("done", "")
                ));

        controller.streamMessage(principal, request).getBody().writeTo(outputStream);

        String body = outputStream.toString();
        assertTrue(body.contains("event: chunk\n"));
        assertTrue(body.contains("data: {\"type\":\"chunk\",\"content\":\"he\""));
        assertTrue(body.contains("event: done\n"));
        assertEquals(2, outputStream.flushCount());
    }

    private String streamBody(
            ChatController controller,
            AuthUserPrincipal principal,
            ChatMessageRequest request
    ) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        controller.streamMessage(principal, request).getBody().writeTo(outputStream);
        return outputStream.toString();
    }

    private static class FlushCountingOutputStream extends ByteArrayOutputStream {
        private int flushCount;

        @Override
        public void flush() throws IOException {
            flushCount++;
            super.flush();
        }

        int flushCount() {
            return flushCount;
        }
    }
}
