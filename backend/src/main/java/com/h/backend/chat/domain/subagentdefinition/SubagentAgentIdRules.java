package com.h.backend.chat.domain.subagentdefinition;

import java.util.regex.Pattern;

/**
 * agent_id 命名规则：kebab-case，长度 1–63，创建后不可修改。
 *
 * <p>保留 ID（内置 agent_id 与 {@code general-purpose}）的冲突校验在 Catalog
 * 事务内完成，不在此处。</p>
 */
public final class SubagentAgentIdRules {

    public static final int MAX_LENGTH = 63;

    private static final Pattern KEBAB_CASE =
            Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private SubagentAgentIdRules() {
    }

    public static boolean isValid(String agentId) {
        return agentId != null
                && !agentId.isEmpty()
                && agentId.length() <= MAX_LENGTH
                && KEBAB_CASE.matcher(agentId).matches();
    }
}
