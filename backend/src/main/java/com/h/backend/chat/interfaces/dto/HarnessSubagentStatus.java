package com.h.backend.chat.interfaces.dto;

/**
 * 用户可见协作 Agent 的当前状态。
 *
 * <p>状态由 AgentScope 2.0.1 的真实生命周期事实投影：暴露、开始、结束和执行失败。
 * 后台任务排队状态与 HITL 确认状态不属于这个枚举。</p>
 */
public enum HarnessSubagentStatus {
    AVAILABLE,
    RUNNING,
    COMPLETED,
    FAILED
}
