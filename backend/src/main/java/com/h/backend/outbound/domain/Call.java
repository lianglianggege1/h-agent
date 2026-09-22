package com.h.backend.outbound.domain;

import lombok.Data;

@Data
public class Call {
    private Long id;
    private Long taskId;
    private Long contactId;
    private Long userId;
    private String phoneSnapshot;
    private String nameSnapshot;
    private String voiceCallId;
    private String originateJobId;
    private String originateState = "NOT_SUBMITTED";
    private boolean remoteEnded;
    private String stage;
    private String connectResult;
    private String dialogueResult;
    private String reason;
    private String timeFacts;
    private long createdAt;
    private long updatedAt;

    public boolean active() {
        return "PREPARING".equals(stage) || "DIALING".equals(stage)
                || "ACTIVE".equals(stage) || "ENDING".equals(stage)
                || "UNKNOWN".equals(stage);
    }

    public boolean terminal() {
        return "FINISHED".equals(stage);
    }
}
