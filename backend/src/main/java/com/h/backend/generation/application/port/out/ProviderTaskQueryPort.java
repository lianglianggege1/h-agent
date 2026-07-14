package com.h.backend.generation.application.port.out;

public interface ProviderTaskQueryPort {
    ProviderTaskStatus query(String providerTaskId);

    record ProviderTaskStatus(Status status, String fileId, String failureMessage) {
        public enum Status { PREPARING, QUEUEING, PROCESSING, SUCCESS, FAILED }
    }
}
