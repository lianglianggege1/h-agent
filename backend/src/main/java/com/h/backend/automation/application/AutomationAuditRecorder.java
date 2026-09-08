package com.h.backend.automation.application;

/** 审计写入端口；持久化实现在 infrastructure 层，应用层只依赖该接口。 */
public interface AutomationAuditRecorder {

    AutomationAuditRecorder NOOP = entry -> {
    };

    void record(AutomationAuditEntry entry);
}
