package com.h.backend.chat.infrastructure.storage;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.ServerException;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 唯一生产 {@link ResourceStorage} Adapter：MinIO 对象存储实现（计划 §5/§6/§10 任务 2）。
 *
 * <p>object key：{@code {objectPrefix}v1/{segment}/{yyyy}/{MM}/{resourceId}.{ext}}
 * （计划 §5.2）；resourceType 到 key 段的映射：IMAGE→images、VIDEO→videos、
 * AUDIO→audio、FILE/DOCUMENT→files（以计划 §5.2 示例段名为准，未识别类型归并 files）。
 *
 * <p>object metadata 只写 Content-Type、{@code x-amz-meta-schema-version=1}、
 * {@code x-amz-meta-created-by=h-agent}，不写 SHA-256（计划不变量 13）；
 * 不使用 ImageIO，width/height 恒为 null（计划 §6.2）。
 *
 * <p>异常映射（计划 §4.5）：业务侧只见 {@link ResourceStorageException}——
 * NoSuchKey→NOT_FOUND；AccessDenied/凭证类、超时/连接失败、5xx→UNAVAILABLE；
 * 其余 SDK/IO 异常→IO_ERROR。消息为固定安全文案；原始异常保留在 cause 链。
 * {@link ResourceRangeException}（416 语义）原样上抛，交由 Controller 层处理。
 *
 * <p>日志纪律（计划不变量 17）：不输出 secret、endpoint、完整 object key、
 * SDK 异常全文；定位信息最多使用 resourceId。
 *
 * <p>可观测性（新计划任务 6）：save/open/discard 成功失败埋点统一上报
 * {@link ResourceStorageMetrics}（失败按 kind 细分）；416 Range 语义错误
 * 未发生存储读失败，不计入 open 失败。本类自身不打日志，告警统一由 metrics 发出。
 */
