package com.h.backend.automation.interfaces.web;

import com.h.backend.automation.application.AutomationRunCoordinator;
import com.h.backend.automation.application.AutomationTaskCommand;
import com.h.backend.automation.application.AutomationTaskService;
import com.h.backend.automation.interfaces.dto.AutomationRunDto;
import com.h.backend.automation.interfaces.dto.AutomationTaskDto;
import com.h.backend.automation.interfaces.dto.AutomationTaskRequest;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/automations")
public class AutomationController {

    private final AutomationTaskService taskService;
    private final AutomationRunCoordinator runCoordinator;

    public AutomationController(AutomationTaskService taskService, AutomationRunCoordinator runCoordinator) {
        this.taskService = taskService;
        this.runCoordinator = runCoordinator;
    }

    @GetMapping
    public ApiResponse<List<AutomationTaskDto>> list(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return ApiResponse.ok(taskService.list(principal.userId()).stream().map(AutomationTaskDto::from).toList());
    }

    @PostMapping
    public ApiResponse<AutomationTaskDto> create(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestBody AutomationTaskRequest request
    ) {
        return ApiResponse.ok(AutomationTaskDto.from(taskService.create(
                principal.userId(), command(request), "UI"
        )));
    }

    @PutMapping("/{taskId}")
    public ApiResponse<AutomationTaskDto> update(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String taskId,
            @RequestBody AutomationTaskRequest request
    ) {
        if (request.expectedRevision() == null) {
            throw new BusinessException(40031, "expectedRevision 不能为空");
        }
        return ApiResponse.ok(AutomationTaskDto.from(taskService.update(
                principal.userId(), taskId, request.expectedRevision(), command(request)
        )));
    }

    @DeleteMapping("/{taskId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String taskId
    ) {
        taskService.delete(principal.userId(), taskId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{taskId}/runs")
    public ApiResponse<AutomationRunDto> runNow(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String taskId
    ) {
        return ApiResponse.ok(AutomationRunDto.from(runCoordinator.runNow(principal.userId(), taskId)));
    }

    @GetMapping("/{taskId}/runs")
    public ApiResponse<List<AutomationRunDto>> runs(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String taskId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(taskService.runs(principal.userId(), taskId, limit)
                .stream().map(AutomationRunDto::from).toList());
    }

    private static AutomationTaskCommand command(AutomationTaskRequest request) {
        return new AutomationTaskCommand(
                request.name(), request.instruction(), request.agentId(), request.runtime(),
                request.cronExpression(), request.zoneId(), request.enabled()
        );
    }
}
