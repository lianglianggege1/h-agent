package com.h.backend.chat.interfaces.dto;

public record DecisionRouterMetaDto(
        String selectionMode,
        Double activationThreshold,
        String dispatchMode
) {
}
