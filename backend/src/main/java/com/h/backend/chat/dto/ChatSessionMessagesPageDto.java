package com.h.backend.chat.dto;

import java.util.List;

public record ChatSessionMessagesPageDto(
        String sessionId,
        List<ChatSessionMessageDto> messages,
        boolean hasMore,
        Integer nextBeforeSeq
) {
}
