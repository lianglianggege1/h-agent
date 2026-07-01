package com.h.backend.knowledge.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ManualInputRequest(
        @NotNull Long promptId,
        @NotBlank String title,
        @NotBlank String content
) {}
