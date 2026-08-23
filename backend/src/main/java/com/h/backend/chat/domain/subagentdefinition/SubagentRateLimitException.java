package com.h.backend.chat.domain.subagentdefinition;

/** Subagent 管理接口用户级限流触发（设计 Phase 3 / 9.3：HTTP 429）。 */
public class SubagentRateLimitException extends RuntimeException {

    public SubagentRateLimitException(String message) {
        super(message);
    }
}
