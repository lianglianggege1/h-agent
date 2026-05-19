package com.h.backend.chat.controller;

import com.h.backend.chat.dto.ChatSessionBootstrapDto;
import com.h.backend.chat.dto.ChatSessionMessagesPageDto;
import com.h.backend.chat.dto.ChatSessionMetaDto;
import com.h.backend.chat.dto.ChatSessionOpenDto;
import com.h.backend.chat.dto.ChatSessionSummaryDto;
import com.h.backend.chat.dto.ActivateHistorySessionRequest;
import com.h.backend.chat.dto.CreateChatSessionRequest;
import com.h.backend.chat.dto.ResolveChatSessionRequest;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.security.AuthUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat/sessions")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    public ChatSessionController(ChatSessionService chatSessionService) {
        this.chatSessionService = chatSessionService;
    }

    @GetMapping("/bootstrap")
    public ApiResponse<ChatSessionBootstrapDto> bootstrap(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return ApiResponse.ok(chatSessionService.bootstrap(principal.userId()));
    }

    @PostMapping("/create")
    public ApiResponse<ChatSessionOpenDto> create(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestBody(required = false) CreateChatSessionRequest request
    ) {
        CreateChatSessionRequest payload = request == null ? new CreateChatSessionRequest(null, null) : request;
        return ApiResponse.ok(chatSessionService.createSession(
                principal.userId(),
                payload.promptId(),
                payload.currentSessionId()
        ));
    }

    @PostMapping("/resolve")
    public ApiResponse<ChatSessionOpenDto> resolve(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestBody ResolveChatSessionRequest request
    ) {
        return ApiResponse.ok(chatSessionService.chooseActiveSession(principal.userId(), request.selectedSessionId()));
    }

    @PostMapping("/activate")
    public ApiResponse<ChatSessionOpenDto> activate(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestBody ActivateHistorySessionRequest request
    ) {
        return ApiResponse.ok(chatSessionService.activateHistorySession(
                principal.userId(),
                request.targetSessionId(),
                request.currentSessionId()
        ));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<ChatSessionMetaDto> detail(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String sessionId
    ) {
        return ApiResponse.ok(chatSessionService.getSessionDetail(principal.userId(), sessionId));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<ChatSessionMessagesPageDto> messages(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Integer beforeSeq
    ) {
        return ApiResponse.ok(chatSessionService.getSessionMessages(principal.userId(), sessionId, limit, beforeSeq));
    }

    @GetMapping("/history")
    public ApiResponse<List<ChatSessionSummaryDto>> history(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.ok(chatSessionService.listHistory(principal.userId(), page, size));
    }
}
