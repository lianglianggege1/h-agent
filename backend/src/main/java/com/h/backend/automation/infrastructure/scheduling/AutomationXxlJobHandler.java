package com.h.backend.automation.infrastructure.scheduling;

import com.h.backend.automation.application.AutomationRunCoordinator;
import com.h.backend.automation.domain.AutomationRunStatus;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "automation.xxl-job", name = "enabled", havingValue = "true")
public class AutomationXxlJobHandler {

    private static final String MARKER_PREFIX = "automation:v1:";

    private final AutomationRunCoordinator coordinator;
    private final ObjectMapper objectMapper;

    public AutomationXxlJobHandler(AutomationRunCoordinator coordinator, ObjectMapper objectMapper) {
        this.coordinator = coordinator;
        this.objectMapper = objectMapper;
    }

    @XxlJob("automationDispatchHandler")
    public void dispatch() {
        try {
            DispatchRequest request = decode(XxlJobHelper.getJobParam(), objectMapper);
            AutomationRunCoordinator.XxlExecutionResult result = coordinator.executeXxl(
                    request.taskId(), request.taskRevision(), request.triggerType(), request.runId(),
                    Instant.ofEpochMilli(XxlJobHelper.getLogDateTime()),
                    "xxl:" + XxlJobHelper.getLogId()
            );
            XxlJobHelper.log("automation taskId={} revision={} runId={} status={}",
                    request.taskId(), request.taskRevision(), result.runId(), result.status());
            if (result.succeeded()) {
                XxlJobHelper.handleSuccess(result.message());
            } else if (AutomationRunStatus.TIMED_OUT.name().equals(result.status())) {
                XxlJobHelper.handleTimeout(result.message());
            } else {
                XxlJobHelper.handleFail(result.message());
            }
        } catch (RuntimeException error) {
            String message = error.getMessage() == null ? "自动化执行失败" : error.getMessage();
            XxlJobHelper.log("automation dispatch failed: {}", message);
            XxlJobHelper.handleFail(message);
        }
    }

    static DispatchRequest decode(String value, ObjectMapper objectMapper) {
        try {
            JsonNode json = objectMapper.readTree(value);
            String marker = json.path("marker").asText();
            long revision = json.path("revision").asLong(0);
            String triggerType = json.path("triggerType").asText("SCHEDULED").toUpperCase();
            String runId = json.path("runId").asText(null);
            if (marker == null || !marker.startsWith(MARKER_PREFIX)
                    || marker.length() == MARKER_PREFIX.length() || revision <= 0
                    || !("SCHEDULED".equals(triggerType) || "MANUAL".equals(triggerType))
                    || ("MANUAL".equals(triggerType) && (runId == null || runId.isBlank()))) {
                throw new IllegalArgumentException("无效的自动化 XXL-Job 参数");
            }
            return new DispatchRequest(marker.substring(MARKER_PREFIX.length()), revision, triggerType, runId);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("无效的自动化 XXL-Job 参数", error);
        }
    }

    record DispatchRequest(String taskId, long taskRevision, String triggerType, String runId) {
    }
}
