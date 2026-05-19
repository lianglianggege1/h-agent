package com.h.backend.chat.dto;

import java.util.List;

public record ChatSessionBootstrapDto(
        String resolution,
        ChatSessionOpenDto session,
        List<ChatSessionSummaryDto> candidates
) {
}
