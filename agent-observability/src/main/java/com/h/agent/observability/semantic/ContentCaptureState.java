package com.h.agent.observability.semantic;

public enum ContentCaptureState {
    INLINE,
    REFERENCE,
    MIRROR_QUEUED,
    MIRRORED,
    TRUNCATED_BY_LIMIT,
    SOURCE_UNAVAILABLE,
    DROPPED_OVERLOAD,
    CAPTURE_ERROR
}
