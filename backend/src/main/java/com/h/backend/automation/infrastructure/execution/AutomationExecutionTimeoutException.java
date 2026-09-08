package com.h.backend.automation.infrastructure.execution;

/** Run 超过平台执行超时（默认 15 分钟），终态记为 TIMED_OUT。 */
public class AutomationExecutionTimeoutException extends RuntimeException {

    public AutomationExecutionTimeoutException(String message) {
        super(message);
    }

    public AutomationExecutionTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
