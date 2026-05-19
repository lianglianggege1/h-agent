package com.h.backend.chat.dto;

public record ChatSessionOpenDto(
        ChatSessionMetaDto session,
        ChatSessionMessagesPageDto messagePage
) {
}
