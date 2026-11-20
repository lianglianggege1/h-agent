package com.h.capabilities.generation.application.port.out;

import com.h.capabilities.generation.domain.model.GenerationTask;

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

    default List<GenerationTask> findStaleSubmissions(Instant before, int limit, String owner) {
        return findStaleSubmissions(before, limit);
    }

    List<GenerationTask> findDue(Instant now, int limit);

    default List<GenerationTask> findDue(Instant now, int limit, String owner) {
        return findDue(now, limit);
    }
}
