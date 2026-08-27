package com.h.backend.generation.application.service;

import com.h.backend.generation.application.port.out.GeneratedArtifactStoragePort;
import com.h.backend.generation.application.port.out.GenerationChatProjectionPort;
import com.h.backend.generation.application.port.out.ProviderFilePort;
import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.application.port.out.GenerationTaskRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

@Service
public class MaterializeGeneratedArtifactService {
    private final GenerationTaskRepository taskRepository;
    private final ProviderFilePort providerFilePort;
    private final GeneratedArtifactStoragePort artifactStoragePort;
    private final GenerationChatProjectionPort chatProjectionPort;

    public MaterializeGeneratedArtifactService(
            GenerationTaskRepository taskRepository,
            ProviderFilePort providerFilePort,
            GeneratedArtifactStoragePort artifactStoragePort,
            GenerationChatProjectionPort chatProjectionPort
    ) {
        this.taskRepository = taskRepository;
        this.providerFilePort = providerFilePort;
        this.artifactStoragePort = artifactStoragePort;
        this.chatProjectionPort = chatProjectionPort;
    }

    public void execute(GenerationTask task, Instant now) {
        if (task.artifact() != null) {
            return;
        }
        ProviderFilePort.DownloadableFile file = providerFilePort.retrieve(task.providerFileId());
        try (InputStream inputStream = providerFilePort.openDownload(file)) {
            // 挂接语义（新计划任务 3）：artifact type/key 写入 generation_tasks
            // 及投影更新都发生在 storeVideo 的挂接回调内，由 Coordinator 的
            // PROPAGATION_REQUIRED 事务覆盖；事务 rollback 时对象被 best-effort discard。
            artifactStoragePort.storeVideo(task.sessionId(), file, inputStream, artifact -> {
                task.complete(artifact, now);
                taskRepository.save(task);
                chatProjectionPort.updateMessage(task);
                return null;
            });
        } catch (IOException ex) {
            throw new IllegalStateException("读取视频下载流失败", ex);
        }
    }
}
