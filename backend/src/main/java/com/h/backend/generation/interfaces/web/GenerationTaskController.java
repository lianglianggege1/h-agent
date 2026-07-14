package com.h.backend.generation.interfaces.web;

import com.h.backend.generation.application.port.out.GenerationTaskRepository;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/generation/tasks")
public class GenerationTaskController {
    private final GenerationTaskRepository taskRepository;

    public GenerationTaskController(GenerationTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> getTask(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String taskId
    ) {
        return taskRepository.findById(taskId)
                .filter(task -> principal != null && principal.userId().equals(task.userId()))
                .map(task -> ResponseEntity.ok(Map.<String, Object>of(
                        "taskId", task.id(),
                        "status", task.status().getName(),
                        "statusCnName", task.status().getCnName(),
                        "generationType", task.generationType().name(),
                        "messageId", task.chatMessageId() == null ? "" : task.chatMessageId().toString()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
