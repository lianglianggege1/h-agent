package com.h.backend.skill.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Skill 工作区请求 DTO（设计 §17）。owner 只从认证身份推导；
 * 生效、启停、撤销、归档和恢复使用 expected revision（乐观锁），
 * Proposal 保存与发布使用 expectedProposalHead。
 */
public final class SkillRequests {

    private SkillRequests() {
    }

    public record CreateSkillRequest(
            @NotBlank String skillKey,
            @NotBlank String displayName,
            String description,
            String skillMd
    ) {
    }

    public record CreateProposalRequest(Long baseReleaseId) {
    }

    public record ProposalChangeRequest(String path, String contentBase64) {
    }

    public record SaveProposalRequest(
            @NotBlank String expectedHead,
            List<ProposalChangeRequest> changes
    ) {
    }

    public record ExpectedHeadRequest(@NotBlank String expectedHead) {
    }

    public record PublishReleaseRequest(
            @NotBlank String expectedHead,
            @NotBlank String validatedHead,
            @NotBlank String releaseNote
    ) {
    }

    public record ExpectedRevisionRequest(@NotNull Long expectedRevision) {
    }

    public record SetEnabledRequest(
            @NotNull Boolean enabled,
            @NotNull Long expectedRevision
    ) {
    }

    public record RevokeReleaseRequest(String reason) {
    }
}
