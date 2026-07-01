package com.h.backend.chat.interfaces.dto;

public record SystemPromptResponse(
        Long id,
        String name,
        String content,
        Boolean isDefault
) {
}
