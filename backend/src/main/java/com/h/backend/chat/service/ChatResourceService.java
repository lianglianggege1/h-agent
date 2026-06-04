package com.h.backend.chat.service;

import com.h.backend.chat.storage.ResourceContent;

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
