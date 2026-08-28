package com.h.backend.skill.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "skill-platform")
public class SkillPlatformProperties {

    private final Repository repository = new Repository();
    private final Artifacts artifacts = new Artifacts();
    private final Validation validation = new Validation();
    private final Cache cache = new Cache();
    private final Runtime runtime = new Runtime();
    private final List<SystemSkill> systemSkills = new ArrayList<>();

    public Repository getRepository() {
        return repository;
    }

    public Artifacts getArtifacts() {
        return artifacts;
    }

    public Validation getValidation() {
        return validation;
    }

    public Cache getCache() {
        return cache;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public List<SystemSkill> getSystemSkills() {
        return systemSkills;
    }

    public static class Repository {
        private String provider = "gitee";
        private String cloneUrl = "https://gitee.com/huajiangliangliang/hj-skill-repo.git";
        private String branch = "master";
        private String credentialEnv = "GITEE_TOKEN";
        private String apiBaseUrl = "https://gitee.com/api/v5";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getCloneUrl() {
            return cloneUrl;
        }

        public void setCloneUrl(String cloneUrl) {
            this.cloneUrl = cloneUrl;
        }

        public String getBranch() {
            return branch;
        }

        public void setBranch(String branch) {
            this.branch = branch;
        }

        public String getCredentialEnv() {
            return credentialEnv;
        }

        public void setCredentialEnv(String credentialEnv) {
            this.credentialEnv = credentialEnv;
        }

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        public String owner() {
            String url = cloneUrl.endsWith(".git") ? cloneUrl.substring(0, cloneUrl.length() - 4) : cloneUrl;
            int schemes = url.indexOf("://");
            String path = schemes > 0 ? url.substring(schemes + 3) : url;
            String[] parts = path.split("/");
            if (parts.length < 2 || parts[parts.length - 2].isBlank() || parts[parts.length - 1].isBlank()) {
                throw new IllegalStateException("skill-platform.repository.clone-url 不是合法仓库地址");
            }
            return parts[parts.length - 2];
        }

        public String repo() {
            String url = cloneUrl.endsWith(".git") ? cloneUrl.substring(0, cloneUrl.length() - 4) : cloneUrl;
            int schemes = url.indexOf("://");
            String path = schemes > 0 ? url.substring(schemes + 3) : url;
            String[] parts = path.split("/");
            return parts[parts.length - 1];
        }
    }

    public static class Artifacts {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "";
        private String secretKey = "";
        private String region = "us-east-1";
        private String systemBucket = "h-agent-skills";
        private String userBucket = "h-agent-skills";
        private String systemObjectPrefix = "system/";
        private String userObjectPrefix = "user/";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(60);

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getSystemBucket() {
            return systemBucket;
        }

        public void setSystemBucket(String systemBucket) {
            this.systemBucket = systemBucket;
        }

        public String getUserBucket() {
            return userBucket;
        }

        public void setUserBucket(String userBucket) {
            this.userBucket = userBucket;
        }

        public String getSystemObjectPrefix() {
            return systemObjectPrefix;
        }

        public void setSystemObjectPrefix(String systemObjectPrefix) {
            this.systemObjectPrefix = systemObjectPrefix;
        }

        public String getUserObjectPrefix() {
            return userObjectPrefix;
        }

        public void setUserObjectPrefix(String userObjectPrefix) {
            this.userObjectPrefix = userObjectPrefix;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class Validation {
        private int maxUserSkills = 20;
        private long maxFileBytes = 1048576;
        private long maxTotalBytes = 10485760;
        private int maxFiles = 200;
        private int maxDepth = 8;

        public int getMaxUserSkills() {
            return maxUserSkills;
        }

        public void setMaxUserSkills(int maxUserSkills) {
            this.maxUserSkills = maxUserSkills;
        }

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }

        public long getMaxTotalBytes() {
            return maxTotalBytes;
        }

        public void setMaxTotalBytes(long maxTotalBytes) {
            this.maxTotalBytes = maxTotalBytes;
        }

        public int getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(int maxFiles) {
            this.maxFiles = maxFiles;
        }

        public int getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
        }
    }

    public static class Cache {
        private String directory = "/tmp/h-agent/skill-artifacts";
        private long maxBytes = 524288000;
        private Duration snapshotTtl = Duration.ofMinutes(5);

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public Duration getSnapshotTtl() {
            return snapshotTtl;
        }

        public void setSnapshotTtl(Duration snapshotTtl) {
            this.snapshotTtl = snapshotTtl;
        }
    }

    public static class Runtime {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class SystemSkill {
        private String key;
        private String displayName;
        private String revision;
        private boolean enabled = true;
        private final Artifact artifact = new Artifact();

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getRevision() {
            return revision;
        }

        public void setRevision(String revision) {
            this.revision = revision;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Artifact getArtifact() {
            return artifact;
        }
    }

    public static class Artifact {
        private int schemaVersion = 1;
        private String mediaType = "application/vnd.h-agent.skill.bundle.v1+tar";
        private String digest;
        private long size;
        private String store = "system-skill-artifacts";
        private String objectKey;
        private String objectVersionId;

        public int getSchemaVersion() {
            return schemaVersion;
        }

        public void setSchemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
        }

        public String getMediaType() {
            return mediaType;
        }

        public void setMediaType(String mediaType) {
            this.mediaType = mediaType;
        }

        public String getDigest() {
            return digest;
        }

        public void setDigest(String digest) {
            this.digest = digest;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store;
        }

        public String getObjectKey() {
            return objectKey;
        }

        public void setObjectKey(String objectKey) {
            this.objectKey = objectKey;
        }

        public String getObjectVersionId() {
            return objectVersionId;
        }

        public void setObjectVersionId(String objectVersionId) {
            this.objectVersionId = objectVersionId;
        }
    }
}
