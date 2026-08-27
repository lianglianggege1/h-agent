package com.h.backend.generation.infrastructure.storage;

import com.h.backend.chat.application.ResourceContentPolicy;
import com.h.backend.chat.infrastructure.content.ResourceContentInspector;
import com.h.backend.chat.infrastructure.storage.ResourceAttachment;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceStorageErrorKind;
import com.h.backend.chat.infrastructure.storage.ResourceStorageException;
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
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频 artifact 存储经 Coordinator 的契约测试（新计划任务 3）：
 * provider stream 直接传命令、不落本地中间文件（计划不变量 10、拒绝方案 8）。
 *
 * <p>审查修复第 2 项：provider 元数据 size 不可信——storeVideo 必须用
 * unknown-size 流式命令（declaredSize=null），由存储层按实际字节计数定 fileSize；
 * 审查修复第 3 项：provider 下载流经 Inspector 签名校验（video/mp4），
 * 校验通过后的回放流（头字节 + 剩余原流）传给保存命令。
 */
class ResourceStorageGeneratedArtifactAdapterTest {

    /** MP4 ftyp box 魔数（isom brand，24 字节）。 */
    private static final byte[] MP4_FTYP = {
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, // size=24, "ftyp"
            'i', 's', 'o', 'm',                              // major brand
            0x00, 0x00, 0x02, 0x00,                          // minor version
            'i', 's', 'o', 'm', 'm', 'p', '4', '1'           // compatible brands
    };

    private final ResourceWriteCoordinator coordinator = mock(ResourceWriteCoordinator.class);
    private final GenerationProperties properties = new GenerationProperties();
    private final ResourceStorageGeneratedArtifactAdapter adapter =
            new ResourceStorageGeneratedArtifactAdapter(
                    coordinator, properties, new ResourceContentInspector(), new ResourceContentPolicy());

    @Test
    void storeVideoSavesUnknownSizeReplayStreamWithoutTrustingProviderMetadata() throws Exception {
        // 审查修复第 2 项：MinIO SDK known-size 行为下 objectSize=0 会静默上传空对象、
        // size 偏小会静默截断——provider 元数据 file.size()（哪怕存在）不得进入命令；
        // 命令必须是流式 unknown-size 形态，上限沿用生成下载配置。
        ProviderFilePort.DownloadableFile file = new ProviderFilePort.DownloadableFile(
                "file-1", "video.mp4", "video/mp4", 123L, "https://provider/video.mp4");
        byte[] videoBytes = concat(MP4_FTYP, new byte[]{9, 8, 7, 6});
        StoredResource stored = new StoredResource(
                "res-1", "OBJECT_STORAGE", "resources/v1/videos/2026/08/res-1.mp4",
                "video/mp4", "res-1.mp4", (long) videoBytes.length, null, null);
        when(coordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<String> attachment = invocation.getArgument(1);
                    return attachment.attach(stored);
                });

        adapter.storeVideo("session-1", file,
                new ByteArrayInputStream(videoBytes), artifact -> "attached:" + artifact.resourceId());

