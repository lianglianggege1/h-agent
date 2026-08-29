package com.h.agent.observability.semantic;

public record ContentLimits(
        int maxInlineBlockBytes,
        int maxObservationBytes,
        int maxStructureDepth,
        int maxCollectionElements
) {

    public static ContentLimits defaults() {
        return new ContentLimits(128 * 1024, 256 * 1024, 16, 512);
    }
}
