package com.h.backend.chat.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSystemPromptRequest(
        @NotBlank(message = "提示词名称不能为空")
        @Size(max = 64, message = "提示词名称不能超过 64 个字符")
        String name,

        @NotBlank(message = "提示词内容不能为空")
        @Size(max = 8000, message = "提示词内容不能超过 8000 个字符")
        String content
) {
}
