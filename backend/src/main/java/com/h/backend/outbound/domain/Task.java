package com.h.backend.outbound.domain;

import lombok.Data;

@Data
public class Task {
    private Long id;
    private Long userId;
    private String requestId;
    private String name;
    private String agentBindingSnapshot;
    private String communicationGoal;
    private String contentHash;
    private String status;
    private String statusReason;
    private long createdAt;
    private long updatedAt;

    public boolean terminal() {
        return "COMPLETED".equals(status) || "STOPPED".equals(status);
    }
}
