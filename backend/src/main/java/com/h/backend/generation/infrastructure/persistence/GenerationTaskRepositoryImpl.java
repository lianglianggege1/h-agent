package com.h.backend.generation.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.h.backend.generation.application.port.out.GenerationTaskRepository;
import com.h.backend.generation.domain.model.GeneratedArtifact;
import com.h.backend.generation.domain.model.GenerationStatus;
import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.domain.model.GenerationType;
import com.h.backend.generation.domain.model.ImageToVideoSpec;
import com.h.backend.generation.domain.model.TextToVideoSpec;
import com.h.backend.generation.domain.model.VideoGenerationSpec;
import com.h.backend.generation.domain.model.VideoV2Spec;
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
            entity.setVersion(0L);
            mapper.insert(entity);
            task.persisted(0);
        } else {
            entity.setVersion(task.version() + 1);
            if (mapper.update(
                            entity,
                            new UpdateWrapper<GenerationTaskEntity>()
                                    .eq("id", task.id())
                                    .eq("version", task.version()))
                    != 1)
                throw new org.springframework.dao.ConcurrencyFailureException(
                        "Generation task was updated concurrently");
            task.persisted(entity.getVersion());
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

    @Override
    public List<GenerationTask> findByUser(Long userId, int limit) {
        return findByUser(userId, limit, 0);
    }

    @Override
    public List<GenerationTask> findStaleSubmissions(Instant before, int limit) {
        return mapper
                .selectList(
                        new QueryWrapper<GenerationTaskEntity>()
                                .eq("status", "PENDING_SUBMISSION")
                                .lt("updated_at", toLocalDateTime(before))
                                .last("LIMIT " + Math.clamp(limit, 1, 100)))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<GenerationTask> findByUser(Long userId, int limit, int offset) {
        if (offset < 0) throw new IllegalArgumentException("Invalid page offset");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("任务列表数量必须为1至100");
        return mapper
                .selectList(
                        new QueryWrapper<GenerationTaskEntity>()
                                .eq("user_id", userId)
                                .orderByDesc("created_at")
                                .last("LIMIT " + limit + " OFFSET " + offset))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private GenerationTaskEntity toEntity(GenerationTask task) {
        try {
            GenerationTaskEntity entity = new GenerationTaskEntity();
            entity.setId(task.id());
            entity.setResultText(task.resultText());
            entity.setVersion(task.version());
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
            GeneratedArtifact artifact =
                    entity.getArtifactId() == null
                            ? null
                            : new GeneratedArtifact(
                                    entity.getArtifactId(),
                                    entity.getArtifactStorageType(),
                                    entity.getArtifactStorageKey(),
                                    entity.getArtifactMimeType(),
                                    entity.getArtifactFileName(),
                                    entity.getArtifactSize());
            GenerationTask restored =
                    GenerationTask.rehydrate(
                            entity.getId(),
                            entity.getUserId(),
                            entity.getSessionId(),
                            spec,
                            GenerationStatus.fromName(entity.getStatus()),
                            entity.getProviderTaskId(),
                            entity.getProviderStatus(),
                            entity.getProviderFileId(),
                            entity.getChatMessageId(),
                            artifact,
                            entity.getRetryCount() == null ? 0 : entity.getRetryCount(),
                            toInstant(entity.getNextPollAt()),
                            entity.getFailureMessage(),
                            toInstant(entity.getCreatedAt()),
                            toInstant(entity.getUpdatedAt()),
                            toInstant(entity.getCompletedAt()));
            restored.restoredDetails(
                    entity.getResultText(), entity.getVersion() == null ? 0 : entity.getVersion());
            return restored;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to restore generation task " + entity.getId(), exception);
        }
    }

    private VideoGenerationSpec readSpec(GenerationTaskEntity entity) throws Exception {
        GenerationType type = GenerationType.valueOf(entity.getGenerationType());
        return switch (type) {
            case VIDEO_V2 -> objectMapper.readValue(entity.getSpecJson(), VideoV2Spec.class);
            case TEXT_TO_VIDEO ->
                    objectMapper.readValue(entity.getSpecJson(), TextToVideoSpec.class);
            case IMAGE_TO_VIDEO ->
                    objectMapper.readValue(entity.getSpecJson(), ImageToVideoSpec.class);
            default ->
                    throw new IllegalStateException("Unsupported video generation type: " + type);
        };
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }
}
