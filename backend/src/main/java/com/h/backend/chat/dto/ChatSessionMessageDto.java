package com.h.backend.chat.dto;

import java.time.LocalDateTime;

public record ChatSessionMessageDto(
        String id,
        String role,
        String messageType,
        String content,
        LocalDateTime createdAt
) {
}
