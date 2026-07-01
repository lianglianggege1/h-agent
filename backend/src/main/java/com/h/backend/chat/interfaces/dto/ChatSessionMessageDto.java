package com.h.backend.chat.interfaces.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ChatSessionMessageDto(
        String id,
        String role,
        String messageType,
        String content,
        ChatMessagePayloadDto payload,
        List<ChatMessageResourceDto> resources,
        LocalDateTime createdAt
) {
}
