package com.h.backend.evaluation.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "evaluation")
public class EvaluationProperties {

    private boolean enabled;
    private int maxConcurrentTrials = 1;
    private int maxTrialsPerExperiment = 300;
    private Duration defaultTrialTimeout = Duration.ofSeconds(120);
    private int maxInteractionTurns = 8;
    private int maxToolCalls = 20;
    private Duration judgeTimeout = Duration.ofSeconds(60);
    private int judgeMaxAttempts = 2;
    private long maxEvidenceBytes = 1_048_576;
    private String requiredJdbcUrlMarker = "h_agent_eval";
    private int requiredRedisDatabase = 15;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxConcurrentTrials() { return maxConcurrentTrials; }
    public void setMaxConcurrentTrials(int value) {
        if (value != 1) {
            throw new IllegalArgumentException("maxConcurrentTrials must be 1 in the first release");
        }
        this.maxConcurrentTrials = value;
    }
    public int getMaxTrialsPerExperiment() { return maxTrialsPerExperiment; }
    public void setMaxTrialsPerExperiment(int value) { this.maxTrialsPerExperiment = requirePositive(value, "maxTrialsPerExperiment"); }
    public Duration getDefaultTrialTimeout() { return defaultTrialTimeout; }
    public void setDefaultTrialTimeout(Duration value) { this.defaultTrialTimeout = requirePositive(value, "defaultTrialTimeout"); }
    public int getMaxInteractionTurns() { return maxInteractionTurns; }
    public void setMaxInteractionTurns(int value) { this.maxInteractionTurns = requirePositive(value, "maxInteractionTurns"); }
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int value) { this.maxToolCalls = requirePositive(value, "maxToolCalls"); }
    public Duration getJudgeTimeout() { return judgeTimeout; }
    public void setJudgeTimeout(Duration value) { this.judgeTimeout = requirePositive(value, "judgeTimeout"); }
    public int getJudgeMaxAttempts() { return judgeMaxAttempts; }
    public void setJudgeMaxAttempts(int value) { this.judgeMaxAttempts = requirePositive(value, "judgeMaxAttempts"); }
    public long getMaxEvidenceBytes() { return maxEvidenceBytes; }
    public void setMaxEvidenceBytes(long value) { this.maxEvidenceBytes = requirePositive(value, "maxEvidenceBytes"); }
    public String getRequiredJdbcUrlMarker() { return requiredJdbcUrlMarker; }
    public void setRequiredJdbcUrlMarker(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("requiredJdbcUrlMarker is required");
        }
        this.requiredJdbcUrlMarker = value;
    }
    public int getRequiredRedisDatabase() { return requiredRedisDatabase; }
    public void setRequiredRedisDatabase(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("requiredRedisDatabase must be non-negative");
        }
        this.requiredRedisDatabase = value;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
