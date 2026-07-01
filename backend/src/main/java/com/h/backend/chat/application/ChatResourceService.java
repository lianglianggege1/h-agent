package com.h.backend.chat.application;

import com.h.backend.chat.infrastructure.storage.ResourceContent;

public interface ChatResourceService {

    ResourceResponse openPreview(Long userId, String resourceId);

    ResourceResponse openDownload(Long userId, String resourceId);

    record ResourceResponse(
            ResourceContent content,
            String fileName,
            boolean attachment
    ) {
    }
}
