package com.h.backend.skill.infrastructure.artifact;

import com.h.backend.skill.domain.ArtifactDescriptor;
import com.h.backend.skill.domain.SkillPlatformErrorKind;
import com.h.backend.skill.domain.SkillPlatformException;
import com.h.backend.skill.domain.tar.SkillTarReader;
import com.h.backend.skill.domain.tar.DeterministicSkillTarBuilder;
import com.h.backend.skill.infrastructure.config.SkillPlatformProperties;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.ServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * MinIO 实现的 Skill 制品发布器 + 解析器（设计 §10.1–§10.4）。
 *
 * <p>发布：内容寻址 key（{@code {prefix}blobs/sha256/<前2位>/<digest>.skill.tar}
 * 或 {@code {prefix}users/<owner>/blobs/...}），先 HEAD 探测同 key 对象：
 * 存在且 GET 验证字节一致则幂等复用；否则 PUT 后读回验证 size 与 SHA-256。
 * {@code objectVersionId} 仅在服务端开启 Versioning 时有值，作为恢复/取证字段。
 *
 * <p>解析：优先本地 digest 缓存；miss 时 GET 流式校验 size + digest + media type。
 * 校验失败抛 ARTIFACT_CORRUPT，MinIO 故障抛 ARTIFACT_UNAVAILABLE。
 *
 * <p>日志纪律（设计 §19）：不输出 endpoint、完整 object key、凭据与 Skill 正文；
 * 定位只用截断 digest。
 */
public class MinioSkillArtifactStore implements SkillArtifactPublisher, SkillArtifactResolver {

    private static final Logger log = LoggerFactory.getLogger(MinioSkillArtifactStore.class);

    private static final String FILE_EXTENSION = ".skill.tar";

    /** 校验 read 流大小上限防御（发布侧 bundle 已受配额约束，这里只兜底）。 */
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024;

    private final MinioClient minioClient;
    private final SkillPlatformProperties.Artifacts artifacts;
    private final SkillTarReader tarReader;
    private final SkillArtifactCache cache;

