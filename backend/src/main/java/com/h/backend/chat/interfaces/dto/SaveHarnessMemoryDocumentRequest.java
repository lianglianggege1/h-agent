package com.h.backend.chat.interfaces.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SaveHarnessMemoryDocumentRequest(
        @NotNull String content,
        @NotNull @PositiveOrZero Long expectedRevision
) {
}
