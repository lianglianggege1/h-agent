package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.application.HarnessCollaborationService;
import com.h.backend.chat.application.HarnessExecutionSession;
import com.h.backend.chat.domain.agent.HarnessEventMapper;
import com.h.backend.chat.domain.agent.HarnessSubagentEventRelay;
import com.h.backend.chat.infrastructure.config.ChatStreamProperties;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.interfaces.dto.HarnessAgentEventPayload;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

/** 允许正在查看子会话的页面附着到该 Agent Session 的实时事件。 */
@RestController
@RequestMapping("/api/chat/agent-sessions")
public class HarnessSubagentEventController {

    private final HarnessCollaborationService collaborationService;
    private final HarnessSubagentEventRelay eventRelay;
    private final HarnessEventMapper eventMapper;
    private final Duration heartbeatInterval;

    @Autowired
    public HarnessSubagentEventController(
            HarnessCollaborationService collaborationService,
            HarnessSubagentEventRelay eventRelay,
            HarnessEventMapper eventMapper,
            ChatStreamProperties properties
    ) {
        this(collaborationService, eventRelay, eventMapper, properties.getHeartbeatInterval());
    }

    HarnessSubagentEventController(
            HarnessCollaborationService collaborationService,
            HarnessSubagentEventRelay eventRelay,
            HarnessEventMapper eventMapper,
            Duration heartbeatInterval
    ) {
        this.collaborationService = collaborationService;
        this.eventRelay = eventRelay;
        this.eventMapper = eventMapper;
        this.heartbeatInterval = heartbeatInterval;
    }

    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> events(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String sessionId
    ) {
        HarnessExecutionSession execution = collaborationService.resolveExecutionSession(
                principal.userId(), sessionId
        );
        if (!execution.subagent()) {
            throw new BusinessException(40404, "协作 Agent 会话不存在");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache().cachePrivate().noTransform())
                .header("X-Accel-Buffering", "no")
                .body(streamEvents(principal.userId(), execution.sessionId()));
    }

    Flux<ServerSentEvent<ChatStreamEvent>> streamEvents(Long userId, String sessionId) {
        Flux<HarnessSubagentEventRelay.RelayedEvent> relayed = Flux.create(sink -> {
            Runnable unsubscribe = eventRelay.subscribe(
                    String.valueOf(userId), sessionId, sink::next
            );
            sink.onDispose(unsubscribe::run);
        });
        Flux<ServerSentEvent<ChatStreamEvent>> childEvents = relayed
                .map(event -> toServerSentEvent(sessionId, event));
        Flux<ServerSentEvent<ChatStreamEvent>> heartbeats = Flux.interval(heartbeatInterval)
                .map(tick -> ServerSentEvent.<ChatStreamEvent>builder()
                        .comment("keepalive")
                        .build());
        ServerSentEvent<ChatStreamEvent> initialHeartbeat = ServerSentEvent
                .<ChatStreamEvent>builder()
                .comment("keepalive")
                .build();

        return Flux.concat(
                Flux.just(initialHeartbeat),
                Flux.merge(childEvents, heartbeats).takeUntil(this::isAgentEnd)
        );
    }

    private ServerSentEvent<ChatStreamEvent> toServerSentEvent(
            String sessionId,
            HarnessSubagentEventRelay.RelayedEvent event
    ) {
        ChatStreamEvent mapped = eventMapper.mapObservedSubagent(
                event.streamId(), event.sequence(), event.event(), sessionId
        );
        return ServerSentEvent.<ChatStreamEvent>builder()
                .id(event.streamId() + ":" + event.sequence())
                .event(mapped.type())
                .data(mapped)
                .build();
    }

    private boolean isAgentEnd(ServerSentEvent<ChatStreamEvent> event) {
        ChatStreamEvent data = event.data();
        return data != null
                && data.payload() instanceof HarnessAgentEventPayload payload
                && "AGENT_END".equals(payload.eventType());
    }
}
