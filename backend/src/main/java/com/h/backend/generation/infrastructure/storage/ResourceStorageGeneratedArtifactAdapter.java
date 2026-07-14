package com.h.backend.generation.infrastructure.storage;

import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.generation.application.port.out.GeneratedArtifactStoragePort;
import com.h.backend.generation.application.port.out.ProviderFilePort;
import com.h.backend.generation.domain.model.GeneratedArtifact;
import com.h.backend.generation.infrastructure.config.GenerationProperties;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class ResourceStorageGeneratedArtifactAdapter implements GeneratedArtifactStoragePort {
    private final ResourceStorage resourceStorage;
    private final GenerationProperties properties;

    public ResourceStorageGeneratedArtifactAdapter(ResourceStorage resourceStorage, GenerationProperties properties) {
        this.resourceStorage = resourceStorage;
        this.properties = properties;
    }

    @Override
    public GeneratedArtifact storeVideo(String sessionId, ProviderFilePort.DownloadableFile file, InputStream inputStream) {
        StoredResource resource = resourceStorage.save(ResourceSaveCommand.fromStream(
                "VIDEO", sessionId, null, inputStream, file.mimeType(), extension(file.fileName()),
                properties.getDownload().getMaxFileSize()
        ));
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
