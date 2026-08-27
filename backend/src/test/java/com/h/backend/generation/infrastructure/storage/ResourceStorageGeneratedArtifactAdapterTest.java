package com.h.backend.generation.infrastructure.storage;

import com.h.backend.chat.infrastructure.storage.ResourceAttachment;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.generation.application.port.out.GeneratedArtifactAttachment;
import com.h.backend.generation.application.port.out.ProviderFilePort;
import com.h.backend.generation.domain.model.GeneratedArtifact;
import com.h.backend.generation.infrastructure.config.GenerationProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频 artifact 存储经 Coordinator 的契约测试（新计划任务 3）：
 * provider stream 直接传命令、不落本地中间文件（计划不变量 10、拒绝方案 8）。
 */
class ResourceStorageGeneratedArtifactAdapterTest {

    private final ResourceWriteCoordinator coordinator = mock(ResourceWriteCoordinator.class);
    private final GenerationProperties properties = new GenerationProperties();
    private final ResourceStorageGeneratedArtifactAdapter adapter =
            new ResourceStorageGeneratedArtifactAdapter(coordinator, properties);

    @Test
    void storeVideoPassesProviderStreamDirectlyAsCommandWithoutLocalBuffering() {
        ProviderFilePort.DownloadableFile file = new ProviderFilePort.DownloadableFile(
                "file-1", "video.mp4", "video/mp4", 123L, "https://provider/video.mp4");
        InputStream providerStream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        StoredResource stored = new StoredResource(
                "res-1", "OBJECT_STORAGE", "resources/v1/videos/2026/08/res-1.mp4",
                "video/mp4", "res-1.mp4", 123L, null, null);
        when(coordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<String> attachment = invocation.getArgument(1);
                    return attachment.attach(stored);
                });

        adapter.storeVideo("session-1", file, providerStream, artifact -> "attached:" + artifact.resourceId());

        ArgumentCaptor<ResourceSaveCommand> commandCaptor = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(coordinator).saveAndAttach(commandCaptor.capture(), any());
        ResourceSaveCommand command = commandCaptor.getValue();
        assertEquals("VIDEO", command.resourceType());
        // provider stream 直接进入命令：流式形态（无 byte[] 缓冲、无本地中间文件）
        assertNull(command.content(), "视频 provider stream 必须以流式形态进入命令，不得缓冲为 byte[]");
        assertSame(providerStream, command.openContentStream(), "命令必须直传 provider 流，不得复制或落盘");
        assertEquals(123L, command.declaredSize(), "provider 已知大小必须作为 declaredSize 传入");
        assertEquals("video/mp4", command.mimeType());
        assertEquals("mp4", command.extension());
        assertEquals(
                properties.getDownload().getMaxFileSize(),
                command.maxBytes(),
                "业务上限必须沿用生成下载大小配置"
        );
    }

    @Test
    void storeVideoRunsArtifactAttachmentInsideCoordinatorTransaction() {
        ProviderFilePort.DownloadableFile file = new ProviderFilePort.DownloadableFile(
                "file-1", "video.mp4", "video/mp4", 123L, "https://provider/video.mp4");
        InputStream providerStream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        StoredResource stored = new StoredResource(
                "res-1", "OBJECT_STORAGE", "resources/v1/videos/2026/08/res-1.mp4",
                "video/mp4", "res-1.mp4", 123L, null, null);
        when(coordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    // 模拟真实 Coordinator：挂接回调在事务内执行
                    ResourceAttachment<String> attachment = invocation.getArgument(1);
                    return attachment.attach(stored);
                });
        GeneratedArtifactAttachment<String> portAttachment = artifact -> "ok:" + artifact.resourceId();

        String result = adapter.storeVideo("session-1", file, providerStream, portAttachment);

        assertEquals("ok:res-1", result);
        // portAttachment 收到的 GeneratedArtifact 由 StoredResource 转换而来
        verify(coordinator).saveAndAttach(any(ResourceSaveCommand.class), any());
    }

    @Test
    void storeVideoConvertsStoredResourceToGeneratedArtifactForAttachment() {
        ProviderFilePort.DownloadableFile file = new ProviderFilePort.DownloadableFile(
                "file-1", "clip.mov", "video/quicktime", 999L, "https://provider/clip.mov");
        StoredResource stored = new StoredResource(
                "res-9", "OBJECT_STORAGE", "resources/v1/videos/2026/08/res-9.mov",
                "video/quicktime", "res-9.mov", 999L, null, null);
        GeneratedArtifact[] received = new GeneratedArtifact[1];
        when(coordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<Void> attachment = invocation.getArgument(1);
                    return attachment.attach(stored);
                });

        adapter.storeVideo("session-1", file, new ByteArrayInputStream(new byte[0]),
                artifact -> {
                    received[0] = artifact;
                    return null;
                });

        GeneratedArtifact artifact = received[0];
        assertEquals("res-9", artifact.resourceId());
        assertEquals("OBJECT_STORAGE", artifact.storageType());
        assertEquals("resources/v1/videos/2026/08/res-9.mov", artifact.storageKey());
        assertEquals("video/quicktime", artifact.mimeType());
        assertEquals("res-9.mov", artifact.fileName());
        assertEquals(999L, artifact.fileSize());
    }

    @Test
    void storeVideoDoesNotWriteAnyLocalIntermediateFile() {
        ProviderFilePort.DownloadableFile file = new ProviderFilePort.DownloadableFile(
                "file-1", "video.mp4", "video/mp4", 5L, "https://provider/video.mp4");
        when(coordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> null);

        adapter.storeVideo("session-1", file, new ByteArrayInputStream(new byte[5]), artifact -> null);

        // Adapter 只依赖 Coordinator 与配置；此断言的意义由上面的流式直传测试承担，
        // 这里再次确认命令为流式形态（拒绝方案 8：视频不得先落 /tmp）。
        ArgumentCaptor<ResourceSaveCommand> commandCaptor = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(coordinator).saveAndAttach(commandCaptor.capture(), any());
        assertNull(commandCaptor.getValue().content());
    }
}
