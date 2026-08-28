package com.h.backend.memory.interfaces.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 显式更新请求：expectedVersion 用于本地 version CAS，冲突返回 409。 */
public record UpdateUserMemoryRequest(
        @NotBlank @Size(max = 4000) String text,
        @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer expectedVersion
) {
}
