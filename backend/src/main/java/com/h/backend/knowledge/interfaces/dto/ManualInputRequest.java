package com.h.backend.knowledge.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

public record ManualInputRequest(
        @NotBlank String title,
        @NotBlank String content
) {}
