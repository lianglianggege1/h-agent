package com.h.backend.skill.interfaces.web;

import com.h.backend.common.api.ApiResponse;
import com.h.backend.skill.application.SkillCatalogService;
import com.h.backend.skill.domain.SkillPlatformErrorKind;
import com.h.backend.skill.domain.SkillPlatformException;
import com.h.backend.skill.interfaces.dto.SkillRequests;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * /me/skills 工作区路由（设计 §17）。owner 只从认证身份推导；
 * 发布只返回 Release，不接受 activation/enabled 参数；不存在任何
 * “发布并生效/启用”组合入口。
 */
@RestController
@RequestMapping("/api/me/skills")
public class SkillController {

    private final SkillCatalogService catalogService;

    public SkillController(SkillCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    // ------------------------------------------------------------------
    // Skill 列表 / 创建 / 详情 / 删除
    // ------------------------------------------------------------------

    @GetMapping
    public ApiResponse<List<SkillCatalogService.SkillSummaryView>> list(
            @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ApiResponse.ok(catalogService.listOwnSkills(principal.userId()));
    }

    @PostMapping
    public ApiResponse<SkillCatalogService.SkillSummaryView> create(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SkillRequests.CreateSkillRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return ApiResponse.ok(catalogService.createSkill(principal.userId(),
                new SkillCatalogService.CreateSkillCommand(
                        request.skillKey(), request.displayName(),
                        request.description(), request.skillMd())));
    }

    @GetMapping("/{skillId}")
    public ApiResponse<SkillCatalogService.SkillSummaryView> get(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId) {
        return ApiResponse.ok(catalogService.getOwnSkill(principal.userId(), skillId));
    }

    /** 仅从未发布的 Skill 可以彻底删除；存在 Release 后必须归档（设计不变量 21）。 */
    @DeleteMapping("/{skillId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId) {
        catalogService.deleteSkill(principal.userId(), skillId);
        return ApiResponse.ok(null);
    }

    // ------------------------------------------------------------------
    // Proposal：读取 / 升级创建 / 保存 / 校验 / 放弃
    // ------------------------------------------------------------------

    @GetMapping("/{skillId}/proposal")
    public ApiResponse<SkillCatalogService.ProposalView> getProposal(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId) {
        return ApiResponse.ok(catalogService.getProposal(principal.userId(), skillId));
    }

    @PostMapping("/{skillId}/proposal")
    public ApiResponse<SkillCatalogService.ProposalView> createProposal(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody(required = false) SkillRequests.CreateProposalRequest request) {
        requireIdempotencyKey(idempotencyKey);
        Long baseReleaseId = request == null ? null : request.baseReleaseId();
        return ApiResponse.ok(catalogService.createProposalFromRelease(
                principal.userId(), skillId, baseReleaseId));
    }

    @PutMapping("/{skillId}/proposal")
    public ApiResponse<SkillCatalogService.ProposalView> saveProposal(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId,
            @Valid @RequestBody SkillRequests.SaveProposalRequest request) {
        List<SkillCatalogService.SaveProposalChange> changes = request.changes() == null
                ? List.of()
                : request.changes().stream()
                        .map(change -> new SkillCatalogService.SaveProposalChange(
                                change.path(), change.contentBase64()))
                        .toList();
        return ApiResponse.ok(catalogService.saveProposal(
                principal.userId(), skillId, request.expectedHead(), changes));
    }

    @PostMapping("/{skillId}/proposal/validate")
    public ApiResponse<SkillCatalogService.ValidationOutcomeView> validateProposal(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId,
            @Valid @RequestBody SkillRequests.ExpectedHeadRequest request) {
        return ApiResponse.ok(SkillCatalogService.ValidationOutcomeView.from(
                catalogService.validateProposal(principal.userId(), skillId, request.expectedHead())));
    }

    @DeleteMapping("/{skillId}/proposal")
    public ApiResponse<Void> discardProposal(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId,
            @RequestParam("expectedHead") String expectedHead) {
        catalogService.discardProposal(principal.userId(), skillId, expectedHead);
        return ApiResponse.ok(null);
    }

    // ------------------------------------------------------------------
    // Release：发布 / 列表 / 详情 / 比较
    // ------------------------------------------------------------------

    @PostMapping("/{skillId}/releases")
    public ApiResponse<SkillCatalogService.ReleaseSummaryView> publishRelease(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SkillRequests.PublishReleaseRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return ApiResponse.ok(catalogService.publishRelease(
                principal.userId(), skillId, request.expectedHead(),
                request.validatedHead(), request.releaseNote(), idempotencyKey));
    }

    @GetMapping("/{skillId}/releases")
    public ApiResponse<List<SkillCatalogService.ReleaseSummaryView>> listReleases(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId) {
        return ApiResponse.ok(catalogService.listReleases(principal.userId(), skillId));
    }

    @GetMapping("/{skillId}/releases/{releaseId}")
    public ApiResponse<SkillCatalogService.ReleaseDetailView> getRelease(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId,
            @PathVariable Long releaseId) {
        return ApiResponse.ok(catalogService.getRelease(principal.userId(), skillId, releaseId));
    }

    @GetMapping("/{skillId}/compare")
    public ApiResponse<SkillCatalogService.ReleaseCompareView> compareReleases(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId,
            @RequestParam("from") Long fromReleaseId,
            @RequestParam("to") Long toReleaseId) {
        return ApiResponse.ok(catalogService.compareReleases(
                principal.userId(), skillId, fromReleaseId, toReleaseId));
    }

    // ------------------------------------------------------------------
    // 生效 / 撤销 / 启停 / 归档 / 恢复（三个独立动作，无组合命令）
    // ------------------------------------------------------------------

    @PostMapping("/{skillId}/releases/{releaseId}/activate")
    public ApiResponse<SkillCatalogService.SkillSummaryView> activateRelease(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId,
            @PathVariable Long releaseId,
            @Valid @RequestBody SkillRequests.ExpectedRevisionRequest request) {
        return ApiResponse.ok(catalogService.activateRelease(
                principal.userId(), skillId, releaseId, request.expectedRevision()));
    }

    @PostMapping("/{skillId}/releases/{releaseId}/revoke")
    public ApiResponse<SkillCatalogService.SkillSummaryView> revokeRelease(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId,
            @PathVariable Long releaseId,
            @Valid @RequestBody(required = false) SkillRequests.RevokeReleaseRequest request) {
        return ApiResponse.ok(catalogService.revokeRelease(
                principal.userId(), skillId, releaseId, request == null ? null : request.reason()));
    }

    @PutMapping("/{skillId}/enabled")
    public ApiResponse<SkillCatalogService.SkillSummaryView> setEnabled(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId,
            @Valid @RequestBody SkillRequests.SetEnabledRequest request) {
        return ApiResponse.ok(catalogService.setEnabled(
                principal.userId(), skillId, request.enabled(), request.expectedRevision()));
    }

    @PostMapping("/{skillId}/archive")
    public ApiResponse<SkillCatalogService.SkillSummaryView> archiveSkill(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId,
            @Valid @RequestBody SkillRequests.ExpectedRevisionRequest request) {
        return ApiResponse.ok(catalogService.archiveSkill(
                principal.userId(), skillId, request.expectedRevision()));
    }

    @PostMapping("/{skillId}/restore")
    public ApiResponse<SkillCatalogService.SkillSummaryView> restoreSkill(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long skillId,
            @Valid @RequestBody SkillRequests.ExpectedRevisionRequest request) {
        return ApiResponse.ok(catalogService.restoreSkill(
                principal.userId(), skillId, request.expectedRevision()));
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.OPERATION_CONFLICT, "缺少 Idempotency-Key");
        }
    }
}
