package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.approval.ApprovalDecision;
import jakarta.validation.constraints.NotNull;

public record ApprovalDecisionRequest(@NotNull ApprovalDecision decision) {
}
