package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.application.HarnessApprovalService;
import com.h.backend.chat.infrastructure.config.ChatStreamProperties;
import com.h.backend.chat.interfaces.dto.ApprovalDecisionRequest;
import com.h.backend.chat.interfaces.dto.ApprovalRequestDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

@RestController
@RequestMapping("/api/chat")
public class HarnessApprovalController {

    private final HarnessApprovalService approvalService;
    private final Duration heartbeatInterval;

    public HarnessApprovalController(
            HarnessApprovalService approvalService,
            ChatStreamProperties properties
    ) {
        this.approvalService = approvalService;
        this.heartbeatInterval = properties.getHeartbeatInterval();
    }

    @GetMapping("/agent-sessions/{sessionId}/pending-approval")
    public ApiResponse<ApprovalRequestDto> pending(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String sessionId
    ) {
        return ApiResponse.ok(approvalService.findPending(principal.userId(), sessionId));
    }

    @PostMapping(value = "/approvals/{approvalId}/decision", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> decide(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String approvalId,
            @Valid @RequestBody ApprovalDecisionRequest request
    ) {
        Flux<ServerSentEvent<ChatStreamEvent>> events = approvalService
                .decideAndResume(principal.userId(), approvalId, request.decision())
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder()
                        .event(event.type()).data(event).build());
        Flux<ServerSentEvent<ChatStreamEvent>> heartbeats = Flux.interval(heartbeatInterval)
                .map(tick -> ServerSentEvent.<ChatStreamEvent>builder().comment("keepalive").build());
        Flux<ServerSentEvent<ChatStreamEvent>> body = Flux.concat(
                Flux.just(ServerSentEvent.<ChatStreamEvent>builder().comment("keepalive").build()),
                Flux.merge(events, heartbeats).takeUntil(event -> event.event() != null
                        && ("done".equals(event.event())
                        || "error".equals(event.event())
                        || "action_required".equals(event.event())))
        );
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache().cachePrivate().noTransform())
                .header("X-Accel-Buffering", "no")
                .body(body);
    }
}
