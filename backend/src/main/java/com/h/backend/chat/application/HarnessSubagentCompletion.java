package com.h.backend.chat.application;

import com.h.backend.chat.interfaces.dto.HarnessSubagentSummaryDto;

/** 一次子 Agent 完成提交后，持久化的回复消息和最新产品状态。 */
public record HarnessSubagentCompletion(
        Long assistantMessageId,
        HarnessSubagentSummaryDto subagent
) {
}
