package com.h.backend.chat.interfaces.dto;

import com.h.capabilities.chat.interfaces.dto.ChatSessionMessageDto;
import java.util.List;

public record ChatSessionMessagesPageDto(
        String sessionId,
        List<ChatSessionMessageDto> messages,
        boolean hasMore,
        Integer nextBeforeSeq
) {
}
