package com.h.backend.outbound.infrastructure;

public interface DialingProvider {
    DialResult originate(String callId, String extension, String domain, String wsUrl);
    void hangup(String callId, String cause);

    default RemoteCallState query(String callId) {
        return RemoteCallState.UNKNOWN;
    }

    enum DialStatus { ORIGINATED, FAILED, BUSY, NO_ANSWER, UNKNOWN }
    enum RemoteCallState { PRESENT, ABSENT, UNKNOWN }
    record DialResult(DialStatus status, String detail, String jobId) {
        public DialResult(DialStatus status, String detail) {
            this(status, detail, null);
        }
    }
}
