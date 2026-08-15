package com.h.backend.chat.application;

import com.h.backend.chat.interfaces.dto.HarnessSubagentSummaryDto;

/** 用户向既有子 Agent 追加要求后，持久化的用户消息和 RUNNING 状态。 */
public record HarnessSubagentTurnStart(
        Long userMessageId,
        String executionId,
        HarnessSubagentSummaryDto subagent
) {
}
