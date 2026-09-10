package com.h.backend.automation.interfaces.web;

import com.h.backend.automation.application.AutomationProposalModule;
import com.h.backend.automation.application.AutomationRunCoordinator;
import com.h.backend.automation.application.AutomationTaskCommand;
import com.h.backend.automation.application.AutomationTaskService;
import com.h.backend.automation.application.DeliveryModule;
import com.h.backend.automation.application.SchedulerProjectionRepository;
import com.h.backend.automation.domain.AutomationProposal;
import com.h.backend.automation.interfaces.dto.AutomationDeliveryDto;
import com.h.backend.automation.interfaces.dto.AutomationProposalDto;
import com.h.backend.automation.interfaces.dto.AutomationRunDto;
import com.h.backend.automation.interfaces.dto.AutomationTaskDto;
import com.h.backend.automation.interfaces.dto.AutomationTaskRequest;
import com.h.backend.automation.interfaces.dto.AutomationTaskStateRequest;
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
    private final AutomationProposalModule proposalModule;
    private final DeliveryModule deliveryModule;
    private final SchedulerProjectionRepository schedulerProjectionRepository;

    public AutomationController(
            AutomationTaskService taskService,
            AutomationRunCoordinator runCoordinator,
            AutomationProposalModule proposalModule,
            DeliveryModule deliveryModule,
            SchedulerProjectionRepository schedulerProjectionRepository
    ) {
        this.taskService = taskService;
        this.runCoordinator = runCoordinator;
        this.proposalModule = proposalModule;
        this.deliveryModule = deliveryModule;
        this.schedulerProjectionRepository = schedulerProjectionRepository;
    }

    @GetMapping
    public ApiResponse<List<AutomationTaskDto>> list(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return ApiResponse.ok(taskService.list(principal.userId()).stream()
                .map(this::dto)
                .toList());
    }

    @PostMapping
    public ApiResponse<AutomationTaskDto> create(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestBody AutomationTaskRequest request
    ) {
        return ApiResponse.ok(dto(taskService.create(
                principal.userId(), command(request), "UI", request.sessionId()
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
        return ApiResponse.ok(dto(taskService.update(
                principal.userId(), taskId, request.expectedRevision(), command(request)
        )));
    }

    @PostMapping("/{taskId}/enable")
    public ApiResponse<AutomationTaskDto> enable(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String taskId,
            @RequestBody AutomationTaskStateRequest request
    ) {
        return ApiResponse.ok(dto(taskService.enable(
                principal.userId(), taskId, requiredRevision(request)
        )));
    }

    @PostMapping("/{taskId}/disable")
    public ApiResponse<AutomationTaskDto> disable(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String taskId,
            @RequestBody AutomationTaskStateRequest request
    ) {
        return ApiResponse.ok(dto(taskService.disable(
                principal.userId(), taskId, requiredRevision(request)
        )));
    }

    private AutomationTaskDto dto(com.h.backend.automation.domain.AutomationTask task) {
        return AutomationTaskDto.from(
                task,
                schedulerProjectionRepository.findStatus(task.id()).orElse(null),
                "XXL_JOB"
        );
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

    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<AutomationRunDto> cancelRun(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String runId
    ) {
        return ApiResponse.ok(AutomationRunDto.from(runCoordinator.requestCancel(principal.userId(), runId)));
    }

    @GetMapping("/runs/{runId}/deliveries")
    public ApiResponse<List<AutomationDeliveryDto>> deliveries(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String runId
    ) {
        return ApiResponse.ok(deliveryModule.listDeliveries(principal.userId(), runId)
                .stream().map(AutomationDeliveryDto::from).toList());
    }

    // ---- 提案：聊天内写操作只生成提案，用户在轮次外确认 ----

    @GetMapping("/proposals")
    public ApiResponse<List<AutomationProposalDto>> pendingProposals(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestParam(required = false) String sessionId
    ) {
        var proposals = sessionId == null || sessionId.isBlank()
                ? proposalModule.listPending(principal.userId())
                : proposalModule.listForSession(principal.userId(), sessionId);
        List<AutomationProposalDto> views = proposals.stream()
                .map(proposal -> {
                    AutomationProposalModule.ProposalView view = proposalModule.describe(proposal);
                    return AutomationProposalDto.from(proposal, view.name(), view.instruction(), view.agentId(),
                            view.cronExpression(), view.zoneId(), view.deliverySink(),
                            view.upcomingFires());
                })
                .toList();
        return ApiResponse.ok(views);
    }

    @PostMapping("/proposals/{proposalId}/confirm")
    public ApiResponse<AutomationProposalDto> confirmProposal(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String proposalId
    ) {
        AutomationProposal confirmed = proposalModule.confirm(principal.userId(), proposalId);
        AutomationProposalModule.ProposalView view = proposalModule.describe(confirmed);
        return ApiResponse.ok(AutomationProposalDto.from(confirmed, view.name(), view.instruction(), view.agentId(),
                view.cronExpression(), view.zoneId(), view.deliverySink(), view.upcomingFires()));
    }

    @PostMapping("/proposals/{proposalId}/discard")
    public ApiResponse<AutomationProposalDto> discardProposal(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String proposalId
    ) {
        return ApiResponse.ok(AutomationProposalDto.from(
                proposalModule.discard(principal.userId(), proposalId), List.of()));
    }

    private static AutomationTaskCommand command(AutomationTaskRequest request) {
        return new AutomationTaskCommand(
                request.name(), request.instruction(), request.agentId(), request.runtime(),
                request.cronExpression(), request.zoneId(), request.enabled(),
                request.deliverySink(), request.deliverySessionId()
        );
    }

    private static long requiredRevision(AutomationTaskStateRequest request) {
        if (request == null || request.expectedRevision() == null) {
            throw new BusinessException(40031, "expectedRevision 不能为空");
        }
        return request.expectedRevision();
    }
}
