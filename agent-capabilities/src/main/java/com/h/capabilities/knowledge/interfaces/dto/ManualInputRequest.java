package com.h.capabilities.knowledge.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

public record ManualInputRequest(
        @NotBlank String title,
        @NotBlank String content
) {}
