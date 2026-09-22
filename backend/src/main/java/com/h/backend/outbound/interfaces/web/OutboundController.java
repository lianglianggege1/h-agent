package com.h.backend.outbound.interfaces.web;

import com.h.backend.common.api.ApiResponse;
import com.h.backend.outbound.application.OutboundModule;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outbound")
public class OutboundController {
    private final OutboundModule outbound;
    public OutboundController(OutboundModule outbound) { this.outbound = outbound; }

    // ── Contacts ──

    public record ContactRow(String phone, String name, String consentBasis) {}

    @PostMapping("/contacts/import")
    public ApiResponse<Map<String, Object>> importContacts(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @RequestBody List<ContactRow> rows) {
        var inputs = rows.stream()
                .map(r -> new OutboundModule.ContactInput(r.phone(), r.name(), r.consentBasis()))
                .toList();
        int inserted = outbound.importContacts(user.userId(), inputs);
        return ApiResponse.ok(Map.of("inserted", inserted));
    }

    @GetMapping("/contacts")
    public ApiResponse<Map<String, Object>> listContacts(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(outbound.listContacts(user.userId(), page, size));
    }

    @PutMapping("/contacts/{id}/dnc")
    public ApiResponse<Map<String, String>> markDnc(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable Long id) {
        outbound.markDnc(user.userId(), id);
        return ApiResponse.ok(Map.of("status", "dnc"));
    }

    // ── Tasks ──

    public record CreateTaskRequest(
            String name,
            String agentId,
            String communicationGoal,
            List<Long> contactIds,
            String requestId) {}

    @PostMapping("/tasks")
    public ApiResponse<Map<String, Object>> createTask(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @RequestBody CreateTaskRequest input) {
        var req = new OutboundModule.CreateTaskInput(
                input.name(), input.agentId(), input.communicationGoal(), input.contactIds(), input.requestId());
        return ApiResponse.ok(outbound.createTask(user.userId(), req));
    }

    @GetMapping("/tasks")
    public ApiResponse<Map<String, Object>> listTasks(
            @AuthenticationPrincipal AuthUserPrincipal user) {
        return ApiResponse.ok(outbound.listTasks(user.userId()));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<Map<String, Object>> taskDetail(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable Long id) {
        return ApiResponse.ok(outbound.taskDetail(user.userId(), id));
    }

    // ── Verified phone-capable agents ──

    @GetMapping("/agents")
    public ApiResponse<Map<String, Object>> listAgents(
            @AuthenticationPrincipal AuthUserPrincipal user) {
        return ApiResponse.ok(outbound.listPhoneAgents());
    }

    // ── Task start/stop ──

    @PostMapping("/tasks/{id}/start")
    public ApiResponse<Map<String, Object>> startTask(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable Long id) {
        outbound.startTask(user.userId(), id);
        return ApiResponse.ok(Map.of("status", "RUNNING"));
    }

    @PostMapping("/tasks/{id}/stop")
    public ApiResponse<Map<String, Object>> stopTask(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable Long id) {
        outbound.stopTask(user.userId(), id);
        return ApiResponse.ok(Map.of("status", "STOPPED"));
    }

    // ── Call: reconcile & resolve unknown ──

    @PostMapping("/calls/{id}/reconcile")
    public ApiResponse<Map<String, Object>> reconcileCall(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable Long id) {
        return ApiResponse.ok(outbound.reconcileCall(user.userId(), id));
    }

    public record ResolveUnknownRequest(String resolution, String note) {}

    @PostMapping("/calls/{id}/resolve-unknown")
    public ApiResponse<Map<String, Object>> resolveUnknown(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable Long id,
            @RequestBody ResolveUnknownRequest req) {
        return ApiResponse.ok(outbound.resolveUnknown(user.userId(), id, req.resolution(), req.note()));
    }
}
