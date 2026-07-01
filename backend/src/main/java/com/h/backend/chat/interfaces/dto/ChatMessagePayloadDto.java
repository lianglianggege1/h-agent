package com.h.backend.chat.interfaces.dto;

public record ChatMessagePayloadDto(
        String prompt,
        String provider,
        String providerRequestId,
        String model,
        String aspectRatio,
        String status,
        String triggerSource,
        String sourceResourceId,
        String parentImageMessageId,
        String operationType
) {
}
