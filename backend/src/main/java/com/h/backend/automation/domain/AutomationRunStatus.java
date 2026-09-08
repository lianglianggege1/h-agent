package com.h.backend.automation.domain;

/**
 * Run 状态机：
 * QUEUED → RUNNING → SUCCEEDED | FAILED | TIMED_OUT | CANCELLED | REJECTED_POLICY。
 * 未接纳的触发不落 Run 行，状态体现在接纳结果（SKIPPED_*）。
 */
public enum AutomationRunStatus {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    REJECTED_POLICY;

    public boolean isTerminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, REJECTED_POLICY -> true;
            case QUEUED, RUNNING, CANCEL_REQUESTED -> false;
        };
    }

    public static boolean isTerminal(String status) {
        try {
            return AutomationRunStatus.valueOf(status).isTerminal();
        } catch (RuntimeException error) {
            return true;
        }
    }
}
