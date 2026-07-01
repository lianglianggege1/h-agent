package com.h.backend.chat.interfaces.dto;

import java.util.List;

public record ChatSessionBootstrapDto(
        String resolution,
        ChatSessionOpenDto session,
        List<ChatSessionSummaryDto> candidates
) {
}
