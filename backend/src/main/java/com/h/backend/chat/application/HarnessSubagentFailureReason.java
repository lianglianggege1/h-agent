package com.h.backend.chat.application;

/** 最近一轮协作 Agent 进入失败终态的服务端原因。 */
public enum HarnessSubagentFailureReason {
    EXECUTION_ERROR,
    CANCELLED,
    PROTOCOL_INCOMPLETE,
    PREPARATION_ERROR,
    ORPHANED
}
