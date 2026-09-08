package com.h.backend.automation.infrastructure.scheduling;

import com.h.backend.automation.application.AutomationRunCoordinator;
import com.h.backend.automation.application.RunAdmissionModule.AdmissionStatus;
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
        DispatchRequest request = decode(XxlJobHelper.getJobParam(), objectMapper);
        AdmissionStatus status = coordinator.submitScheduled(
                request.taskId(), request.taskRevision(),
                Instant.ofEpochMilli(XxlJobHelper.getLogDateTime()),
                Long.toString(XxlJobHelper.getLogId())
        );
        XxlJobHelper.log("automation taskId={} revision={} admission={}",
                request.taskId(), request.taskRevision(), status);
    }

    static DispatchRequest decode(String value, ObjectMapper objectMapper) {
        try {
            JsonNode json = objectMapper.readTree(value);
            String marker = json.path("marker").asText();
            long revision = json.path("revision").asLong(0);
            if (marker == null || !marker.startsWith(MARKER_PREFIX)
                    || marker.length() == MARKER_PREFIX.length() || revision <= 0) {
                throw new IllegalArgumentException("无效的自动化 XXL-Job 参数");
            }
            return new DispatchRequest(marker.substring(MARKER_PREFIX.length()), revision);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("无效的自动化 XXL-Job 参数", error);
        }
    }

    record DispatchRequest(String taskId, long taskRevision) {
    }
}
