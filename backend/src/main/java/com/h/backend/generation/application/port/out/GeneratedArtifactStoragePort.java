package com.h.backend.generation.application.port.out;

import com.h.backend.generation.domain.model.GeneratedArtifact;

import java.io.InputStream;

public interface GeneratedArtifactStoragePort {
    GeneratedArtifact storeVideo(String sessionId, ProviderFilePort.DownloadableFile file, InputStream inputStream);
}
