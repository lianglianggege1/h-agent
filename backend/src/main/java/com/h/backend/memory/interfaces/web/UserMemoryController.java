package com.h.backend.memory.interfaces.web;

import com.h.backend.common.api.ApiResponse;
import com.h.backend.memory.application.UserMemoryCatalog;
import com.h.backend.memory.domain.ExplicitMemoryDelete;
import com.h.backend.memory.domain.ExplicitMemorySave;
import com.h.backend.memory.domain.ExplicitMemoryUpdate;
import com.h.backend.memory.domain.MemoryModuleDisabledException;
import com.h.backend.memory.domain.MemoryNotFoundException;
import com.h.backend.memory.domain.MemoryScopeKind;
import com.h.backend.memory.domain.MemoryVersionConflictException;
import com.h.backend.memory.domain.OwnedMemoryId;
import com.h.backend.memory.domain.OwnedMemoryQuery;
import com.h.backend.memory.domain.OwnedMemorySearch;
import com.h.backend.memory.interfaces.dto.SaveUserMemoryRequest;
import com.h.backend.memory.interfaces.dto.UpdateUserMemoryRequest;
import com.h.backend.memory.interfaces.dto.UserMemoryHistoryDto;
import com.h.backend.memory.interfaces.dto.UserMemoryItemDto;
import com.h.backend.memory.interfaces.dto.UserMemoryMutationResultDto;
import com.h.backend.memory.interfaces.dto.UserMemoryPageDto;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户长期记忆管理接口：列表/语义搜索/详情/显式 CRUD/历史，身份只取自服务端认证。 */
@RestController
@RequestMapping("/api/memories")
public class UserMemoryController {

    private final UserMemoryCatalog userMemoryCatalog;

    public UserMemoryController(UserMemoryCatalog userMemoryCatalog) {
        this.userMemoryCatalog = userMemoryCatalog;
    }

    @GetMapping
    public ApiResponse<UserMemoryPageDto> list(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int pageSize
    ) {
        OwnedMemoryQuery query = new OwnedMemoryQuery(
                principal.userId(),
                parseScope(scope),
                agentId,
                cursor,
                pageSize
        );
        return ApiResponse.ok(UserMemoryPageDto.from(userMemoryCatalog.list(query)));
    }

    @GetMapping("/search")
    public ApiResponse<UserMemoryPageDto> search(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestParam String q,
            @RequestParam(required = false, defaultValue = "10") int limit
    ) {
        OwnedMemorySearch query = new OwnedMemorySearch(principal.userId(), q, limit);
        return ApiResponse.ok(UserMemoryPageDto.from(userMemoryCatalog.search(query)));
    }

    @GetMapping("/{localId}")
    public ApiResponse<UserMemoryItemDto> get(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long localId
    ) {
        return ApiResponse.ok(UserMemoryItemDto.from(
                userMemoryCatalog.get(new OwnedMemoryId(principal.userId(), localId))));
    }

    @PostMapping
    public ApiResponse<UserMemoryMutationResultDto> save(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @Valid @RequestBody SaveUserMemoryRequest request
    ) {
        ExplicitMemorySave command = new ExplicitMemorySave(
                principal.userId(),
                parseScope(request.scope()),
                request.agentId(),
                request.runId(),
                request.text(),
                null
        );
        return ApiResponse.ok(UserMemoryMutationResultDto.from(userMemoryCatalog.save(command)));
    }

    @PutMapping("/{localId}")
    public ApiResponse<UserMemoryMutationResultDto> update(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long localId,
            @Valid @RequestBody UpdateUserMemoryRequest request
    ) {
        ExplicitMemoryUpdate command = new ExplicitMemoryUpdate(
                principal.userId(),
                localId,
                request.text(),
                request.expectedVersion()
        );
        return ApiResponse.ok(UserMemoryMutationResultDto.from(userMemoryCatalog.update(command)));
    }

    @DeleteMapping("/{localId}")
    public ApiResponse<UserMemoryMutationResultDto> delete(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long localId,
            @RequestParam int expectedVersion
    ) {
        ExplicitMemoryDelete command = new ExplicitMemoryDelete(
                principal.userId(), localId, expectedVersion);
        return ApiResponse.ok(UserMemoryMutationResultDto.from(userMemoryCatalog.delete(command)));
    }

    @GetMapping("/{localId}/history")
    public ApiResponse<UserMemoryHistoryDto> history(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long localId
    ) {
        return ApiResponse.ok(UserMemoryHistoryDto.from(
                userMemoryCatalog.history(new OwnedMemoryId(principal.userId(), localId))));
    }

    @ExceptionHandler(MemoryVersionConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleVersionConflict(MemoryVersionConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(40901, ex.getMessage()));
    }

    @ExceptionHandler(MemoryNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(MemoryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(40404, ex.getMessage()));
    }

    @ExceptionHandler(MemoryModuleDisabledException.class)
    public ResponseEntity<ApiResponse<Void>> handleDisabled(MemoryModuleDisabledException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(50301, ex.getMessage()));
    }

    private static MemoryScopeKind parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        return MemoryScopeKind.valueOf(scope.trim().toUpperCase());
    }
}
