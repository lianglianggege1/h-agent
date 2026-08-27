package com.h.backend.generation.application.service;

import com.h.backend.generation.application.port.out.GeneratedArtifactAttachment;
import com.h.backend.generation.application.port.out.GeneratedArtifactStoragePort;
import com.h.backend.generation.application.port.out.GenerationChatProjectionPort;
import com.h.backend.generation.application.port.out.GenerationTaskRepository;
import com.h.backend.generation.application.port.out.ProviderFilePort;
import com.h.backend.generation.domain.model.GenerationStatus;
import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.domain.model.GeneratedArtifact;
import com.h.backend.generation.domain.model.TextToVideoSpec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 物化服务挂接语义测试（新计划任务 3）：
 * 异步生成对象只有写入 generation_tasks 的 artifact type/key 后才算挂接，
 * 该持久化必须发生在 storeVideo 的挂接回调内（从而被 Coordinator 事务覆盖）。
 */
class MaterializeGeneratedArtifactServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:00:00Z");

    private final GenerationTaskRepository taskRepository = mock(GenerationTaskRepository.class);
    private final ProviderFilePort providerFilePort = mock(ProviderFilePort.class);
    private final GeneratedArtifactStoragePort artifactStoragePort = mock(GeneratedArtifactStoragePort.class);
    private final GenerationChatProjectionPort chatProjectionPort = mock(GenerationChatProjectionPort.class);
    private final MaterializeGeneratedArtifactService service = new MaterializeGeneratedArtifactService(
            taskRepository, providerFilePort, artifactStoragePort, chatProjectionPort
    );

    @Test
    void executePersistsArtifactAndProjectionInsideStoragePortAttachment() {
        GenerationTask task = materializingTask();
        ProviderFilePort.DownloadableFile file = new ProviderFilePort.DownloadableFile(
                "file-1", "video.mp4", "video/mp4", 123L, "https://provider/video.mp4");
        InputStream downloadStream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        when(providerFilePort.retrieve("file-1")).thenReturn(file);
        when(providerFilePort.openDownload(file)).thenReturn(downloadStream);
        // 模拟存储 Adapter：挂接回调在 Coordinator 事务内同步执行
        when(artifactStoragePort.storeVideo(eq("session-1"), eq(file), eq(downloadStream), any()))
                .thenAnswer(invocation -> {
                    GeneratedArtifactAttachment<Object> attachment = invocation.getArgument(3);
                    return attachment.attach(new GeneratedArtifact(
                            "res-1", "OBJECT_STORAGE", "resources/v1/videos/2026/08/res-1.mp4",
                            "video/mp4", "res-1.mp4", 123L));
                });

        service.execute(task, NOW);

        assertEquals(GenerationStatus.SUCCEEDED, task.status());
        assertNotNull(task.artifact());
        assertEquals("res-1", task.artifact().resourceId());
        verify(taskRepository).save(task);
        verify(chatProjectionPort).updateMessage(task);
    }

    @Test
    void executeSkipsTasksThatAlreadyCarryAnArtifact() {
        GenerationTask task = materializingTask();
        GeneratedArtifact artifact = new GeneratedArtifact(
                "res-done", "OBJECT_STORAGE", "resources/v1/videos/2026/08/res-done.mp4",
                "video/mp4", "res-done.mp4", 1L);
        task.complete(artifact, NOW);

        service.execute(task, NOW);

        verify(providerFilePort, org.mockito.Mockito.never()).retrieve(any());
        verify(taskRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void attachmentFailurePropagatesSoCoordinatorCanCompensateObject() {
        GenerationTask task = materializingTask();
        ProviderFilePort.DownloadableFile file = new ProviderFilePort.DownloadableFile(
                "file-1", "video.mp4", "video/mp4", 123L, "https://provider/video.mp4");
        when(providerFilePort.retrieve("file-1")).thenReturn(file);
        when(providerFilePort.openDownload(file)).thenReturn(new ByteArrayInputStream(new byte[0]));
        IllegalStateException boom = new IllegalStateException("artifact 挂接失败");
        when(artifactStoragePort.storeVideo(eq("session-1"), eq(file), any(), any()))
                .thenAnswer(invocation -> {
                    GeneratedArtifactAttachment<Object> attachment = invocation.getArgument(3);
                    return attachment.attach(new GeneratedArtifact(
                            "res-1", "OBJECT_STORAGE", "key", "video/mp4", "res-1.mp4", 1L));
                });
        doFailOnSave(boom);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.execute(task, NOW));

        assertEquals(boom, thrown);
    }

    private void doFailOnSave(RuntimeException boom) {
        org.mockito.Mockito.doThrow(boom).when(taskRepository).save(any());
    }

    private GenerationTask materializingTask() {
        GenerationTask task = GenerationTask.create(
                "task-1",
                1L,
                "session-1",
                TextToVideoSpec.withDefaults("原始提示词", "最终提示词", "MiniMax-Hailuo-2.3", null, null, false, false, false),
                NOW.minusSeconds(600)
        );
        task.markSubmitted("provider-task-1", NOW.minusSeconds(300), NOW.minusSeconds(600));
        task.startMaterialization("file-1", NOW.minusSeconds(60));
        return task;
    }
}
