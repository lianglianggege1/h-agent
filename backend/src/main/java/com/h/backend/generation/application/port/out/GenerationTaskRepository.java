package com.h.backend.generation.application.port.out;

import com.h.backend.generation.domain.model.GenerationTask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persists and reloads generation task aggregates for application use cases.
 */
public interface GenerationTaskRepository {
    void save(GenerationTask task);

    Optional<GenerationTask> findById(String taskId);

    List<GenerationTask> findDue(Instant now, int limit);
}
