package com.h.backend.skill.infrastructure.config;

import com.h.backend.skill.infrastructure.artifact.MinioSkillArtifactStore;
import com.h.backend.skill.infrastructure.artifact.SkillArtifactCache;
import com.h.backend.skill.domain.tar.SkillTarReader;
import com.h.backend.skill.domain.tar.DeterministicSkillTarBuilder;
import com.h.backend.skill.infrastructure.validation.SkillContentValidator;
import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.file.Path;

/**
 * Skill 平台装配（设计 §8/§10）：独立的 MinIO Client（命名 Bean，不复用
 * resources 账号连接）、digest 缓存、确定性 builder/reader 与制品深模块。
 *
 * <p>启动校验沿用资源存储的纯配置校验纪律：不联网、不探测 Bucket，
 * 必填缺失或非法立即 fail fast，异常消息不含属性值。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkillPlatformProperties.class)
public class SkillPlatformConfig {

    @Bean
    public DeterministicSkillTarBuilder deterministicSkillTarBuilder(ObjectMapper objectMapper) {
        return new DeterministicSkillTarBuilder(objectMapper);
    }

    @Bean
    public SkillTarReader skillTarReader(ObjectMapper objectMapper) {
        return new SkillTarReader(objectMapper);
    }

    @Bean
    public SkillContentValidator skillContentValidator(SkillPlatformProperties properties) {
        SkillPlatformProperties.Validation validation = properties.getValidation();
        return new SkillContentValidator(new SkillContentValidator.Quotas(
                validation.getMaxUserSkills(),
                validation.getMaxFileBytes(),
                validation.getMaxTotalBytes(),
                validation.getMaxFiles(),
                validation.getMaxDepth()));
    }

    @Bean
    public SkillArtifactCache skillArtifactCache(SkillPlatformProperties properties) {
        SkillPlatformProperties.Cache cache = properties.getCache();
        if (!StringUtils.hasText(cache.getDirectory())) {
            throw new IllegalStateException("skill-platform.cache.directory 不能为空");
        }
        if (cache.getMaxBytes() <= 0) {
            throw new IllegalStateException("skill-platform.cache.max-bytes 必须是正数");
        }
        return new SkillArtifactCache(Path.of(cache.getDirectory()).toAbsolutePath().normalize(),
                cache.getMaxBytes());
    }

    @Bean
    public MinioClient skillArtifactMinioClient(SkillPlatformProperties properties) {
        SkillPlatformProperties.Artifacts artifacts = properties.getArtifacts();
        validate(artifacts);
        return MinioClient.builder()
                .endpoint(artifacts.getEndpoint().strip())
                .credentials(artifacts.getAccessKey(), artifacts.getSecretKey())
                .region(artifacts.getRegion())
                .httpClient(new OkHttpClient.Builder()
                        .connectTimeout(artifacts.getConnectTimeout())
                        .readTimeout(artifacts.getReadTimeout())
                        .writeTimeout(artifacts.getReadTimeout())
                        .build())
                .build();
    }

    @Bean
    public MinioSkillArtifactStore minioSkillArtifactStore(
            @Qualifier("skillArtifactMinioClient") MinioClient minioClient,
            SkillPlatformProperties properties,
            SkillTarReader skillTarReader,
            SkillArtifactCache skillArtifactCache
    ) {
        return new MinioSkillArtifactStore(minioClient, properties.getArtifacts(),
                skillTarReader, skillArtifactCache);
    }

    private void validate(SkillPlatformProperties.Artifacts artifacts) {
        if (!StringUtils.hasText(artifacts.getEndpoint())) {
            throw new IllegalStateException("skill-platform.artifacts.endpoint 不能为空（MINIO_ENDPOINT）");
        }
        URI uri;
        try {
            uri = URI.create(artifacts.getEndpoint().strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("skill-platform.artifacts.endpoint 必须是合法 http/https URL");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("skill-platform.artifacts.endpoint 必须是合法 http/https URL");
        }
        if (!StringUtils.hasText(artifacts.getAccessKey())) {
            throw new IllegalStateException("skill-platform.artifacts.access-key 不能为空（MINIO_ACCESS_KEY）");
        }
        if (!StringUtils.hasText(artifacts.getSecretKey())) {
            throw new IllegalStateException("skill-platform.artifacts.secret-key 不能为空（MINIO_SECRET_KEY）");
        }
        if (!StringUtils.hasText(artifacts.getSystemBucket())
                || !StringUtils.hasText(artifacts.getUserBucket())) {
            throw new IllegalStateException(
                    "skill-platform.artifacts.system-bucket/user-bucket 不能为空（MINIO_SKILLS_BUCKET）");
        }
        if (artifacts.getConnectTimeout() == null || artifacts.getConnectTimeout().isZero()
                || artifacts.getConnectTimeout().isNegative()) {
            throw new IllegalStateException("skill-platform.artifacts.connect-timeout 必须是正的时长");
        }
        if (artifacts.getReadTimeout() == null || artifacts.getReadTimeout().isZero()
                || artifacts.getReadTimeout().isNegative()) {
            throw new IllegalStateException("skill-platform.artifacts.read-timeout 必须是正的时长");
        }
    }
}
