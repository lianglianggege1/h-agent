package com.h.backend.generation.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.domain.model.VideoV2Spec;
import com.h.backend.generation.infrastructure.persistence.entity.GenerationTaskEntity;
import com.h.backend.generation.infrastructure.persistence.mapper.GenerationTaskMapper;

import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class GenerationTaskRepositoryImplTest {
    @Test
    void ignoresRetiredTaskTypesWithoutDeletingHistoricalRecords() {
        var mapper = mock(GenerationTaskMapper.class);
        var retired = new GenerationTaskEntity();
        retired.setId("retired-task");
        retired.setGenerationType("RETIRED_MEDIA_TYPE");
        retired.setSpecJson("{\"legacy\":true}");
        var json = new ObjectMapper();
        var active = new GenerationTaskEntity();
        active.setId("active-video");
        active.setUserId(1L);
        active.setSessionId("session");
        active.setGenerationType("VIDEO_V2");
        active.setStatus("IN_PROGRESS");
        active.setSpecJson(json.writeValueAsString(VideoV2Spec.defaults(
                "generation", "test", null, null, null, null, List.of())));
        when(mapper.selectById("retired-task")).thenReturn(retired);
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(retired, active));
        when(mapper.selectDue(any(), anyInt())).thenReturn(List.of(retired, active));
        var repository = new GenerationTaskRepositoryImpl(mapper, json);

        assertTrue(repository.findById("retired-task").isEmpty());
        assertEquals(List.of("active-video"), repository.findByUser(1L, 20).stream().map(GenerationTask::id).toList());
        assertEquals(List.of("active-video"), repository.findDue(Instant.now(), 20).stream().map(GenerationTask::id).toList());
        assertEquals(List.of("active-video"), repository.findStaleSubmissions(Instant.now(), 20).stream().map(GenerationTask::id).toList());
        verify(mapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void restoresEnhancedTextAndContinuationIntentWithoutDroppingOriginalMedia() {
        var mapper = mock(GenerationTaskMapper.class);
        var row = new AtomicReference<GenerationTaskEntity>();
        when(mapper.selectById("ir")).thenAnswer(i -> row.get());
        when(mapper.insert(any(GenerationTaskEntity.class)))
                .thenAnswer(
                        i -> {
                            row.set(i.getArgument(0));
                            return 1;
                        });
        var repository = new GenerationTaskRepositoryImpl(mapper, new ObjectMapper());
        var spec =
                new VideoV2Spec(
                        "h3_context_ir",
                        "原始描述",
                        "MiniMax-H3",
                        15,
                        "2K",
                        "adaptive",
                        List.of(new VideoV2Spec.Input("original", "first_frame")),
                        null,
                        true);
        var task = GenerationTask.create("ir", 1L, "s", spec, Instant.now());
        task.markSubmitted("v2:provider", Instant.now(), Instant.now());
        task.recordEnhancedPrompt("完整增强提示词", Instant.now());
        repository.save(task);
        var restored = repository.findById("ir").orElseThrow();
        assertEquals(spec, restored.spec());
        assertEquals("完整增强提示词", restored.resultText());
        assertEquals("v2:provider", restored.providerTaskId());
        assertEquals(0, restored.version());
    }

    @Test
    void staleWorkerCannotOverwriteAnotherWorkersTerminalState() {
        var mapper = mock(GenerationTaskMapper.class);
        when(mapper.selectById("task")).thenReturn(new GenerationTaskEntity());
        when(mapper.update(any(GenerationTaskEntity.class), any())).thenReturn(0);
        var repository = new GenerationTaskRepositoryImpl(mapper, new ObjectMapper());
        var task =
                GenerationTask.create(
                        "task",
                        1L,
                        "s",
                        VideoV2Spec.defaults(
                                "generation", "test", null, null, null, null, List.of()),
                        Instant.now());
        assertThrows(ConcurrencyFailureException.class, () -> repository.save(task));
        assertEquals(0, task.version());
        verify(mapper, never()).insert(any(GenerationTaskEntity.class));
    }
}
