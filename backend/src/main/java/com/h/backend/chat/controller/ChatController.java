package com.h.backend.chat.controller;

import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.dto.ChatMessageRequest;
import com.h.backend.chat.service.ChatService;
import com.h.backend.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> streamMessage(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @Valid @RequestBody ChatMessageRequest request
    ) {
        return chatService.streamChat(
                        principal.userId(),
                        request.promptId(),
                        request.sessionId(),
                        request.message().trim()
                )
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());
    }
}
