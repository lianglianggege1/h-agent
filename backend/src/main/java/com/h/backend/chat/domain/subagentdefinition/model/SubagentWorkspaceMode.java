package com.h.backend.chat.domain.subagentdefinition.model;

/** child 工作区隔离模式。 */
public enum SubagentWorkspaceMode {
    /** 内置定义：复用父 Agent 当前 USER-scoped Remote filesystem。 */
    SHARED,
    /** 用户定义：SESSION-isolated Remote filesystem。 */
    ISOLATED
}
