package com.h.backend.automation.infrastructure.execution;

/**
 * 接纳后权限/策略校验失败（Agent 停用、凭证失效、需要人在回路但无持久授权）。
 * V1 不引入运行中审批，Run 以 REJECTED_POLICY 结束，不发送业务 Artifact。
 */
public class AutomationPolicyRejectionException extends RuntimeException {

    public AutomationPolicyRejectionException(String message) {
        super(message);
    }

    public AutomationPolicyRejectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
