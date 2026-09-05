package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationTask;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AutomationTaskRepository {
    AutomationTask insert(AutomationTask task);
    AutomationTask updateOwned(Long userId, String taskId, long expectedRevision, AutomationTask replacement);
    Optional<AutomationTask> findOwned(Long userId, String taskId);
    List<AutomationTask> listOwned(Long userId);
    boolean softDeleteOwned(Long userId, String taskId);
    List<AutomationTask> claimDue(Instant now, int limit, String leaseOwner, Duration leaseDuration);
    void releaseLease(String taskId, String leaseOwner, Instant nextRunAt, Instant lastRunAt, String lastStatus);
    void recordManualRunResult(String taskId, Instant lastRunAt, String lastStatus);
    AutomationRun insertRun(AutomationRun run);
    void completeRun(String runId, String status, Instant finishedAt, String sessionId, String output, String errorMessage);
    List<AutomationRun> listRunsOwned(Long userId, String taskId, int limit);
}
