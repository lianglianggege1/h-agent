package com.h.backend.chat.infrastructure.storage;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.HeadObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.ServerException;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MinioResourceStorage 单元测试（计划 §10 任务 2 / §11.1）。
 *
 * <p>全部使用 Mockito mock MinioClient，无网络。putObject 桩以 Answer 完整消费内容流，
 * 模拟真实 SDK 读取行为；SDK 读取中触发的 IOException 会被 minio 9.0.1 的
 * throwMinioException 包成 IllegalStateException，桩同步复刻该行为。
 */
class MinioResourceStorageTest {

    private static final String BUCKET = "h-agent-test-bucket";
    private static final String ENDPOINT_HOST = "10.99.99.99";
    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private MinioClient minioClient;
    private ResourceStorageProperties properties;
    private ResourceStorageMetrics metrics;
    private MinioResourceStorage storage;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        properties = new ResourceStorageProperties();
        properties.getMinio().setBucket(BUCKET);
        metrics = new ResourceStorageMetrics();
        storage = new MinioResourceStorage(minioClient, properties, metrics);
    }

    // ------------------------------------------------------------------
    // save：object key 生成规则（计划 §5.2）
    // ------------------------------------------------------------------

    @Test
    void saveMapsResourceTypeToKeySegment() throws Exception {
        stubPutConsumingStream();

        storage.save(command("IMAGE", new byte[]{1}, "image/webp", "webp"));
        storage.save(command("VIDEO", new byte[]{1}, "video/mp4", "mp4"));
        storage.save(command("AUDIO", new byte[]{1}, "audio/mpeg", "mp3"));
        storage.save(command("FILE", new byte[]{1}, "application/pdf", "pdf"));
        storage.save(command("DOCUMENT", new byte[]{1}, "application/pdf", "pdf"));
        storage.save(command("UNKNOWN_TYPE", new byte[]{1}, "application/octet-stream", "bin"));

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient, times(6)).putObject(captor.capture());

        List<String> objects = captor.getAllValues().stream()
                .map(PutObjectArgs::object)
                .toList();
        assertThat(objects).hasSize(6);
        assertThat(objects.get(0)).matches(keyPattern("images"));
        assertThat(objects.get(1)).matches(keyPattern("videos"));
        assertThat(objects.get(2)).matches(keyPattern("audio"));
        assertThat(objects.get(3)).matches(keyPattern("files"));
        assertThat(objects.get(4)).matches(keyPattern("files"));
        assertThat(objects.get(5)).matches(keyPattern("files"));
        assertThat(captor.getAllValues()).extracting(PutObjectArgs::bucket)
                .containsOnly(BUCKET);
    }

    @Test
    void saveGeneratesDatedUuidKeyWithPrefixAndExtension() throws Exception {
        stubPutConsumingStream();

        StoredResource stored = storage.save(command("IMAGE", new byte[]{1, 2, 3}, "image/webp", "webp"));

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        String objectKey = captor.getValue().object();

        assertThat(objectKey)
                .matches("resources/v1/images/\\d{4}/\\d{2}/" + UUID_PATTERN + "\\.webp");
        assertThat(objectKey).endsWith(stored.id() + ".webp");
    }

    @Test
    void saveCleansExtensionAndOmitsItWhenMissing() throws Exception {
        stubPutConsumingStream();

        storage.save(command("FILE", new byte[]{1}, "application/pdf", ".PDF"));
        storage.save(command("FILE", new byte[]{1}, "application/pdf", "we ird/x"));
        storage.save(ResourceSaveCommand.fromStream(
                "FILE", new ByteArrayInputStream(new byte[]{1}), null, "application/pdf", null, 10));

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient, times(3)).putObject(captor.capture());

        assertThat(captor.getAllValues().get(0).object())
                .matches("resources/v1/files/\\d{4}/\\d{2}/" + UUID_PATTERN + "\\.pdf$");
        assertThat(captor.getAllValues().get(1).object())
                .matches("resources/v1/files/\\d{4}/\\d{2}/" + UUID_PATTERN + "\\.weirdx$");
        assertThat(captor.getAllValues().get(2).object())
                .matches("resources/v1/files/\\d{4}/\\d{2}/" + UUID_PATTERN + "$");
    }

    @Test
    void saveHonorsConfiguredObjectPrefix() throws Exception {
        stubPutConsumingStream();
        properties.getMinio().setObjectPrefix("custom-prefix");

        storage.save(command("IMAGE", new byte[]{1}, "image/webp", "webp"));

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        assertThat(captor.getValue().object()).startsWith("custom-prefix/v1/images/");
    }

    // ------------------------------------------------------------------
    // save：metadata（计划 §5.3，不变量 13：不写 SHA-256）
    // ------------------------------------------------------------------

    @Test
    void saveWritesOnlyContentTypeAndSchemaMetadata() throws Exception {
        stubPutConsumingStream();

        storage.save(command("IMAGE", new byte[]{1, 2}, "image/webp", "webp"));

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        PutObjectArgs args = captor.getValue();

        assertThat(args.contentType().toString()).isEqualTo("image/webp");

        Map<String, String> metadata = new HashMap<>();
        // SDK 会自动加 x-amz-meta- 前缀并规范为 Title-Case，这里小写化后断言。
        args.userMetadata().forEach(entry ->
                metadata.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
        assertThat(metadata).containsOnly(
                Map.entry("x-amz-meta-schema-version", "1"),
                Map.entry("x-amz-meta-created-by", "h-agent"));
        assertThat(metadata.keySet()).noneMatch(name -> name.contains("sha"));
    }

    // ------------------------------------------------------------------
    // save：StoredResource 字段（计划 §4.1/§6.2：不使用 ImageIO）
    // ------------------------------------------------------------------

    @Test
    void saveReturnsObjectStorageStoredResourceWithActualSize() throws Exception {
        stubPutConsumingStream();

        StoredResource stored = storage.save(command("IMAGE", new byte[]{1, 2, 3}, "image/webp", "webp"));

        assertThat(stored.storageType()).isEqualTo("OBJECT_STORAGE");
        assertThat(stored.id()).isNotBlank();
        assertThat(stored.storageKey()).isNotBlank();
        assertThat(stored.mimeType()).isEqualTo("image/webp");
        assertThat(stored.fileName()).isEqualTo(stored.id() + ".webp");
        assertThat(stored.fileSize()).isEqualTo(3L);
        assertThat(stored.width()).isNull();
        assertThat(stored.height()).isNull();
    }

    @Test
    void saveFallsBackToOctetStreamMimeType() throws Exception {
        stubPutConsumingStream();

        StoredResource stored = storage.save(command("FILE", new byte[]{1}, " ", "bin"));

        assertThat(stored.mimeType()).isEqualTo("application/octet-stream");
    }

    @Test
    void saveEmptyFileSucceedsWithZeroSize() throws Exception {
        stubPutConsumingStream();

        StoredResource stored = storage.save(command("FILE", new byte[0], "application/pdf", "pdf"));

        assertThat(stored.fileSize()).isZero();
        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        assertThat(captor.getValue().objectSize()).isZero();
    }

    @Test
    void saveKnownSizePassesDeclaredObjectSizeAndUnknownSizeUsesConfiguredPartSize() throws Exception {
        stubPutConsumingStream();

        storage.save(command("FILE", new byte[]{1, 2, 3, 4, 5}, "application/pdf", "pdf"));
        storage.save(ResourceSaveCommand.fromStream(
                "VIDEO", new ByteArrayInputStream(new byte[]{1, 2, 3}), null, "video/mp4", "mp4", 100));

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient, times(2)).putObject(captor.capture());

        assertThat(captor.getAllValues().get(0).objectSize()).isEqualTo(5L);
        assertThat(captor.getAllValues().get(1).objectSize()).isEqualTo(-1L);
        assertThat(captor.getAllValues().get(1).partSize()).isEqualTo(10_485_760L);
    }

    // ------------------------------------------------------------------
    // save：大小上限（计划 §6.1 / §11.1 边界）
    // ------------------------------------------------------------------

    @Test
    void saveRejectsDeclaredSizeAboveLimitBeforeReading() throws Exception {
        TrackingInputStream stream = new TrackingInputStream(new byte[10]);

        assertThatThrownBy(() -> storage.save(ResourceSaveCommand.fromStream(
                "FILE", stream, 6L, "application/pdf", "pdf", 5)))
                .isInstanceOfSatisfying(ResourceStorageException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.SIZE_LIMIT));

        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
        assertThat(stream.isClosed()).isTrue();
    }

    @Test
    void saveAcceptsStreamExactlyAtLimit() throws Exception {
        stubPutConsumingStream();

        StoredResource stored = storage.save(ResourceSaveCommand.fromStream(
                "FILE", new ByteArrayInputStream(new byte[5]), null, "application/pdf", "pdf", 5));

        assertThat(stored.fileSize()).isEqualTo(5L);
    }

    @Test
    void saveAbortsAndBestEffortCleansUpWhenStreamExceedsLimit() throws Exception {
        stubPutConsumingStream();
        TrackingInputStream stream = new TrackingInputStream(new byte[6]);

        assertThatThrownBy(() -> storage.save(ResourceSaveCommand.fromStream(
                "FILE", stream, null, "application/pdf", "pdf", 5)))
                .isInstanceOfSatisfying(ResourceStorageException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.SIZE_LIMIT);
                    assertThat(exception.getMessage()).doesNotContain(BUCKET)
                            .doesNotContain(ENDPOINT_HOST)
                            .doesNotContain("IllegalStateException");
                });

        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
        assertThat(stream.isClosed()).isTrue();
    }

    @Test
    void saveCleanupFailureDoesNotMaskSizeLimitError() throws Exception {
        stubPutConsumingStream();
        doThrow(new RuntimeException("cleanup failed"))
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));

        assertThatThrownBy(() -> storage.save(ResourceSaveCommand.fromStream(
                "FILE", new ByteArrayInputStream(new byte[6]), null, "application/pdf", "pdf", 5)))
                .isInstanceOfSatisfying(ResourceStorageException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.SIZE_LIMIT));
    }

    @Test
    void saveTightensLimitFromConfiguredAbsoluteMaxBytes() throws Exception {
        stubPutConsumingStream();
        properties.setAbsoluteMaxBytes(4L);

        assertThatThrownBy(() -> storage.save(ResourceSaveCommand.fromStream(
                "FILE", new ByteArrayInputStream(new byte[5]), null, "application/pdf", "pdf", 0)))
                .isInstanceOfSatisfying(ResourceStorageException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.SIZE_LIMIT));
    }

    // ------------------------------------------------------------------
    // save：输入流生命周期（计划 §6.1：Adapter 负责关闭）
    // ------------------------------------------------------------------

    @Test
    void saveClosesStreamOnSuccess() throws Exception {
        stubPutConsumingStream();
        TrackingInputStream stream = new TrackingInputStream(new byte[3]);

        storage.save(ResourceSaveCommand.fromStream(
                "FILE", stream, null, "application/pdf", "pdf", 10));

        assertThat(stream.isClosed()).isTrue();
    }

    @Test
    void saveClosesStreamWhenPutObjectFails() throws Exception {
        TrackingInputStream stream = new TrackingInputStream(new byte[3]);
        when(minioClient.putObject(any(PutObjectArgs.class)))
                .thenThrow(new RuntimeException("sdk failure with endpoint " + ENDPOINT_HOST));

        assertThatThrownBy(() -> storage.save(ResourceSaveCommand.fromStream(
                "FILE", stream, null, "application/pdf", "pdf", 10)))
                .isInstanceOfSatisfying(ResourceStorageException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.IO_ERROR);
                    assertThat(exception.getMessage()).doesNotContain(ENDPOINT_HOST)
                            .doesNotContain("sdk failure");
                });
        assertThat(stream.isClosed()).isTrue();
    }

    // ------------------------------------------------------------------
    // open：stat + ranged GET（计划 §6.4）
    // ------------------------------------------------------------------

    @Test
    void openStatsThenPerformsFullRead() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(statResponse(100L, "video/mp4"));
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getObjectResponse(new byte[100]));

        ResourceContent content = storage.open("resources/v1/videos/2026/08/x.mp4", ResourceRange.fullRead());

        assertThat(content.totalSize()).isEqualTo(100L);
        assertThat(content.responseLength()).isEqualTo(100L);
        assertThat(content.offset()).isZero();
        assertThat(content.partial()).isFalse();
        assertThat(content.mimeType()).isEqualTo("video/mp4");

        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minioClient).getObject(captor.capture());
        assertThat(captor.getValue().offset()).isNull();
        assertThat(captor.getValue().length()).isNull();
    }

    @Test
    void openPushesResolvedOffsetAndLengthToRangedGet() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(statResponse(100L, "video/mp4"));
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getObjectResponse(new byte[10]));

        ResourceContent content = storage.open(
                "resources/v1/videos/2026/08/x.mp4", ResourceRange.fromHeader("bytes=10-19"));

        assertThat(content.totalSize()).isEqualTo(100L);
        assertThat(content.responseLength()).isEqualTo(10L);
        assertThat(content.offset()).isEqualTo(10L);
        assertThat(content.partial()).isTrue();

        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minioClient).getObject(captor.capture());
        assertThat(captor.getValue().offset()).isEqualTo(10L);
        assertThat(captor.getValue().length()).isEqualTo(10L);
    }

    @Test
    void openResolvesSuffixRangeFromTotalSize() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(statResponse(100L, "video/mp4"));
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getObjectResponse(new byte[5]));

        ResourceContent content = storage.open(
                "resources/v1/videos/2026/08/x.mp4", ResourceRange.fromHeader("bytes=-5"));

        assertThat(content.offset()).isEqualTo(95L);
        assertThat(content.responseLength()).isEqualTo(5L);

        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minioClient).getObject(captor.capture());
        assertThat(captor.getValue().offset()).isEqualTo(95L);
        assertThat(captor.getValue().length()).isEqualTo(5L);
    }

    @Test
    void openPropagatesUnsatisfiableRangeWithoutObjectRead() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(statResponse(100L, "video/mp4"));

        assertThatThrownBy(() -> storage.open(
                "resources/v1/videos/2026/08/x.mp4", ResourceRange.fromHeader("bytes=100-")))
                .isInstanceOfSatisfying(ResourceRangeException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(ResourceRangeException.Reason.UNSATISFIABLE);
                    assertThat(exception.totalSize()).isEqualTo(100L);
                });

        verify(minioClient, never()).getObject(any(GetObjectArgs.class));
    }

    @Test
    void openFallsBackToOctetStreamWhenContentTypeMissing() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenReturn(statResponse(10L, null));
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getObjectResponse(new byte[10]));

        ResourceContent content = storage.open("resources/v1/files/2026/08/x.pdf", ResourceRange.fullRead());

        assertThat(content.mimeType()).isEqualTo("application/octet-stream");
    }

    // ------------------------------------------------------------------
    // discard：幂等与错误映射（计划 §4.1）
    // ------------------------------------------------------------------

    @Test
    void discardRemovesObject() throws Exception {
        storage.discard("resources/v1/files/2026/08/x.pdf");

        ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient).removeObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().object()).isEqualTo("resources/v1/files/2026/08/x.pdf");
    }

    @Test
    void discardTreatsMissingObjectAsSuccess() throws Exception {
        doThrow(errorResponseException("NoSuchKey", 404))
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));

        storage.discard("resources/v1/files/2026/08/x.pdf");
    }

    // ------------------------------------------------------------------
    // 异常映射矩阵（计划 §4.5）：全部脱敏
    // ------------------------------------------------------------------

    @Test
    void openMapsNoSuchKeyToNotFoundWithSanitizedMessage() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(errorResponseException("NoSuchKey", 404));

        assertThatThrownBy(() -> storage.open("resources/v1/files/2026/08/x.pdf", ResourceRange.fullRead()))
                .isInstanceOfSatisfying(ResourceStorageException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.NOT_FOUND);
                    assertThat(exception.getMessage())
                            .doesNotContain(BUCKET)
                            .doesNotContain(ENDPOINT_HOST)
                            .doesNotContain("resources/v1/files")
                            .doesNotContain("ErrorResponseException")
                            .doesNotContain("x.pdf");
                    assertThat(exception.getCause()).isInstanceOf(ErrorResponseException.class);
                });
    }

    @Test
    void openMapsAccessDeniedToUnavailable() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(errorResponseException("AccessDenied", 403));

        assertThatThrownBy(() -> storage.open("resources/v1/files/2026/08/x.pdf", ResourceRange.fullRead()))
                .isInstanceOfSatisfying(ResourceStorageException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.UNAVAILABLE));
    }

    @Test
    void openMapsTimeoutToUnavailable() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new IllegalStateException(
                        new SocketTimeoutException("connect timed out to " + ENDPOINT_HOST)));

        assertThatThrownBy(() -> storage.open("resources/v1/files/2026/08/x.pdf", ResourceRange.fullRead()))
                .isInstanceOfSatisfying(ResourceStorageException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.UNAVAILABLE);
                    assertThat(exception.getMessage())
                            .doesNotContain(ENDPOINT_HOST)
                            .doesNotContain("SocketTimeoutException")
                            .doesNotContain("timed out");
                });
    }

    @Test
    void openMaps5xxServerExceptionToUnavailable() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new ServerException("server failed with HTTP status code 500", 500, "trace"));

        assertThatThrownBy(() -> storage.open("resources/v1/files/2026/08/x.pdf", ResourceRange.fullRead()))
                .isInstanceOfSatisfying(ResourceStorageException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.UNAVAILABLE);
                    assertThat(exception.getMessage()).doesNotContain("500");
                });
    }

    @Test
    void openMapsUnknownExceptionToIoError() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new RuntimeException("boom " + ENDPOINT_HOST + " secret"));

        assertThatThrownBy(() -> storage.open("resources/v1/files/2026/08/x.pdf", ResourceRange.fullRead()))
                .isInstanceOfSatisfying(ResourceStorageException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.IO_ERROR);
                    assertThat(exception.getMessage())
                            .doesNotContain(ENDPOINT_HOST)
                            .doesNotContain("boom");
                });
    }

    @Test
    void discardMapsAccessDeniedToUnavailableAndOthersToIoError() throws Exception {
        doThrow(errorResponseException("AccessDenied", 403))
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));
        assertThatThrownBy(() -> storage.discard("resources/v1/files/2026/08/x.pdf"))
                .isInstanceOfSatisfying(ResourceStorageException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.UNAVAILABLE));

        doThrow(new RuntimeException("boom"))
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));
        assertThatThrownBy(() -> storage.discard("resources/v1/files/2026/08/x.pdf"))
                .isInstanceOfSatisfying(ResourceStorageException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ResourceStorageErrorKind.IO_ERROR));
    }

    // ------------------------------------------------------------------
    // 可观测性埋点（新计划任务 6）：成功/失败计数与按 kind 细分
    // ------------------------------------------------------------------

    @Test
    void instrumentationCountsSaveOpenDiscardSuccessAndFailureByKind() throws Exception {
        // save 成功
        stubPutConsumingStream();
        storage.save(command("IMAGE", new byte[]{1}, "image/webp", "webp"));
        // save 失败：declaredSize 超限 → SIZE_LIMIT（未发 putObject）
        assertThatThrownBy(() -> storage.save(ResourceSaveCommand.fromStream(
                "FILE", new ByteArrayInputStream(new byte[10]), 6L, "application/pdf", "pdf", 5)))
                .isInstanceOf(ResourceStorageException.class);

        // open 成功（关闭流释放 mock 响应）
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(statResponse(10L, "video/mp4"));
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getObjectResponse(new byte[10]));
        try (ResourceContent ignored = storage.open("resources/v1/videos/2026/08/x.mp4", ResourceRange.fullRead())) {
            // 仅消费打开/关闭
        }
        // open 失败：NoSuchKey → NOT_FOUND
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(errorResponseException("NoSuchKey", 404));
        assertThatThrownBy(() -> storage.open("resources/v1/videos/2026/08/x.mp4", ResourceRange.fullRead()))
                .isInstanceOf(ResourceStorageException.class);

        // discard 成功 + 幂等 NOT_FOUND 也计成功
        storage.discard("resources/v1/files/2026/08/x.pdf");
        doThrow(errorResponseException("NoSuchKey", 404))
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));
        storage.discard("resources/v1/files/2026/08/x.pdf");
        // discard 失败：AccessDenied → UNAVAILABLE
        doThrow(errorResponseException("AccessDenied", 403))
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));
        assertThatThrownBy(() -> storage.discard("resources/v1/files/2026/08/x.pdf"))
                .isInstanceOf(ResourceStorageException.class);

        ResourceStorageMetrics.StorageMetricsSnapshot snapshot = metrics.snapshot();
        assertThat(snapshot.saveSuccess()).isEqualTo(1L);
        assertThat(snapshot.saveFailuresByKind())
                .containsEntry(ResourceStorageErrorKind.SIZE_LIMIT, 1L)
                .hasSize(1);
        assertThat(snapshot.openSuccess()).isEqualTo(1L);
        assertThat(snapshot.openFailuresByKind())
                .containsEntry(ResourceStorageErrorKind.NOT_FOUND, 1L)
                .hasSize(1);
        assertThat(snapshot.discardSuccess()).isEqualTo(2L);
        assertThat(snapshot.discardFailuresByKind())
                .containsEntry(ResourceStorageErrorKind.UNAVAILABLE, 1L)
                .hasSize(1);
        assertThat(snapshot.compensatedDiscardSuccess()).isZero();
        assertThat(snapshot.compensatedDiscardFailure()).isZero();
    }

    @Test
    void unsatisfiableRangeIsNotCountedAsStorageFailure() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(statResponse(100L, "video/mp4"));

        assertThatThrownBy(() -> storage.open(
                "resources/v1/videos/2026/08/x.mp4", ResourceRange.fromHeader("bytes=100-")))
                .isInstanceOf(ResourceRangeException.class);

        // 416 是 Range 语义错误（未发生存储读失败），不计入 open 失败
        assertThat(metrics.snapshot().openFailureTotal()).isZero();
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    private static ResourceSaveCommand command(
            String resourceType, byte[] content, String mimeType, String extension) {
        return new ResourceSaveCommand(resourceType, content, mimeType, extension, null, null);
    }

    /** key 生成规则断言模式：前缀/v1/{segment}/{yyyy}/{MM}/{uuid}.{ext}。 */
    private static String keyPattern(String segment) {
        return "resources/v1/" + segment + "/\\d{4}/\\d{2}/" + UUID_PATTERN + "\\.[a-z0-9]+";
    }

    private void stubPutConsumingStream() throws Exception {
        when(minioClient.putObject(any(PutObjectArgs.class))).thenAnswer(invocation -> {
            PutObjectArgs args = invocation.getArgument(0);
            try (InputStream stream = args.stream()) {
                stream.readAllBytes();
            } catch (IOException exception) {
                // 模拟 minio 9.0.1 throwMinioException：非 MinioException 的 cause
                // 会包装为 IllegalStateException 上抛。
                throw new IllegalStateException(exception);
            }
            return null;
        });
    }

    private StatObjectResponse statResponse(long size, String contentType) {
        // HeadObjectResponse 严格解析 RFC1123 GMT 时间戳，动态生成避免星期几写错。
        String lastModified = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        Headers.Builder headers = new Headers.Builder()
                .set("Content-Length", String.valueOf(size))
                .set("Last-Modified", lastModified);
        if (contentType != null) {
            headers.set("Content-Type", contentType);
        }
        return new StatObjectResponse(new HeadObjectResponse(
                headers.build(), BUCKET, "us-east-1", "resources/v1/files/2026/08/x.pdf"));
    }

    private GetObjectResponse getObjectResponse(byte[] data) {
        return new GetObjectResponse(
                Headers.of("Content-Type", "application/octet-stream"),
                BUCKET, "us-east-1", "resources/v1/files/2026/08/x.pdf",
                new ByteArrayInputStream(data));
    }

    private ErrorResponseException errorResponseException(String code, int httpStatus) {
        ErrorResponse errorResponse = new ErrorResponse(
                code, "leaky message", BUCKET, "resources/v1/files/2026/08/x.pdf",
                "/h-agent-test-bucket/resources", "request-id", "host-id");
        Response response = new Response.Builder()
                .request(new Request.Builder()
                        .url("http://" + ENDPOINT_HOST + ":9000/" + BUCKET + "/resources/v1/files/x.pdf")
                        .build())
                .protocol(Protocol.HTTP_1_1)
                .code(httpStatus)
                .message("error")
                .build();
        return new ErrorResponseException(errorResponse, response, "trace");
    }

    private static final class TrackingInputStream extends FilterInputStream {
        private boolean closed;

        TrackingInputStream(byte[] data) {
            super(new ByteArrayInputStream(data));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        boolean isClosed() {
            return closed;
        }
    }
}
