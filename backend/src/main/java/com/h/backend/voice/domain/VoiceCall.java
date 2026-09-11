package com.h.backend.voice.domain;

import lombok.Data;

@Data
public class VoiceCall {
    private long endingAt;
    private String id;
    private Long userId;
    private String sessionId;
    private String requestId;
    private Long promptId;
    private String systemPrompt;
    private String modelName;
    private String roomName;
    private String participantIdentity;
    private String dispatchId;
    private String claimSecret;
    private String workerId;
    private long workerEpoch;
    private boolean workerReady;
    private boolean participantJoined;
    private long leaseUntil;
    private long disconnectedAt;
    private String state;
    private String reason;
    private boolean contextDirty;
    private boolean cleanupPending = true;
    private long createdAt;
    private long updatedAt;

    public boolean terminal() { return "ENDED".equals(state) || "FAILED".equals(state); }
}
