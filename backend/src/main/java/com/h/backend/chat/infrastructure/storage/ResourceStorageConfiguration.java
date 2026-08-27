package com.h.backend.chat.infrastructure.storage;

import io.minio.MinioClient;
import io.minio.ObjectWriteArgs;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.URI;

/**
 * 资源存储装配（计划 §8.1 / §10 任务 2）。
 *
 * <p>注册 {@link MinioResourceStorage} 为<b>唯一生产</b> {@link ResourceStorage} Bean
 * （历史本地文件存储实现及其资源配置已在任务 5 删除，
 * 生产运行态无本地回退、双写或双读——计划不变量 6/7）。
 *
 * <p>启动校验是纯配置校验：<b>不联网、不 bucketExists、不写探针对象</b>（计划 §8.1）。
 * 必填缺失或格式非法立即以 {@link IllegalStateException} fail fast；异常消息只含
 * 属性名与格式要求，绝不包含属性值（尤其 secret）。
 *
 * <p>{@link MinioClient.Builder} 无超时设置方法（SDK 9.0.1 已核实），
 * 超时通过自建 {@link OkHttpClient} 传入。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResourceStorageProperties.class)
public class ResourceStorageConfiguration {

    @Bean
    public MinioClient minioClient(ResourceStorageProperties properties) {
        validate(properties);

        ResourceStorageProperties.Minio minio = properties.getMinio();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(minio.getConnectTimeout())
                .readTimeout(minio.getReadTimeout())
                .build();
        return MinioClient.builder()
                .endpoint(minio.getEndpoint().strip())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .region(minio.getRegion())
                .httpClient(httpClient)
                .build();
    }

    @Bean
    public MinioResourceStorage minioResourceStorage(
            MinioClient minioClient, ResourceStorageProperties properties) {
        return new MinioResourceStorage(minioClient, properties);
    }

    // ------------------------------------------------------------------
    // 纯配置校验（不联网）：缺失或非法 → IllegalStateException fail fast
    // ------------------------------------------------------------------

    private void validate(ResourceStorageProperties properties) {
        ResourceStorageProperties.Minio minio = properties.getMinio();

        requireHttpUrl(minio.getEndpoint(), "resource-storage.minio.endpoint");
        requireText(minio.getAccessKey(), "resource-storage.minio.access-key");
        requireText(minio.getSecretKey(), "resource-storage.minio.secret-key");
        requireText(minio.getBucket(), "resource-storage.minio.bucket");
        requireObjectPrefix(minio.getObjectPrefix(), "resource-storage.minio.object-prefix");
        requirePositiveDuration(minio.getConnectTimeout(), "resource-storage.minio.connect-timeout");
        requirePositiveDuration(minio.getReadTimeout(), "resource-storage.minio.read-timeout");
        requirePartSize(minio.getPartSizeBytes(), "resource-storage.minio.part-size-bytes");
        if (properties.getAbsoluteMaxBytes() <= 0) {
            throw new IllegalStateException("resource-storage.absolute-max-bytes 必须是正数");
        }
    }

    private static void requireText(String value, String propertyName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(propertyName + " 不能为空");
        }
    }

    private static void requireHttpUrl(String value, String propertyName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(propertyName + " 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(value.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(propertyName + " 必须是合法的 http/https URL");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException(propertyName + " 必须是合法的 http/https URL");
        }
    }

    private static void requireObjectPrefix(String value, String propertyName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(propertyName + " 不能为空");
        }
        if (value.strip().startsWith("/")) {
            throw new IllegalStateException(propertyName + " 不能以 / 开头（尾部 / 会自动规范）");
        }
    }

    private static void requirePositiveDuration(java.time.Duration value, String propertyName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(propertyName + " 必须是正的时长");
        }
    }

    /**
     * 分片大小合法区间 [5 MiB, 5 GiB] 是 SDK 硬约束
     * （{@link ObjectWriteArgs#MIN_MULTIPART_SIZE} / {@link ObjectWriteArgs#MAX_PART_SIZE}）：
     * 对象大小未知时按此分片上传，越界值会导致运行时写入失败，故启动期 fail fast。
     */
    private static void requirePartSize(long value, String propertyName) {
        if (value < ObjectWriteArgs.MIN_MULTIPART_SIZE || value > ObjectWriteArgs.MAX_PART_SIZE) {
            throw new IllegalStateException(propertyName + " 必须介于 5MiB 与 5GiB 之间");
        }
    }
}
