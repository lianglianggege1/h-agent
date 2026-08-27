package com.h.backend.generation.domain;

import com.h.backend.generation.domain.model.GeneratedArtifact;
import com.h.backend.generation.domain.model.GenerationStatus;
import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.domain.model.TextToVideoSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationTaskTest {
    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @Test
    void transitionsFromSubmissionToMaterializationAndCompletion() {
        GenerationTask task = newTask();

        task.markSubmitted("provider-task", NOW.plusSeconds(5), NOW);
        task.recordProviderProgress("PROCESSING", NOW.plusSeconds(15), NOW.plusSeconds(5));
        task.startMaterialization("provider-file", NOW.plusSeconds(20));
        task.complete(new GeneratedArtifact(
                "resource-1", "OBJECT_STORAGE", "generated-videos/video.mp4", "video/mp4", "video.mp4", 100L
        ), NOW.plusSeconds(30));

        assertEquals(GenerationStatus.SUCCEEDED, task.status());
        assertEquals("resource-1", task.artifact().resourceId());
        assertThrows(IllegalStateException.class, () -> task.retry("late", NOW.plusSeconds(60), NOW.plusSeconds(60)));
    }

    @Test
    void retryKeepsTaskQueryableAndIncrementsCounter() {
        GenerationTask task = newTask();
        task.markSubmitted("provider-task", NOW.plusSeconds(5), NOW);

        task.retry("temporary network failure", NOW.plusSeconds(30), NOW.plusSeconds(5));

        assertEquals(GenerationStatus.RETRY_WAIT, task.status());
        assertEquals(1, task.retryCount());
        assertEquals(NOW.plusSeconds(30), task.nextPollAt());
    }

    @Test
    void exposesStableNameAndChineseDisplayName() {
        assertEquals("IN_PROGRESS", GenerationStatus.IN_PROGRESS.getName());
        assertEquals("生成中", GenerationStatus.IN_PROGRESS.getCnName());
        assertEquals(GenerationStatus.IN_PROGRESS, GenerationStatus.fromName("IN_PROGRESS"));
    }

    private GenerationTask newTask() {
        TextToVideoSpec spec = TextToVideoSpec.withDefaults(
                "原始提示词", "最终提示词", null, null, null, false, false, false
        );
        return GenerationTask.create("task-1", 1L, "session-1", spec, NOW);
    }
}
