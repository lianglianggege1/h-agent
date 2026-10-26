package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.application.HarnessMemoryDocumentManager;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.domain.memory.HarnessMemoryDocument;
import com.h.backend.chat.interfaces.dto.HarnessMemoryDocumentDto;
import com.h.backend.chat.interfaces.dto.SaveHarnessMemoryDocumentRequest;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前认证用户按 Agent 隔离的 MEMORY.md 接口；owner 只取 principal。
 * 内容属于私人数据：一律 no-store，禁止任何缓存。
 */
@RestController
@RequestMapping("/api/me/memory")
public class MeHarnessMemoryController {

    private final HarnessMemoryDocumentManager manager;

    public MeHarnessMemoryController(HarnessMemoryDocumentManager manager) {
        this.manager = manager;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<HarnessMemoryDocumentDto>> view(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestParam(defaultValue = ChatAgentIds.HARNESS) String agentId) {
        HarnessMemoryDocument document = manager.forAssistant(agentId).view(principal.userId());
        return noStore(ApiResponse.ok(HarnessMemoryDocumentDto.from(document)));
    }

    @GetMapping("/agents")
    public ResponseEntity<ApiResponse<List<MemoryAgentDto>>> agents() {
        return noStore(ApiResponse.ok(manager.listAgents().stream()
                .map(agent -> new MemoryAgentDto(agent.agentId(), agent.displayName())).toList()));
    }

    public record MemoryAgentDto(String agentId, String displayName) {}

    @PutMapping
    public ResponseEntity<ApiResponse<HarnessMemoryDocumentDto>> save(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestParam(defaultValue = ChatAgentIds.HARNESS) String agentId,
            @Valid @RequestBody SaveHarnessMemoryDocumentRequest request) {
        HarnessMemoryDocument document = manager.forAssistant(agentId).save(
                principal.userId(), request.content(), request.expectedRevision());
        return noStore(ApiResponse.ok(HarnessMemoryDocumentDto.from(document)));
    }

    private static <T> ResponseEntity<ApiResponse<T>> noStore(ApiResponse<T> body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
