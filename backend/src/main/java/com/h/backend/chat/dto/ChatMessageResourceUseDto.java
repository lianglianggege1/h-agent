package com.h.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatMessageResourceUseDto(
        @NotBlank(message = "resourceId 不能为空")
        String resourceId,

        @NotBlank(message = "资源角色不能为空")
        String role,

        @NotBlank(message = "资源来源不能为空")
        String source
) {
}
