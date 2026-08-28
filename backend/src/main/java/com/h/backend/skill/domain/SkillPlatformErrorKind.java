package com.h.backend.skill.domain;

public enum SkillPlatformErrorKind {

    SKILL_NOT_OWNED(40404, "Skill 不存在"),
    SKILL_INVALID(40002, "Skill 内容校验失败"),
    PROPOSAL_HEAD_MISMATCH(40901, "草稿已被其他操作修改"),
    VALIDATION_STALE(40902, "当前内容缺少有效校验，请先重新校验"),
    ACTIVE_RELEASE_MISMATCH(40903, "生效版本状态已变化"),
    RELEASE_REVOKED(40904, "该 Release 已撤销，不能生效"),
    SOURCE_UNAVAILABLE(50301, "源码仓库暂不可用"),
    ARTIFACT_UNAVAILABLE(50302, "Skill 运行制品暂不可用"),
    ARTIFACT_CORRUPT(50001, "Skill 运行制品校验失败"),
    CREDENTIAL_UNAVAILABLE(50303, "源码仓库凭据不可用"),
    SOURCE_DRIFTED(40905, "源码仓库状态漂移"),
    QUOTA_EXCEEDED(40003, "超出 Skill 配额"),
    OPERATION_CONFLICT(40906, "操作冲突"),
    PROPOSAL_STATE_INVALID(40004, "草稿状态不允许该操作");

    private final int code;
    private final String defaultMessage;

    SkillPlatformErrorKind(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
