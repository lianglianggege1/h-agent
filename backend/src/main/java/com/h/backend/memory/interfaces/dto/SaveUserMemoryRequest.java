package com.h.backend.memory.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 显式保存请求：scope 可选 USER/AGENT/RUN；AGENT 必填 agentId，RUN 必填 agentId + runId。 */
public record SaveUserMemoryRequest(
        @NotBlank @Size(max = 4000) String text,
        @NotBlank String scope,
        String agentId,
        String runId
) {
}
