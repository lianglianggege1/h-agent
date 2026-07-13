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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.BaseSubscriber;
import tools.jackson.databind.ObjectMapper;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final Duration heartbeatInterval;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChatController(ChatService chatService, ChatStreamProperties properties, ObjectMapper objectMapper) {
        this(chatService, properties.getHeartbeatInterval(), objectMapper);
    }

    public ChatController(ChatService chatService, ChatStreamProperties properties) {
        this(chatService, properties.getHeartbeatInterval(), new ObjectMapper());
    }

    ChatController(ChatService chatService, Duration heartbeatInterval) {
        this(chatService, heartbeatInterval, new ObjectMapper());
    }

    ChatController(ChatService chatService, Duration heartbeatInterval, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.heartbeatInterval = heartbeatInterval;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamMessage(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @Valid @RequestBody ChatMessageRequest request
    ) {
        Flux<ServerSentEvent<ChatStreamEvent>> events = streamEvents(principal, request);
        StreamingResponseBody body = outputStream -> writeSseEvents(events, outputStream);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache().cachePrivate().noTransform())
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> streamEvents(
            AuthUserPrincipal principal,
            ChatMessageRequest request
    ) {
        Flux<ServerSentEvent<ChatStreamEvent>> chatEvents = chatService.streamChat(
                principal.userId(),
                request.promptId(),
                request.agentId(),
                request.sessionId(),
                request.message().trim(),
                request.resources()
        )
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());

        Flux<ServerSentEvent<ChatStreamEvent>> heartbeats = Flux.interval(heartbeatInterval)
                .map(tick -> ServerSentEvent.<ChatStreamEvent>builder()
                        .comment("keepalive")
                        .build());

        return Flux.merge(chatEvents, heartbeats)
                .takeUntil(event -> event.event() != null
                        && ("done".equals(event.event())
                        || "error".equals(event.event())
                        || "blocked".equals(event.event())));
    }

    private void writeSseEvents(
            Flux<ServerSentEvent<ChatStreamEvent>> events,
            OutputStream outputStream
    ) throws IOException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Disposable subscription = events.subscribeWith(new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(Long.MAX_VALUE);
            }

            @Override
            protected void hookOnNext(ServerSentEvent<ChatStreamEvent> event) {
                try {
                    writeSseEvent(event, outputStream);
                    outputStream.flush();
                } catch (IOException ex) {
                    failure.compareAndSet(null, ex);
                    cancel();
                    completed.countDown();
                }
            }

            @Override
            protected void hookOnError(Throwable throwable) {
                failure.compareAndSet(null, throwable);
                completed.countDown();
            }

            @Override
            protected void hookOnComplete() {
                completed.countDown();
            }
        });

        try {
            completed.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            throw new IOException("Interrupted while streaming SSE response", ex);
        }

        Throwable throwable = failure.get();
        if (throwable instanceof IOException ioException) {
            throw ioException;
        }
        if (throwable != null) {
            throw new IOException("Failed to stream SSE response", throwable);
        }
    }

    private void writeSseEvent(
            ServerSentEvent<ChatStreamEvent> event,
            OutputStream outputStream
    ) throws IOException {
        if (event.comment() != null) {
            writeSseField(outputStream, ":", event.comment());
        }
        if (event.event() != null) {
            writeSseField(outputStream, "event: ", event.event());
        }
        if (event.data() != null) {
            writeSseField(outputStream, "data: ", objectMapper.writeValueAsString(event.data()));
        }
        outputStream.write('\n');
    }

    private void writeSseField(OutputStream outputStream, String prefix, String value) throws IOException {
        for (String line : value.split("\\R", -1)) {
            outputStream.write(prefix.getBytes(StandardCharsets.UTF_8));
            outputStream.write(line.getBytes(StandardCharsets.UTF_8));
            outputStream.write('\n');
        }
    }
}
