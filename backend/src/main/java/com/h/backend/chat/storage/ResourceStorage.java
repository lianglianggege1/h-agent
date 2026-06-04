package com.h.backend.chat.storage;

public interface ResourceStorage {

    StoredResource save(ResourceSaveCommand command);

    ResourceContent open(String storageKey);

    String buildViewUrl(String resourceId);

    String buildDownloadUrl(String resourceId);
}
