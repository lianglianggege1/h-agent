package com.h.backend.chat.domain.approval;

import java.util.List;

/** SDK 无关、可安全持久化和展示的一次权限确认快照。 */
public record ApprovalEpisode(
        String requestKey,
        String replyId,
        List<ToolCall> toolCalls
) {
    public ApprovalEpisode {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public record ToolCall(String id, String name, String displaySummary) {
    }
}
