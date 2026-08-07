package com.h.backend.generation.infrastructure.persistence;

import com.h.backend.generation.application.port.out.GenerationTaskRepository;
import com.h.backend.generation.domain.model.GeneratedArtifact;
import com.h.backend.generation.domain.model.GenerationStatus;
import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.domain.model.TextToVideoSpec;
import com.h.backend.generation.domain.model.ImageToVideoSpec;
import com.h.backend.generation.domain.model.VideoGenerationSpec;
import com.h.backend.generation.domain.model.GenerationType;
import com.h.backend.generation.infrastructure.persistence.entity.GenerationTaskEntity;
import com.h.backend.generation.infrastructure.persistence.mapper.GenerationTaskMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class GenerationTaskRepositoryImpl implements GenerationTaskRepository {
    private final GenerationTaskMapper mapper;
    private final ObjectMapper objectMapper;

    public GenerationTaskRepositoryImpl(GenerationTaskMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(GenerationTask task) {
        GenerationTaskEntity entity = toEntity(task);
        if (mapper.selectById(task.id()) == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
    }

    @Override
    public Optional<GenerationTask> findById(String taskId) {
        GenerationTaskEntity entity = mapper.selectById(taskId);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<GenerationTask> findDue(Instant now, int limit) {
        return mapper.selectDue(toLocalDateTime(now), limit).stream().map(this::toDomain).toList();
    }

    private GenerationTaskEntity toEntity(GenerationTask task) {
        try {
            GenerationTaskEntity entity = new GenerationTaskEntity();
            entity.setId(task.id());
            entity.setUserId(task.userId());
            entity.setSessionId(task.sessionId());
            entity.setGenerationType(task.generationType().name());
            entity.setProvider(task.provider());
            entity.setStatus(task.status().getName());
            entity.setSpecJson(objectMapper.writeValueAsString(task.spec()));
            entity.setProviderTaskId(task.providerTaskId());
            entity.setProviderStatus(task.providerStatus());
            entity.setProviderFileId(task.providerFileId());
            entity.setChatMessageId(task.chatMessageId());
            entity.setRetryCount(task.retryCount());
            entity.setNextPollAt(toLocalDateTime(task.nextPollAt()));
            entity.setFailureMessage(task.failureMessage());
            entity.setCreatedAt(toLocalDateTime(task.createdAt()));
            entity.setUpdatedAt(toLocalDateTime(task.updatedAt()));
            entity.setCompletedAt(toLocalDateTime(task.completedAt()));
            if (task.artifact() != null) {
                entity.setArtifactId(task.artifact().resourceId());
                entity.setArtifactStorageType(task.artifact().storageType());
                entity.setArtifactStorageKey(task.artifact().storageKey());
                entity.setArtifactMimeType(task.artifact().mimeType());
                entity.setArtifactFileName(task.artifact().fileName());
                entity.setArtifactSize(task.artifact().fileSize());
            }
            return entity;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize generation task", exception);
        }
    }

    private GenerationTask toDomain(GenerationTaskEntity entity) {
        try {
            VideoGenerationSpec spec = readSpec(entity);
            GeneratedArtifact artifact = entity.getArtifactId() == null ? null : new GeneratedArtifact(
                    entity.getArtifactId(), entity.getArtifactStorageType(), entity.getArtifactStorageKey(),
                    entity.getArtifactMimeType(), entity.getArtifactFileName(), entity.getArtifactSize()
            );
            return GenerationTask.rehydrate(
                    entity.getId(), entity.getUserId(), entity.getSessionId(), spec,
                    GenerationStatus.fromName(entity.getStatus()), entity.getProviderTaskId(), entity.getProviderStatus(),
                    entity.getProviderFileId(), entity.getChatMessageId(), artifact,
                    entity.getRetryCount() == null ? 0 : entity.getRetryCount(), toInstant(entity.getNextPollAt()),
                    entity.getFailureMessage(), toInstant(entity.getCreatedAt()), toInstant(entity.getUpdatedAt()),
                    toInstant(entity.getCompletedAt())
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to restore generation task " + entity.getId(), exception);
        }
    }

    private VideoGenerationSpec readSpec(GenerationTaskEntity entity) throws Exception {
        GenerationType type = GenerationType.valueOf(entity.getGenerationType());
        return switch (type) {
            case TEXT_TO_VIDEO -> objectMapper.readValue(entity.getSpecJson(), TextToVideoSpec.class);
            case IMAGE_TO_VIDEO -> objectMapper.readValue(entity.getSpecJson(), ImageToVideoSpec.class);
            default -> throw new IllegalStateException("Unsupported video generation type: " + type);
        };
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }
}
