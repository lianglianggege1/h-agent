package com.h.backend.chat.domain.subagentdefinition.model;

import java.time.Instant;
import java.util.Map;

/**
 * 父 turn 开始时生成的不可变 Catalog 快照。
 *
 * <p>定义在 turn 中途发布或停用，不改变已经开始的执行。用户 A 与 B 即使都定义
 * {@code my-reviewer}，也得到不同 snapshot 和 factory closure。</p>
 */
public record SubagentTurnSnapshot(
        String snapshotId,
        long userId,
        Instant createdAt,
        long policyRevision,
        Map<String, ResolvedSubagentDefinition> byAgentId) {

    public SubagentTurnSnapshot {
        byAgentId = Map.copyOf(byAgentId);
    }

    public ResolvedSubagentDefinition resolve(String agentId) {
        return byAgentId.get(agentId);
    }
}
