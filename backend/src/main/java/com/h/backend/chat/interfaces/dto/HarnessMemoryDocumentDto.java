package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.memory.HarnessMemoryDocument;

public record HarnessMemoryDocumentDto(
        String content,
        long revision,
        boolean exists,
        String updatedAt
) {

    public static HarnessMemoryDocumentDto from(HarnessMemoryDocument document) {
        return new HarnessMemoryDocumentDto(
                document.content(),
                document.revision(),
                document.exists(),
                document.updatedAt() == null ? null : document.updatedAt().toString()
        );
    }
}