public class MinioResourceStorage implements ResourceStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioResourceStorage.class);

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    /** 凭证/权限类 S3 错误码：映射为 UNAVAILABLE（服务端拒绝服务）。 */
    private static final Set<String> CREDENTIAL_ERROR_CODES =
            Set.of("AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch");

    private final MinioClient minioClient;
    private final ResourceStorageProperties properties;
    private final ResourceStorageMetrics metrics;

    public MinioResourceStorage(
            MinioClient minioClient,
            ResourceStorageProperties properties,
            ResourceStorageMetrics metrics
    ) {
        this.minioClient = Objects.requireNonNull(minioClient, "minioClient must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    // ------------------------------------------------------------------
    // save
    // ------------------------------------------------------------------

    @Override
    public StoredResource save(ResourceSaveCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        String resourceId = UUID.randomUUID().toString();
        String objectKey = buildObjectKey(command.resourceType(), command.extension(), resourceId);
        String mimeType = normalizeMimeType(command.mimeType());
        String extension = safeExtension(command.extension());
        String bucket = properties.getMinio().getBucket();
        long partSizeBytes = properties.getMinio().getPartSizeBytes();
        long effectiveMaxBytes = effectiveMaxBytes(command);

        boolean putAttempted = false;
        try (InputStream source = command.openContentStream()) {
            if (command.declaredSize() != null && command.declaredSize() > effectiveMaxBytes) {
                throw new ResourceStorageException(
                        ResourceStorageErrorKind.SIZE_LIMIT, "资源大小超过存储上限");
            }
            SizeLimitingInputStream limited =
                    new SizeLimitingInputStream(source, effectiveMaxBytes);
            Long declaredSize = command.declaredSize();
            putAttempted = true;
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .contentType(mimeType)
                    .userMetadata(Map.of(
                            "schema-version", "1",
                            "created-by", "h-agent"))
                    .stream(limited, declaredSize == null ? -1L : declaredSize, partSizeBytes)
                    .build());
            long transferred = limited.transferredBytes();
            metrics.recordSaveSuccess();
            return new StoredResource(
                    resourceId,
                    ResourceStorageType.OBJECT_STORAGE.value(),
                    objectKey,
                    mimeType,
                    resourceId + extension,
                    transferred,
                    null,
                    null);
        } catch (ResourceStorageException exception) {
            metrics.recordSaveFailure(exception.kind());
            if (exception.kind() == ResourceStorageErrorKind.SIZE_LIMIT && putAttempted) {
                // 流式写入中途超限：best-effort 清理半写对象，
                // 清理失败只记 debug 日志，不覆盖原错误（计划任务 2）。
                bestEffortDelete(bucket, objectKey, resourceId);
            }
            throw exception;
        } catch (Exception exception) {
            ResourceStorageException mapped = toStorageException(exception, "资源写入失败");
            metrics.recordSaveFailure(mapped.kind());
            throw mapped;
        }
    }

    // ------------------------------------------------------------------
    // open
    // ------------------------------------------------------------------

    @Override
    public ResourceContent open(String storageKey, ResourceRange range) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        Objects.requireNonNull(range, "range must not be null");
        String bucket = properties.getMinio().getBucket();

        StatObjectResponse stat;
        try {
            stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .build());
        } catch (Exception exception) {
            ResourceStorageException mapped = toStorageException(exception, "资源读取失败");
            metrics.recordOpenFailure(mapped.kind());
            throw mapped;
        }

        long totalSize = stat.size();
        String mimeType = stat.contentType() == null || stat.contentType().isBlank()
                ? DEFAULT_MIME_TYPE
                : stat.contentType();

        // 416 语义（不可满足）原样上抛，由 Controller 层生成 Content-Range。
        ResourceRange.Resolved resolved = range.resolve(totalSize);

        try {
            GetObjectArgs.Builder builder = GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey);
            if (resolved.offset() != 0L || resolved.length() != totalSize) {
                builder.offset(resolved.offset()).length(resolved.length());
            }
            GetObjectResponse response = minioClient.getObject(builder.build());
            metrics.recordOpenSuccess();
            return new ResourceContent(
                    response,
                    mimeType,
                    totalSize,
                    resolved.length(),
                    resolved.offset(),
                    resolved.partial());
        } catch (Exception exception) {
            ResourceStorageException mapped = toStorageException(exception, "资源读取失败");
            metrics.recordOpenFailure(mapped.kind());
            throw mapped;
        }
    }

    // ------------------------------------------------------------------
    // discard
    // ------------------------------------------------------------------

    @Override
    public void discard(String storageKey) {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getMinio().getBucket())
                    .object(storageKey)
                    .build());
            metrics.recordDiscardSuccess();
        } catch (Exception exception) {
            ResourceStorageException mapped = toStorageException(exception, "资源删除失败");
            if (mapped.kind() == ResourceStorageErrorKind.NOT_FOUND) {
                // 幂等：对象不存在视为删除成功（计划 §4.1）。
                metrics.recordDiscardSuccess();
                return;
            }
            metrics.recordDiscardFailure(mapped.kind());
            throw mapped;
        }
    }

    // ------------------------------------------------------------------
    // object key 与字段归一化（计划 §5.2）
    // ------------------------------------------------------------------

    private String buildObjectKey(String resourceType, String extension, String resourceId) {
        return properties.getMinio().normalizedObjectPrefix()
                + "v1/" + keySegment(resourceType) + "/"
                + LocalDate.now(ZoneOffset.UTC).format(YEAR_MONTH) + "/"
                + resourceId + safeExtension(extension);
    }

    /** resourceType → key 段：IMAGE→images、VIDEO→videos、AUDIO→audio、FILE/DOCUMENT→files。 */
    private static String keySegment(String resourceType) {
        if (resourceType == null) {
            return "files";
        }
        return switch (resourceType.strip().toUpperCase(Locale.ROOT)) {
            case "IMAGE" -> "images";
            case "VIDEO" -> "videos";
            case "AUDIO" -> "audio";
            case "FILE", "DOCUMENT" -> "files";
            default -> "files";
        };
    }

    /** 扩展名清洗：小写、仅保留字母数字；空/清洗后为空则省略扩展名段。 */
    private static String safeExtension(String extension) {
        if (extension == null) {
            return "";
        }
        String cleaned = extension.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return cleaned.isEmpty() ? "" : "." + cleaned;
    }

    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return DEFAULT_MIME_TYPE;
        }
        return mimeType.strip();
    }

    /**
     * 生效上限 = min(配置属性 absoluteMaxBytes, 命令侧 effectiveMaxBytes())：
     * 属性小于默认 500 MiB 时收紧；大于时不放大命令的 ABSOLUTE_MAX_BYTES
     * （调用方无法通过配置放大上限）。
     */
    private long effectiveMaxBytes(ResourceSaveCommand command) {
        return Math.min(command.effectiveMaxBytes(), properties.getAbsoluteMaxBytes());
    }

    // ------------------------------------------------------------------
    // 异常映射矩阵（计划 §4.5）与脱敏日志
    // ------------------------------------------------------------------

    private ResourceStorageException toStorageException(Throwable failure, String safeMessage) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ResourceStorageException storageException) {
                return storageException;
            }
            if (current instanceof ErrorResponseException errorResponse) {
                return mapErrorResponse(errorResponse, failure);
            }
            if (current instanceof ServerException) {
                // ServerException 仅在 HTTP 5xx 时由 SDK 合成（计划任务 2 异常矩阵）。
                return new ResourceStorageException(
                        ResourceStorageErrorKind.UNAVAILABLE, "存储服务暂时不可用", failure);
            }
            if (current instanceof SocketTimeoutException
                    || current instanceof InterruptedIOException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException) {
                return new ResourceStorageException(
                        ResourceStorageErrorKind.UNAVAILABLE, "存储服务暂时不可用", failure);
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return new ResourceStorageException(ResourceStorageErrorKind.IO_ERROR, safeMessage, failure);
    }

    private ResourceStorageException mapErrorResponse(
            ErrorResponseException errorResponse, Throwable original) {
        String code = errorResponse.errorResponse().code();
        if ("NoSuchKey".equals(code)) {
            return new ResourceStorageException(
                    ResourceStorageErrorKind.NOT_FOUND, "资源不存在或已被删除", original);
        }
        if (CREDENTIAL_ERROR_CODES.contains(code)) {
            return new ResourceStorageException(
                    ResourceStorageErrorKind.UNAVAILABLE, "存储服务暂时不可用", original);
        }
        Response response = errorResponse.response();
        if (response != null && response.code() >= 500) {
            return new ResourceStorageException(
                    ResourceStorageErrorKind.UNAVAILABLE, "存储服务暂时不可用", original);
        }
        return new ResourceStorageException(
                ResourceStorageErrorKind.IO_ERROR, "资源存储读写失败", original);
    }

    private void bestEffortDelete(String bucket, String objectKey, String resourceId) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception cleanupFailure) {
            // 不输出 SDK 异常全文与完整 object key（计划不变量 17），
            // 仅记 resourceId 供排障定位。
            log.debug("best-effort 清理半写对象失败，resourceId={}", resourceId);
        }
    }

    // ------------------------------------------------------------------
    // 计数限流流：实际读取超过上限立即中止（计划 §6.1）
    // ------------------------------------------------------------------

    /** 包装命令内容流：透传读取并计数，累计字节超过 maxBytes 立即抛 SIZE_LIMIT。 */
    private static final class SizeLimitingInputStream extends FilterInputStream {

        private final long maxBytes;
        private long transferred;

        SizeLimitingInputStream(InputStream delegate, long maxBytes) {
            super(delegate);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                transferred++;
                checkLimit();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                transferred += read;
                checkLimit();
            }
            return read;
        }

        long transferredBytes() {
            return transferred;
        }

        private void checkLimit() {
            if (transferred > maxBytes) {
                throw new ResourceStorageException(
                        ResourceStorageErrorKind.SIZE_LIMIT, "资源大小超过存储上限");
            }
        }
    }
}
