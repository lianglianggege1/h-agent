package com.h.matrixteam;

/**
 * 团队逻辑 Agent 身份的单一定义源。身份装配（other-agents 配置、backend Manager/解析器）
 * 与记忆策略注册表（agent-capabilities）共用同一组常量，避免主动记忆时归属错填。
 */
public final class TeamAgentIds {

    public static final String MANAGER = "harness-team-manager";
    public static final String WORKER = "worker";
    public static final String SPRING_WORKER = "spring-worker";

    private TeamAgentIds() {
    }
}