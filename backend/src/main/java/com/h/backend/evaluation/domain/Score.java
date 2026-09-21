package com.h.backend.evaluation.domain;

public record Score(
        String graderId,
        String graderVersion,
        ScoreStatus status,
        Double value,
        boolean required,
        double threshold
) {
    public Score(String graderId, String graderVersion, ScoreStatus status, Double value, boolean required) {
        this(graderId, graderVersion, status, value, required, 1.0);
    }

    public Score {
        if (graderId == null || graderId.isBlank()) {
            throw new IllegalArgumentException("graderId is required");
        }
        if (graderVersion == null || graderVersion.isBlank()) {
            throw new IllegalArgumentException("graderVersion is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (value != null && (value.isNaN() || value.isInfinite())) {
            throw new IllegalArgumentException("score value must be finite");
        }
        if (Double.isNaN(threshold) || Double.isInfinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold must be between 0 and 1");
        }
    }
}
