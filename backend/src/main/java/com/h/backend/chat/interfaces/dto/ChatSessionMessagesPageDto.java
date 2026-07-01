package com.h.backend.chat.interfaces.dto;

import java.util.List;

public record ChatSessionMessagesPageDto(
        String sessionId,
        List<ChatSessionMessageDto> messages,
        boolean hasMore,
        Integer nextBeforeSeq
) {
}
