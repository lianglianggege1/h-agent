package com.h.backend.generation.application.port.out;

import java.io.InputStream;

public interface ProviderFilePort {
    DownloadableFile retrieve(String providerFileId);
    InputStream openDownload(DownloadableFile file);

    record DownloadableFile(String fileId, String fileName, String mimeType, long size, String downloadUrl) {
    }
}
