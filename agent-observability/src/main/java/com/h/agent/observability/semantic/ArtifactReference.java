package com.h.agent.observability.semantic;

public record ArtifactReference(
        String resourceId,
        String sourceResourceId,
        ArtifactKind kind,
        ArtifactUse use,
        String businessRole,
        String mimeType,
        Long byteSize,
        Integer width,
        Integer height,
        String fileName,
        String applicationViewUrl
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String resourceId;
        private String sourceResourceId;
        private ArtifactKind kind;
        private ArtifactUse use;
        private String businessRole;
        private String mimeType;
        private Long byteSize;
        private Integer width;
        private Integer height;
        private String fileName;
        private String applicationViewUrl;

        public Builder resourceId(String resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public Builder sourceResourceId(String sourceResourceId) {
            this.sourceResourceId = sourceResourceId;
            return this;
        }

        public Builder kind(ArtifactKind kind) {
            this.kind = kind;
            return this;
        }

        public Builder use(ArtifactUse use) {
            this.use = use;
            return this;
        }

        public Builder businessRole(String businessRole) {
            this.businessRole = businessRole;
            return this;
        }

        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public Builder byteSize(Long byteSize) {
            this.byteSize = byteSize;
            return this;
        }

        public Builder width(Integer width) {
            this.width = width;
            return this;
        }

        public Builder height(Integer height) {
            this.height = height;
            return this;
        }

        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder applicationViewUrl(String applicationViewUrl) {
            this.applicationViewUrl = applicationViewUrl;
            return this;
        }

        public ArtifactReference build() {
            return new ArtifactReference(
                    resourceId,
                    sourceResourceId,
                    kind,
                    use,
                    businessRole,
                    mimeType,
                    byteSize,
                    width,
                    height,
                    fileName,
                    applicationViewUrl
            );
        }
    }
}
