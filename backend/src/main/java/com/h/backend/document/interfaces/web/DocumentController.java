package com.h.backend.document.interfaces.web;

import com.h.backend.common.api.ApiResponse;
import com.h.capabilities.document.application.DocumentWorkflow;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 文档 HTTP 入口，仅使用认证用户身份调用应用服务；复核图片使用独立的二进制响应。 */
@RestController
@RequestMapping("/api/chat/documents/sessions/{session}")
public class DocumentController {
    private final DocumentWorkflow workflow;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.h.backend.document.infrastructure.a2a.A2ADocumentDispatch remote;

    public DocumentController(DocumentWorkflow workflow) {
        this.workflow = workflow;
    }

    public record Action(long revision, String action, String hint, boolean allowUnchanged) {}

    @GetMapping("/settings")
    public ApiResponse<Map<String, Object>> settings(
            @AuthenticationPrincipal AuthUserPrincipal user, @PathVariable String session) {
        return ApiResponse.ok(workflow.settings(user.userId(), session));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<Map<String, Object>>> tasks(
            @AuthenticationPrincipal AuthUserPrincipal user, @PathVariable String session) {
        return ApiResponse.ok(workflow.list(user.userId(), session));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<Map<String, Object>> review(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable String session,
            @PathVariable String id) {
        return ApiResponse.ok(workflow.review(user.userId(), session, id));
    }

    @PostMapping("/tasks/{id}/decisions")
    public ApiResponse<Map<String, Object>> decide(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable String session,
            @PathVariable String id,
            @RequestBody DocumentWorkflow.Decision request) {
        return ApiResponse.ok(workflow.decide(user.userId(), session, id, request));
    }

    @PostMapping("/tasks/{id}/actions")
    public ApiResponse<Map<String, Object>> action(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable String session,
            @PathVariable String id,
            @RequestBody Action request) {
        if (remote != null && "CANCEL".equals(request.action())) {
            var task = workflow.require(user.userId(), session, id);
            if (remote.accepts(task)) {
                try {
                    remote.cancel(task);
                    return ApiResponse.ok(workflow.review(user.userId(), session, id));
                } catch (IllegalStateException unavailable) {
                    // The local and remote actions share the task row. Cancellation is idempotent.
                }
            }
        }
        return ApiResponse.ok(
                workflow.action(
                        user.userId(),
                        session,
                        id,
                        request.revision(),
                        request.action(),
                        request.hint(),
                        request.allowUnchanged()));
    }

    @PostMapping("/tasks/{id}/blocks/{block}/reocr")
    public ApiResponse<String> reocrBlock(@AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable String session, @PathVariable String id, @PathVariable String block,
            @RequestBody RegionRequest request) throws Exception {
        return ApiResponse.ok(workflow.reocrBlock(user.userId(), session, id, block, request.revision()));
    }

    public record RegionRequest(long revision) {}

    @GetMapping("/tasks/{id}/blocks/{block}/image")
    public ResponseEntity<byte[]> image(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable String session,
            @PathVariable String id,
            @PathVariable String block)
            throws Exception {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Type", "image/png")
                .body(workflow.blockImage(user.userId(), session, id, block));
    }
}
