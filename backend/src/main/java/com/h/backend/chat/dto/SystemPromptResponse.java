package com.h.backend.chat.dto;

public record SystemPromptResponse(
        Long id,
        String name,
        String content,
        Boolean isDefault
) {
}
