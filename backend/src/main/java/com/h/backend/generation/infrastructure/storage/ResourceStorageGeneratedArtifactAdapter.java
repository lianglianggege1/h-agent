package com.h.backend.generation.infrastructure.storage;

import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.generation.application.port.out.GeneratedArtifactAttachment;
import com.h.backend.generation.application.port.out.GeneratedArtifactStoragePort;
import com.h.backend.generation.application.port.out.ProviderFilePort;
import com.h.backend.generation.domain.model.GeneratedArtifact;
import com.h.backend.generation.infrastructure.config.GenerationProperties;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * 生成对象存储 Adapter（新计划任务 3）：写入统一经过
 * {@link ResourceWriteCoordinator}，provider stream 直接进入命令、
 * 不落本地中间文件（计划不变量 10、拒绝方案 8）；
 * 数据库挂接回调在 Coordinator 的挂接事务内执行，覆盖
 * generation_tasks 的 artifact type/key 持久化。
 */
@Component
public class ResourceStorageGeneratedArtifactAdapter implements GeneratedArtifactStoragePort {

    private final ResourceWriteCoordinator writeCoordinator;
    private final GenerationProperties properties;

    public ResourceStorageGeneratedArtifactAdapter(
            ResourceWriteCoordinator writeCoordinator,
            GenerationProperties properties
    ) {
        this.writeCoordinator = writeCoordinator;
        this.properties = properties;
    }

    @Override
    public <T> T storeVideo(
            String sessionId,
            ProviderFilePort.DownloadableFile file,
            InputStream inputStream,
            GeneratedArtifactAttachment<T> attachment
    ) {
        return writeCoordinator.saveAndAttach(
                ResourceSaveCommand.fromStream(
                        "VIDEO",
                        inputStream,
                        file.size(),
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
}
