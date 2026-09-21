package com.h.backend.evaluation.domain;

public record GraderRequirement(
        String graderId,
        String graderVersion,
        double threshold
) {
    public GraderRequirement {
        if (graderId == null || graderId.isBlank()) {
            throw new IllegalArgumentException("graderId is required");
        }
        if (graderVersion == null || graderVersion.isBlank()) {
            throw new IllegalArgumentException("graderVersion is required");
        }
        if (Double.isNaN(threshold) || Double.isInfinite(threshold)
                || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold must be between 0 and 1");
        }
    }
}
