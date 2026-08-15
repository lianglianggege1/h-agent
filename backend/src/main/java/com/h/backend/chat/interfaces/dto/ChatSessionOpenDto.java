package com.h.backend.chat.interfaces.dto;

import java.util.List;

public record ChatSessionOpenDto(
        ChatSessionMetaDto session,
        ChatSessionMessagesPageDto messagePage,
        List<HarnessSubagentSummaryDto> subagents
) {

    public ChatSessionOpenDto(ChatSessionMetaDto session, ChatSessionMessagesPageDto messagePage) {
        this(session, messagePage, null);
    }
}
