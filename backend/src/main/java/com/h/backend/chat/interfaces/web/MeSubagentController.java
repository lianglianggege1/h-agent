package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.domain.subagentdefinition.SubagentDefinitionCatalog;
import com.h.backend.chat.domain.subagentdefinition.model.CreateSubagentDraftCommand;
import com.h.backend.chat.domain.subagentdefinition.model.SaveSubagentDraftCommand;
import com.h.backend.chat.domain.subagentdefinition.model.ValidateSubagentDraftCommand;
import com.h.backend.chat.infrastructure.config.SubagentCatalogProperties;
import com.h.backend.chat.infrastructure.subagent.SubagentManagementRateLimiter;
import com.h.backend.chat.interfaces.dto.CreateSubagentRequest;
import com.h.backend.chat.interfaces.dto.PublishSubagentRequest;
import com.h.backend.chat.interfaces.dto.SaveSubagentDraftRequest;
import com.h.backend.chat.interfaces.dto.SetSubagentEnabledRequest;
import com.h.backend.chat.interfaces.dto.SubagentCatalogViewDto;
import com.h.backend.chat.interfaces.dto.SubagentDefinitionDetailDto;
import com.h.backend.chat.interfaces.dto.SubagentDraftResultDto;
import com.h.backend.chat.interfaces.dto.SubagentPublishResultDto;
import com.h.backend.chat.interfaces.dto.SubagentValidationResultDto;
import com.h.backend.chat.interfaces.dto.SubagentVersionDetailDto;
import com.h.backend.chat.interfaces.dto.SubagentVersionSummaryDto;
import com.h.backend.chat.interfaces.dto.ValidateSubagentRequest;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
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

/**
 * Subagent Definition Catalog 管理接口（设计 9）。
 *
 * <p>owner 一律取自认证 principal；跨用户定义统一表现为 404（Catalog 不变量）。
 * 变更类操作经过用户级限流；错误码与 HTTP 状态映射见
 * {@link SubagentDefinitionExceptionAdvisor}。</p>
 */
@RestController
@RequestMapping("/api/me/subagents")
public class MeSubagentController {

    private final SubagentDefinitionCatalog catalog;
    private final SubagentCatalogProperties properties;
    private final SubagentManagementRateLimiter rateLimiter;

    public MeSubagentController(
            SubagentDefinitionCatalog catalog,
            SubagentCatalogProperties properties,
            SubagentManagementRateLimiter rateLimiter
    ) {
        this.catalog = catalog;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public ApiResponse<SubagentCatalogViewDto> list(@AuthenticationPrincipal AuthUserPrincipal principal) {
        requireCatalogEnabled();
        return ApiResponse.ok(SubagentCatalogViewDto.from(catalog.listForManagement(principal.userId())));
    }

    @GetMapping("/{agentId}")
    public ApiResponse<SubagentDefinitionDetailDto> detail(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String agentId
    ) {
        requireCatalogEnabled();
        return ApiResponse.ok(SubagentDefinitionDetailDto.from(
                catalog.requireVisible(principal.userId(), agentId)));
    }

    @PostMapping
    public ApiResponse<SubagentDraftResultDto> create(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @Valid @RequestBody CreateSubagentRequest request
    ) {
        requireCatalogEnabled();
        rateLimiter.acquire(principal.userId());
        return ApiResponse.ok(SubagentDraftResultDto.from(
                catalog.createDraft(principal.userId(), new CreateSubagentDraftCommand(
                        request.agentId(), request.markdown()))));
    }

    @PutMapping("/{agentId}/draft")
    public ApiResponse<SubagentDraftResultDto> saveDraft(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String agentId,
            @Valid @RequestBody SaveSubagentDraftRequest request
    ) {
        requireCatalogEnabled();
        rateLimiter.acquire(principal.userId());
        return ApiResponse.ok(SubagentDraftResultDto.from(
                catalog.saveDraft(principal.userId(), agentId, new SaveSubagentDraftCommand(
                        request.expectedRevision(), request.markdown()))));
    }

    @PostMapping("/validate")
    public ApiResponse<SubagentValidationResultDto> validate(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @Valid @RequestBody ValidateSubagentRequest request
    ) {
        requireCatalogEnabled();
        rateLimiter.acquire(principal.userId());
        return ApiResponse.ok(SubagentValidationResultDto.from(
                catalog.validate(principal.userId(), new ValidateSubagentDraftCommand(request.markdown()))));
    }

    @PostMapping("/{agentId}/publish")
    public ApiResponse<SubagentPublishResultDto> publish(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String agentId,
            @Valid @RequestBody PublishSubagentRequest request
    ) {
        requireCatalogEnabled();
        rateLimiter.acquire(principal.userId());
        return ApiResponse.ok(SubagentPublishResultDto.from(
                catalog.publish(principal.userId(), agentId, request.expectedRevision())));
    }

    @PutMapping("/{agentId}/enabled")
    public ApiResponse<SubagentDefinitionDetailDto> setEnabled(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String agentId,
            @Valid @RequestBody SetSubagentEnabledRequest request
    ) {
        requireCatalogEnabled();
        rateLimiter.acquire(principal.userId());
        return ApiResponse.ok(SubagentDefinitionDetailDto.from(
                catalog.setEnabled(principal.userId(), agentId, request.enabled())));
    }

    @DeleteMapping("/{agentId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String agentId
    ) {
        requireCatalogEnabled();
        rateLimiter.acquire(principal.userId());
        catalog.softDelete(principal.userId(), agentId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{agentId}/restore")
    public ApiResponse<SubagentDefinitionDetailDto> restore(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String agentId
    ) {
        requireCatalogEnabled();
        rateLimiter.acquire(principal.userId());
        return ApiResponse.ok(SubagentDefinitionDetailDto.from(
                catalog.restore(principal.userId(), agentId)));
    }

    @GetMapping("/{agentId}/versions")
    public ApiResponse<List<SubagentVersionSummaryDto>> versions(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String agentId
    ) {
        requireCatalogEnabled();
        return ApiResponse.ok(catalog.listVersions(principal.userId(), agentId).stream()
                .map(SubagentVersionSummaryDto::from)
                .toList());
    }

    @GetMapping("/{agentId}/versions/{version}")
    public ApiResponse<SubagentVersionDetailDto> versionDetail(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String agentId,
            @PathVariable int version
    ) {
        requireCatalogEnabled();
        return ApiResponse.ok(SubagentVersionDetailDto.from(
                catalog.versionDetail(principal.userId(), agentId, version)));
    }

    private void requireCatalogEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(40404, "Subagent 目录功能未启用");
        }
    }
}
