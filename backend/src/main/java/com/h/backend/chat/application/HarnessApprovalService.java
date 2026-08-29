package com.h.backend.chat.application;

import com.h.backend.chat.domain.approval.ApprovalDecision;
import com.h.backend.chat.interfaces.dto.ApprovalRequestDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import reactor.core.publisher.Flux;

public interface HarnessApprovalService {
    ApprovalRequestDto findPending(Long userId, String sessionId);

    Flux<ChatStreamEvent> decideAndResume(Long userId, String approvalId, ApprovalDecision decision);
}
