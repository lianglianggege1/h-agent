package com.h.backend.knowledge.interfaces.web;

import com.h.backend.common.api.ApiResponse;
import com.h.backend.knowledge.application.KnowledgeDrafts;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge/documents")
public class KnowledgeDraftController {
    private final KnowledgeDrafts drafts;

    public KnowledgeDraftController(KnowledgeDrafts drafts) { this.drafts = drafts; }

    @GetMapping("/{id}/editor")
    public ApiResponse<Editor> current(@AuthenticationPrincipal AuthUserPrincipal user, @PathVariable long id) {
        return ApiResponse.ok(new Editor(drafts.current(user.userId(), id), drafts.latest(user.userId(), id)));
    }

    @PostMapping("/{id}/drafts")
    public ApiResponse<KnowledgeDrafts.Draft> propose(@AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable long id, @RequestBody Instruction request) {
        return ApiResponse.ok(drafts.propose(user.userId(), id, request.instruction(), request.paragraphs(), request.sourceRefs()));
    }

    @PostMapping("/{id}/drafts/{draft}/apply")
    public ApiResponse<Map<String, Object>> apply(@AuthenticationPrincipal AuthUserPrincipal user,
            @PathVariable long id, @PathVariable String draft, @RequestBody Apply request) {
        return ApiResponse.ok(drafts.commit(user.userId(), id, draft, request.selections()));
    }

    @GetMapping("/source")
    public ApiResponse<String> source(@AuthenticationPrincipal AuthUserPrincipal user, @RequestParam String ref) {
        return ApiResponse.ok(drafts.source(user.userId(), ref));
    }

    public record Instruction(String instruction, List<Integer> paragraphs, List<String> sourceRefs) {}
    public record Apply(List<KnowledgeDrafts.Selection> selections) {}
    public record Editor(KnowledgeDrafts.View current, KnowledgeDrafts.Draft draft) {}
}
