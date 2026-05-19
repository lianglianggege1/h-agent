package com.h.backend.chat.dto;

import java.time.LocalDateTime;

public record ChatSessionMessageDto(
        String id,
        String role,
        String content,
        LocalDateTime createdAt
) {
}
