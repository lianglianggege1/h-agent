package com.h.backend.skill.domain;

public record ArtifactDescriptor(
        int schemaVersion,
        String mediaType,
        String digest,
        long size,
        String store,
        String objectKey,
        String objectVersionId
) {

    public static final String MEDIA_TYPE = "application/vnd.h-agent.skill.bundle.v1+tar";
    public static final String SYSTEM_STORE = "system-skill-artifacts";
    public static final String USER_STORE = "user-skill-artifacts";

    public static ArtifactDescriptor of(String digest, long size, String store, String objectKey, String objectVersionId) {
        return new ArtifactDescriptor(1, MEDIA_TYPE, digest, size, store, objectKey, objectVersionId);
    }

    public boolean isSystem() {
        return SYSTEM_STORE.equals(store);
    }
}
