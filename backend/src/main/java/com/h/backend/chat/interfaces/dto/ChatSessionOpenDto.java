package com.h.backend.chat.interfaces.dto;

public record ChatSessionOpenDto(
        ChatSessionMetaDto session,
        ChatSessionMessagesPageDto messagePage
) {
}
