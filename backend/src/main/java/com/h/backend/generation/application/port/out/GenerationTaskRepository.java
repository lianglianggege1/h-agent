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

    default List<GenerationTask> findByUser(Long userId, int limit) { return List.of(); }

    default List<GenerationTask> findByUser(Long userId, int limit, int offset) {
        if(offset!=0) throw new UnsupportedOperationException("Paging not implemented");
        return findByUser(userId,limit);
    }
    default List<GenerationTask> findStaleSubmissions(Instant before, int limit) { return List.of(); }

    List<GenerationTask> findDue(Instant now, int limit);
}
