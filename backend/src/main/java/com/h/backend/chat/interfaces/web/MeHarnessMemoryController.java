package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.application.HarnessMemoryDocumentManager;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前认证用户唯一 Harness MEMORY.md 的单文档接口；owner 只取 principal。
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
    public ResponseEntity<ApiResponse<HarnessMemoryDocumentDto>> view(@AuthenticationPrincipal AuthUserPrincipal principal) {
        HarnessMemoryDocument document = manager.view(principal.userId());
        return noStore(ApiResponse.ok(HarnessMemoryDocumentDto.from(document)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<HarnessMemoryDocumentDto>> save(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @Valid @RequestBody SaveHarnessMemoryDocumentRequest request) {
        HarnessMemoryDocument document = manager.save(
                principal.userId(), request.content(), request.expectedRevision());
        return noStore(ApiResponse.ok(HarnessMemoryDocumentDto.from(document)));
    }

    private static <T> ResponseEntity<ApiResponse<T>> noStore(ApiResponse<T> body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
