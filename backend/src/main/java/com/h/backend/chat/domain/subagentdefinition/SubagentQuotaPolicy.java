package com.h.backend.chat.domain.subagentdefinition;

/**
 * 用户级 Subagent 配额政策。
 *
 * <p>配额在事务内锁定用户定义集合后统计（见设计 11.3），
 * 本类只承载常量与判定逻辑，不负责并发控制。</p>
 */
public final class SubagentQuotaPolicy {

    /** 每用户最多 100 个未删除 Definition。 */
    public static final int MAX_DEFINITIONS = 100;

    /** 每用户最多 20 个 enabled Definition。 */
    public static final int MAX_ENABLED = 20;

    /** Markdown 原文上限（与编译器一致）。 */
    public static final int MAX_MARKDOWN_BYTES = SubagentMarkdownCompiler.MAX_MARKDOWN_BYTES;

    public boolean withinDefinitionLimit(long usedDefinitions) {
        return usedDefinitions < MAX_DEFINITIONS;
    }

    public boolean withinEnabledLimit(long usedEnabled) {
        return usedEnabled < MAX_ENABLED;
    }
}
