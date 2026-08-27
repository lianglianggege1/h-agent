package com.h.backend.generation.infrastructure.storage;

import com.h.backend.chat.application.ResourceContentPolicy;
import com.h.backend.chat.infrastructure.content.ResourceContentInspector;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceStorageErrorKind;
import com.h.backend.chat.infrastructure.storage.ResourceStorageException;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.generation.application.port.out.GeneratedArtifactAttachment;
import com.h.backend.generation.application.port.out.GeneratedArtifactStoragePort;
import com.h.backend.generation.application.port.out.ProviderFilePort;
import com.h.backend.generation.domain.model.GeneratedArtifact;
import com.h.backend.generation.infrastructure.config.GenerationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * 生成对象存储 Adapter（新计划任务 3）：写入统一经过
 * {@link ResourceWriteCoordinator}，provider stream 直接进入命令、
 * 不落本地中间文件（计划不变量 10、拒绝方案 8）；
 * 数据库挂接回调在 Coordinator 的挂接事务内执行，覆盖
 * generation_tasks 的 artifact type/key 持久化。
 *
 * <p>审查修复第 2 项：不信任 provider 元数据 size——MinIO SDK 的
 * known-size 行为下 objectSize=0 会静默上传空对象、size 偏小会静默截断，
 * 因此 storeVideo 使用 unknown-size 流式命令（declaredSize=null），
 * 存储层按实际字节计数，fileSize=实际值；业务上限沿用
 * {@code generation.download.max-file-size}。
 *
 * <p>审查修复第 3 项：provider 下载流经 {@link ResourceContentInspector}
 * 签名校验（计划 §6.3：MP4 必须校验基础文件签名，provider MIME 只是提示），
 * 校验通过后的回放流（头字节 + 剩余原流）传给保存命令——单次消费链路，
 * 不二次读源流。
 */
@Component
public class ResourceStorageGeneratedArtifactAdapter implements GeneratedArtifactStoragePort {

    private final ResourceWriteCoordinator writeCoordinator;
    private final GenerationProperties properties;
    private final ResourceContentInspector contentInspector;
    private final ResourceContentPolicy contentPolicy;

    public ResourceStorageGeneratedArtifactAdapter(
            ResourceWriteCoordinator writeCoordinator,
            GenerationProperties properties,
            ResourceContentInspector contentInspector,
            ResourceContentPolicy contentPolicy
    ) {
        this.writeCoordinator = writeCoordinator;
        this.properties = properties;
        this.contentInspector = contentInspector;
        this.contentPolicy = contentPolicy;
    }

    @Override
    public <T> T storeVideo(
            String sessionId,
            ProviderFilePort.DownloadableFile file,
            InputStream inputStream,
            GeneratedArtifactAttachment<T> attachment
    ) {
        ResourceContentInspector.Inspection inspection;
        try {
            inspection = contentInspector.inspect(inputStream, file.mimeType());
        } catch (IOException exception) {
            throw new ResourceStorageException(
                    ResourceStorageErrorKind.IO_ERROR, "生成视频读取失败", exception);
        }
        ResourceContentPolicy.SaveDecision decision =
                contentPolicy.validateForSave(inspection.result(), file.mimeType());
        if (!decision.allowed()) {
            closeQuietly(inspection.replayStream());
            throw new ResourceStorageException(
                    ResourceStorageErrorKind.IO_ERROR, "生成视频未通过内容校验，已拒绝保存");
        }
        // unknown-size 流式命令：declaredSize=null，实际大小由存储层计数确定。
        return writeCoordinator.saveAndAttach(
                ResourceSaveCommand.fromStream(
                        "VIDEO",
                        inspection.replayStream(),
                        file.mimeType(),
                        extension(file.fileName()),
                        properties.getDownload().getMaxFileSize()
                ),
                stored -> attachment.attach(toArtifact(stored))
        );
    }

    private GeneratedArtifact toArtifact(StoredResource resource) {
        return new GeneratedArtifact(
                resource.id(), resource.storageType(), resource.storageKey(), resource.mimeType(),
                resource.fileName(), resource.fileSize()
        );
    }

    private String extension(String fileName) {
        int index = fileName == null ? -1 : fileName.lastIndexOf('.');
        return index < 1 ? "mp4" : fileName.substring(index + 1);
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // 关闭失败不覆盖原始拒绝原因
        }
    }
}