    public MinioSkillArtifactStore(
            MinioClient minioClient,
            SkillPlatformProperties.Artifacts artifacts,
            SkillTarReader tarReader,
            SkillArtifactCache cache
    ) {
        this.minioClient = Objects.requireNonNull(minioClient, "minioClient must not be null");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
        this.tarReader = Objects.requireNonNull(tarReader, "tarReader must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
    }

    // ------------------------------------------------------------------
    // 发布（User namespace only）
    // ------------------------------------------------------------------

    @Override
    public ArtifactDescriptor storeVerifiedUserBundle(long ownerUserId, byte[] bundle) {
        Objects.requireNonNull(bundle, "bundle must not be null");
        if (bundle.length == 0) {
            throw new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_CORRUPT, "Skill bundle 为空");
        }
        String digestHex = DeterministicSkillTarBuilder.sha256Hex(bundle);
        String digest = "sha256:" + digestHex;
        String objectKey = userObjectKey(ownerUserId, digestHex);
        String bucket = artifacts.getUserBucket();

        String existingVersionId = tryReuseExisting(bucket, objectKey, digest, bundle.length);
        if (existingVersionId != null) {
            log.info("Skill artifact 幂等复用 owner={} digest={}", ownerUserId, shortDigest(digest));
            return ArtifactDescriptor.of(digest, bundle.length, ArtifactDescriptor.USER_STORE,
                    objectKey, existingVersionId);
        }

        try {
            var response = minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .contentType(ArtifactDescriptor.MEDIA_TYPE)
                    .userMetadata(Map.of(
                            "schema-version", "1",
                            "content-digest", digest))
                    .stream(new ByteArrayInputStream(bundle), (long) bundle.length, -1L)
                    .build());
            String versionId = response.versionId();
            verifyStoredObject(bucket, objectKey, versionId, digest, bundle.length);
            log.info("Skill artifact 已存储并验证 owner={} digest={}", ownerUserId, shortDigest(digest));
            return ArtifactDescriptor.of(digest, bundle.length, ArtifactDescriptor.USER_STORE,
                    objectKey, versionId);
        } catch (SkillPlatformException ex) {
            throw ex;
        } catch (Exception ex) {
            throw toPlatformException(ex, "Skill artifact 上传失败");
        }
    }

    /**
     * 同 key 对象已存在时验证实际字节一致才复用；不存在或验证失败返回 null。
     * 设计 §10.2：不因 key 相同就盲目信任。
     */
    private String tryReuseExisting(String bucket, String objectKey, String digest, long size) {
        try {
            if (!objectExists(bucket, objectKey)) {
                return null;
            }
            Downloaded download = downloadObject(bucket, objectKey, null);
            if (download.size() == size && digest.equals(download.digest())) {
                return download.versionId();
            }
            log.warn("Skill artifact 同 key 内容不一致，重新上传 digest={}", shortDigest(digest));
            return null;
        } catch (SkillPlatformException ex) {
            log.warn("Skill artifact 复用探测失败，按新写入处理 digest={}", shortDigest(digest));
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    @Override
    public VerifiedSkillBundle openVerified(ArtifactDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        byte[] bytes = loadVerifiedBytes(descriptor);
        SkillTarReader.ParsedBundle parsed;
        try {
            parsed = tarReader.parse(bytes);
        } catch (RuntimeException ex) {
            throw new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_CORRUPT,
                    "Skill bundle 解析失败", ex);
        }
        return new VerifiedSkillBundle(descriptor, parsed.files(), parsed.manifest());
    }

    @Override
    public void verifyAvailable(ArtifactDescriptor descriptor) {
        loadVerifiedBytes(descriptor);
    }

    /** 缓存优先的字节加载：命中核对 size；miss 回源 MinIO 流式校验 digest。 */
    private byte[] loadVerifiedBytes(ArtifactDescriptor descriptor) {
        if (!ArtifactDescriptor.MEDIA_TYPE.equals(descriptor.mediaType())) {
            throw new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_CORRUPT,
                    "Skill artifact media type 不受支持");
        }
        byte[] cached = cache.readIfValid(descriptor.digest(), descriptor.size());
        if (cached != null) {
            return cached;
        }
        String bucket = bucketFor(descriptor);
        String versionId = descriptor.objectVersionId();
        try {
            Downloaded download = downloadObject(bucket, descriptor.objectKey(), versionId);
            if (download.size() != descriptor.size()
                    || !download.digest().equals(descriptor.digest())) {
                throw new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_CORRUPT,
                        "Skill artifact 读回校验失败");
            }
            cache.store(descriptor.digest(), download.content());
            return download.content();
        } catch (SkillPlatformException ex) {
            throw ex;
        } catch (Exception ex) {
            throw toPlatformException(ex, "Skill artifact 读取失败");
        }
    }

    // ------------------------------------------------------------------
    // MinIO 原语
    // ------------------------------------------------------------------

    private boolean objectExists(String bucket, String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return true;
        } catch (ErrorResponseException ex) {
            if ("NoSuchKey".equals(ex.errorResponse().code())) {
                return false;
            }
            throw toPlatformException(ex, "Skill artifact 状态查询失败");
        } catch (Exception ex) {
            throw toPlatformException(ex, "Skill artifact 状态查询失败");
        }
    }

    private void verifyStoredObject(String bucket, String objectKey, String versionId,
                                    String digest, long size) {
        Downloaded download = downloadObject(bucket, objectKey, versionId);
        if (download.size() != size || !download.digest().equals(digest)) {
            throw new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_CORRUPT,
                    "Skill artifact 上传后读回校验失败");
        }
        cache.store(digest, download.content());
    }

    /** GET 完整对象，边读边算 SHA-256；超过上限防御性中止。 */
    private Downloaded downloadObject(String bucket, String objectKey, String versionId) {
        try (GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .versionId(versionId)
                .build())) {
            LimitingDigestInputStream in = new LimitingDigestInputStream(response, MAX_DOWNLOAD_BYTES);
            byte[] content = in.readAllBytes();
            return new Downloaded(content, "sha256:" + in.digestHex(), versionIdOf(response));
        } catch (SkillPlatformException ex) {
            throw ex;
        } catch (ErrorResponseException ex) {
            if ("NoSuchKey".equals(ex.errorResponse().code())) {
                throw new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_UNAVAILABLE,
                        "Skill artifact 不存在");
            }
            throw toPlatformException(ex, "Skill artifact 读取失败");
        } catch (Exception ex) {
            throw toPlatformException(ex, "Skill artifact 读取失败");
        }
    }

    private static String versionIdOf(GetObjectResponse response) {
        try {
            String header = response.headers().get("x-amz-version-id");
            return (header == null || header.isBlank()) ? null : header;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String bucketFor(ArtifactDescriptor descriptor) {
        if (ArtifactDescriptor.SYSTEM_STORE.equals(descriptor.store())) {
            return artifacts.getSystemBucket();
        }
        if (ArtifactDescriptor.USER_STORE.equals(descriptor.store())) {
            return artifacts.getUserBucket();
        }
        throw new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_CORRUPT,
                "Skill artifact store 未知: " + descriptor.store());
    }

    // ------------------------------------------------------------------
    // object key（设计 §10.1）
    // ------------------------------------------------------------------

    String userObjectKey(long ownerUserId, String digestHex) {
        return normalizePrefix(artifacts.getUserObjectPrefix())
                + "v1/users/" + ownerUserId + "/blobs/sha256/"
                + digestHex.substring(0, 2) + "/" + digestHex + FILE_EXTENSION;
    }

    String systemObjectKey(String digestHex) {
        return normalizePrefix(artifacts.getSystemObjectPrefix())
                + "v1/blobs/sha256/" + digestHex.substring(0, 2) + "/" + digestHex + FILE_EXTENSION;
    }

    private static String normalizePrefix(String prefix) {
        String cleaned = prefix == null ? "" : prefix.strip();
        if (cleaned.startsWith("/") || cleaned.contains("//")) {
            throw new IllegalStateException("skill-platform.artifacts object prefix 非法");
        }
        if (cleaned.isEmpty() || cleaned.endsWith("/")) {
            return cleaned;
        }
        return cleaned + "/";
    }

    // ------------------------------------------------------------------
    // 异常映射与日志脱敏
    // ------------------------------------------------------------------

    private SkillPlatformException toPlatformException(Throwable failure, String safeMessage) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ErrorResponseException errorResponse) {
                String code = errorResponse.errorResponse().code();
                if ("AccessDenied".equals(code) || "InvalidAccessKeyId".equals(code)
                        || "SignatureDoesNotMatch".equals(code)) {
                    return new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_UNAVAILABLE,
                            "Skill artifact 存储暂不可用", failure);
                }
                var raw = errorResponse.response();
                if (raw != null && raw.code() >= 500) {
                    return new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_UNAVAILABLE,
                            "Skill artifact 存储暂不可用", failure);
                }
                return new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_CORRUPT,
                        safeMessage, failure);
            }
            if (current instanceof ServerException serverException) {
                if (serverException.statusCode() >= 500) {
                    return new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_UNAVAILABLE,
                            "Skill artifact 存储暂不可用", failure);
                }
                return new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_CORRUPT,
                        safeMessage, failure);
            }
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException) {
                return new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_UNAVAILABLE,
                        "Skill artifact 存储暂不可用", failure);
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_UNAVAILABLE,
                safeMessage, failure);
    }

    static String shortDigest(String digest) {
        String hex = digest.startsWith("sha256:") ? digest.substring(7) : digest;
        return hex.length() <= 12 ? hex : hex.substring(0, 12);
    }

    private record Downloaded(byte[] content, String digest, String versionId) {
        long size() {
            return content.length;
        }
    }

    /** 读入时流式计算 SHA-256 并限制总字节数，超限立即中止。 */
    private static final class LimitingDigestInputStream extends FilterInputStream {

        private final MessageDigest digest;
        private final long maxBytes;
        private long transferred;

        LimitingDigestInputStream(InputStream delegate, long maxBytes) {
            super(delegate);
            this.maxBytes = maxBytes;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 不可用", ex);
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                digest.update((byte) value);
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                digest.update(buffer, offset, read);
                count(read);
            }
            return read;
        }

        private void count(long bytes) throws IOException {
            transferred += bytes;
            if (transferred > maxBytes) {
                throw new IOException("Skill artifact 超过读取上限");
            }
        }

        String digestHex() {
            return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        }
    }
}
