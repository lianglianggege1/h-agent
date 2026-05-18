package com.h.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatMessageRequest(
        @NotBlank(message = "消息不能为空")
        @Size(max = 4000, message = "消息长度不能超过 4000")
        String message,

        @NotBlank(message = "sessionId 不能为空")
        String sessionId,

        Long promptId
) {
}
