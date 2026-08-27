package com.h.backend.chat.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 资源存储配置（计划 §8.1 精确结构）。
 *
 * <p>不提供 enabled / publicEndpoint / presign / orphan 等字段（计划 §8.1 明确不提供）：
 * MinIO Adapter 是唯一生产实现，没有开关、没有预签名 URL、没有迁移跟踪配置。
 *
 * <p>所有必填项由 {@code ResourceStorageConfiguration} 在启动期做纯配置校验
 * （不联网、不 bucketExists、不写探针对象），非法即 fail fast。
 */
@ConfigurationProperties(prefix = "resource-storage")
public class ResourceStorageProperties {

    /**
     * 配置化绝对上限（字节），默认 500 MiB。
     *
     * <p>与 {@link ResourceSaveCommand#ABSOLUTE_MAX_BYTES} 的协调语义：
     * 生效上限恒为 {@code min(本属性, 命令侧 effectiveMaxBytes())}——
     * 本属性小于默认 500 MiB 时收紧实际上限；大于时<b>不放大</b>命令侧
     * 的绝对上限（调用方无法通过配置突破 {@code ABSOLUTE_MAX_BYTES}）。
     * 必须为正数。
     */
    private long absoluteMaxBytes = ResourceSaveCommand.ABSOLUTE_MAX_BYTES;

    private final Minio minio = new Minio();

    public long getAbsoluteMaxBytes() {
        return absoluteMaxBytes;
    }

    public void setAbsoluteMaxBytes(long absoluteMaxBytes) {
        this.absoluteMaxBytes = absoluteMaxBytes;
    }

    public Minio getMinio() {
        return minio;
    }

    /** MinIO 连接与对象命名配置（计划 §8.1）。 */
    public static class Minio {

        /** S3 兼容 endpoint，必须是合法 http/https URL，例如 {@code http://127.0.0.1:9000}。 */
        private String endpoint;

        /** 访问凭证 access key。 */
        private String accessKey;

        /** 访问凭证 secret key（只用于构建客户端，绝不进入日志与异常消息）。 */
        private String secretKey;

        /** 区域，默认 us-east-1。 */
        private String region = "us-east-1";

        /** 目标 bucket 名。 */
        private String bucket;

        /** object key 前缀，默认 resources/；不以 / 开头，尾部 / 自动规范。 */
        private String objectPrefix = "resources/";

        /** 连接超时，默认 3 秒。 */
        private Duration connectTimeout = Duration.ofSeconds(3);

        /** 读超时，默认 60 秒。 */
        private Duration readTimeout = Duration.ofSeconds(60);

        /**
         * 分片大小（字节），默认 10 MiB；对象大小未知时 SDK 按此走 multipart，
         * 合法区间为 [5 MiB, 5 GiB]（SDK 硬约束）。
         */
        private long partSizeBytes = 10_485_760L;

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

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getObjectPrefix() {
            return objectPrefix;
        }

        public void setObjectPrefix(String objectPrefix) {
            this.objectPrefix = objectPrefix;
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

        public long getPartSizeBytes() {
            return partSizeBytes;
        }

        public void setPartSizeBytes(long partSizeBytes) {
            this.partSizeBytes = partSizeBytes;
        }

        /**
         * 规范化 object 前缀：剥掉多余尾部斜杠后恰好补一个 {@code /}
         * （例如 {@code custom-prefix} 与 {@code custom-prefix//} 都得到
         * {@code custom-prefix/}）。不以 {@code /} 开头由启动校验保证。
         */
        public String normalizedObjectPrefix() {
            String trimmed = objectPrefix == null ? "" : objectPrefix.strip();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed + "/";
        }
    }
}
