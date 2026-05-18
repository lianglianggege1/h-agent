package com.h.backend.chat.controller;

import com.h.backend.chat.dto.CreateSystemPromptRequest;
import com.h.backend.chat.dto.SystemPromptResponse;
import com.h.backend.chat.dto.UpdateSystemPromptRequest;
import com.h.backend.chat.service.SystemPromptService;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat/system-prompts")
public class SystemPromptController {

    private final SystemPromptService systemPromptService;

    public SystemPromptController(SystemPromptService systemPromptService) {
        this.systemPromptService = systemPromptService;
    }

    @GetMapping
    public ApiResponse<List<SystemPromptResponse>> list(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return ApiResponse.ok(systemPromptService.listPrompts(principal.userId()));
    }

    @PostMapping
    public ApiResponse<SystemPromptResponse> create(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @Valid @RequestBody CreateSystemPromptRequest request
    ) {
        return ApiResponse.ok(systemPromptService.createPrompt(principal.userId(), request));
    }

    @PutMapping("/{promptId}")
    public ApiResponse<SystemPromptResponse> update(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long promptId,
            @Valid @RequestBody UpdateSystemPromptRequest request
    ) {
        return ApiResponse.ok(systemPromptService.updatePrompt(principal.userId(), promptId, request));
    }

    @DeleteMapping("/{promptId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long promptId
    ) {
        systemPromptService.deletePrompt(principal.userId(), promptId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{promptId}/default")
    public ApiResponse<SystemPromptResponse> setDefault(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long promptId
    ) {
        return ApiResponse.ok(systemPromptService.setDefaultPrompt(principal.userId(), promptId));
    }
}
