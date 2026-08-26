package com.h.backend.chat.application;

import com.h.backend.chat.infrastructure.storage.ResourceContent;
import com.h.backend.chat.infrastructure.storage.ResourceRange;

public interface ChatResourceService {

    /**
     * 打开预览内容；Range 由 Controller 语法解析后传入，
     * 可满足性在存储层结合对象总大小判定。
     */
    ResourceResponse openPreview(Long userId, String resourceId, ResourceRange range);

    ResourceResponse openDownload(Long userId, String resourceId);

    record ResourceResponse(
            ResourceContent content,
            String fileName,
            boolean attachment
    ) {
    }
}
