package com.h.backend.chat.interfaces.dto;

import java.time.LocalDateTime;

/** 前端恢复协作者拓扑、寻址和当前状态所需的最小读模型。 */
public record HarnessSubagentSummaryDto(
        String sessionId,
        String parentSessionId,
        String displayName,
        String assignment,
        HarnessSubagentStatus status,
        int displayOrder,
        LocalDateTime updatedAt
) {
}
