package com.h.backend.automation.domain;

/** 聊天内写操作的提案类型；管理页直接操作不经过提案。 */
public enum AutomationProposalAction {
    CREATE,
    UPDATE,
    ENABLE,
    DISABLE,
    DELETE
}