        ArgumentCaptor<ResourceSaveCommand> commandCaptor = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(coordinator).saveAndAttach(commandCaptor.capture(), any());
        ResourceSaveCommand command = commandCaptor.getValue();
        assertEquals("VIDEO", command.resourceType());
        // 流式形态（无 byte[] 缓冲、无本地中间文件）
        assertNull(command.content(), "视频 provider stream 必须以流式形态进入命令，不得缓冲为 byte[]");
        // 审查修复第 2 项核心断言：declaredSize 必须为 null（不信任 provider 元数据 size）
        assertNull(command.declaredSize(), "provider 元数据 size 不可信，命令必须是 unknown-size 形态");
        assertEquals("video/mp4", command.mimeType());
        assertEquals("mp4", command.extension());
        assertEquals(
                properties.getDownload().getMaxFileSize(),
                command.maxBytes(),
                "业务上限必须沿用生成下载大小配置"
        );
        // 回放流必须完整还原 provider 内容（头字节 + 剩余流）
        assertArrayEquals(videoBytes, command.openContentStream().readAllBytes());
    }

    @Test
    void storeVideoRejectsProviderContentWhenSignatureDoesNotMatch() {
        // 审查修复第 3 项：provider 声明 video/mp4 但下载流是 HTML 主动内容 ——
        // 签名校验拒绝保存，写入路径从未被触碰。
        ProviderFilePort.DownloadableFile file = new ProviderFilePort.DownloadableFile(
                "file-1", "video.mp4", "video/mp4", 123L, "https://provider/video.mp4");
        InputStream providerStream = new ByteArrayInputStream(
                "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        ResourceStorageException error = assertThrows(
                ResourceStorageException.class,
                () -> adapter.storeVideo("session-1", file, providerStream, artifact -> "never"));
        assertTrue(error.getMessage().contains("内容校验"), "拒绝消息应明确指向内容校验");
        verify(coordinator, never()).saveAndAttach(any(ResourceSaveCommand.class), any());
    }

    @Test
    void storeVideoRejectsProviderContentWhenDeclaredMimeConflictsWithSignature() {
        // provider 声明 video/mp4 但字节实际是 PNG —— 签名冲突拒绝。
        ProviderFilePort.DownloadableFile file = new ProviderFilePort.DownloadableFile(
                "file-1", "video.mp4", "video/mp4", 123L, "https://provider/video.mp4");
        byte[] pngBytes = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

        assertThrows(
                ResourceStorageException.class,
                () -> adapter.storeVideo("session-1", file, new ByteArrayInputStream(pngBytes), artifact -> "never"));
        verify(coordinator, never()).saveAndAttach(any(ResourceSaveCommand.class), any());
    }

    @Test
    void storeVideoRunsArtifactAttachmentInsideCoordinatorTransaction() {
        ProviderFilePort.DownloadableFile file = new ProviderFilePort.DownloadableFile(
                "file-1", "video.mp4", "video/mp4", 123L, "https://provider/video.mp4");
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

        String result = adapter.storeVideo("session-1", file,
                new ByteArrayInputStream(MP4_FTYP), portAttachment);

        assertEquals("ok:res-1", result);
        // portAttachment 收到的 GeneratedArtifact 由 StoredResource 转换而来
        verify(coordinator).saveAndAttach(any(ResourceSaveCommand.class), any());
    }

    @Test
    void storeVideoConvertsStoredResourceToGeneratedArtifactForAttachment() {
        // 注意：声明的 MIME 必须与内容签名一致（video/mp4）；
        // clip.mov 只是文件名/扩展名，签名冲突（声明 video/quicktime 而字节是
        // MP4 容器）会被拒绝。
        ProviderFilePort.DownloadableFile file = new ProviderFilePort.DownloadableFile(
                "file-1", "clip.mov", "video/mp4", 999L, "https://provider/clip.mov");
        StoredResource stored = new StoredResource(
                "res-9", "OBJECT_STORAGE", "resources/v1/videos/2026/08/res-9.mov",
                "video/quicktime", "res-9.mov", 999L, null, null);
        GeneratedArtifact[] received = new GeneratedArtifact[1];
        when(coordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<Void> attachment = invocation.getArgument(1);
                    return attachment.attach(stored);
                });

        adapter.storeVideo("session-1", file, new ByteArrayInputStream(MP4_FTYP),
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

        adapter.storeVideo("session-1", file, new ByteArrayInputStream(MP4_FTYP), artifact -> null);

        // Adapter 只依赖 Coordinator 与配置；此断言的意义由上面的流式直传测试承担，
        // 这里再次确认命令为流式形态（拒绝方案 8：视频不得先落 /tmp）。
        ArgumentCaptor<ResourceSaveCommand> commandCaptor = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(coordinator).saveAndAttach(commandCaptor.capture(), any());
        assertNull(commandCaptor.getValue().content());
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] merged = new byte[first.length + second.length];
        System.arraycopy(first, 0, merged, 0, first.length);
        System.arraycopy(second, 0, merged, first.length, second.length);
        return merged;
    }
}
