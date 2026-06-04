package com.h.backend.chat.controller;

import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.dto.ChatMessageRequest;
import com.h.backend.chat.service.ChatService;
import com.h.backend.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Duration STREAM_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final ChatService chatService;
    private final Duration streamHeartbeatInterval;

    @Autowired
    public ChatController(ChatService chatService) {
        this(chatService, STREAM_HEARTBEAT_INTERVAL);
    }

    public ChatController(ChatService chatService, Duration streamHeartbeatInterval) {
        this.chatService = chatService;
        this.streamHeartbeatInterval = streamHeartbeatInterval;
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> streamMessage(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @Valid @RequestBody ChatMessageRequest request
    ) {
        Flux<ServerSentEvent<ChatStreamEvent>> chatEvents = chatService.streamChat(
                principal.userId(),
                request.promptId(),
                request.sessionId(),
                request.message().trim()
        )
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());

        Flux<ServerSentEvent<ChatStreamEvent>> heartbeats = Flux.interval(streamHeartbeatInterval)
                .map(tick -> ServerSentEvent.<ChatStreamEvent>builder()
                        .comment("keepalive")
                        .build());

        return Flux.merge(chatEvents, heartbeats)
                .takeUntil(event -> event.event() != null
                        && ("done".equals(event.event())
                        || "error".equals(event.event())
                        || "blocked".equals(event.event())));
    }
}
