package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.infrastructure.config.ChatStreamProperties;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.interfaces.dto.ChatMessageRequest;
import com.h.backend.chat.application.ChatService;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    private final ChatService chatService;
    private final Duration heartbeatInterval;

    @Autowired
    public ChatController(ChatService chatService, ChatStreamProperties properties) {
        this(chatService, properties.getHeartbeatInterval());
    }

    ChatController(ChatService chatService, Duration heartbeatInterval) {
        this.chatService = chatService;
        this.heartbeatInterval = heartbeatInterval;
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> streamMessage(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @Valid @RequestBody ChatMessageRequest request
    ) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache().cachePrivate().noTransform())
                .header("X-Accel-Buffering", "no")
                .body(streamEvents(principal, request));
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> streamEvents(
            AuthUserPrincipal principal,
            ChatMessageRequest request
    ) {
        Flux<ChatStreamEvent> stream = chatService.streamChat(
                principal.userId(),
                request.promptId(),
                request.agentId(),
                request.sessionId(),
                request.message().trim(),
                request.resources()
        );
        Flux<ServerSentEvent<ChatStreamEvent>> chatEvents = stream
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());

        Flux<ServerSentEvent<ChatStreamEvent>> heartbeats = Flux.interval(heartbeatInterval)
                .map(tick -> ServerSentEvent.<ChatStreamEvent>builder()
                        .comment("keepalive")
                        .build());

        ServerSentEvent<ChatStreamEvent> initialHeartbeat = ServerSentEvent.<ChatStreamEvent>builder()
                .comment("keepalive")
                .build();

        return Flux.concat(
                Flux.just(initialHeartbeat),
                Flux.merge(chatEvents, heartbeats)
                        .takeUntil(event -> event.event() != null
                                && ("done".equals(event.event())
                                || "error".equals(event.event())
                                || "blocked".equals(event.event())))
        );
    }
}
