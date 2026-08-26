package com.h.backend.chat.infrastructure.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceStorageExceptionTest {

    @Test
    void exposesExactlyFourStableErrorKinds() {
        assertThat(ResourceStorageErrorKind.values())
                .extracting(ResourceStorageErrorKind::httpStatus)
                .containsExactlyInAnyOrder(404, 413, 503, 500);
        assertThat(ResourceStorageErrorKind.NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ResourceStorageErrorKind.SIZE_LIMIT.httpStatus()).isEqualTo(413);
        assertThat(ResourceStorageErrorKind.UNAVAILABLE.httpStatus()).isEqualTo(503);
        assertThat(ResourceStorageErrorKind.IO_ERROR.httpStatus()).isEqualTo(500);
    }

    @Test
    void carriesItsErrorKind() {
        ResourceStorageException error = new ResourceStorageException(
                ResourceStorageErrorKind.NOT_FOUND, "资源不存在或已被清理");

        assertThat(error.kind()).isEqualTo(ResourceStorageErrorKind.NOT_FOUND);
        assertThat(error.getMessage()).isEqualTo("资源不存在或已被清理");
    }

    @Test
    void messageNeverLeakesCauseDetailsEvenWhenCauseCarriesMinioConfiguration() {
        Exception sensitiveCause = new IllegalStateException(
                "GetObject failed: endpoint=http://169.254.140.78:9000, bucket=huajiang, "
                        + "objectKey=resources/v1/videos/2026/08/550e8400-e29b-41d4-a716-446655440000.mp4, "
                        + "secret=AKIA-super-secret");

        ResourceStorageException error = new ResourceStorageException(
                ResourceStorageErrorKind.UNAVAILABLE, "资源存储暂时不可用", sensitiveCause);

        assertThat(error.getMessage())
                .doesNotContain("169.254.140.78")
                .doesNotContain("huajiang")
                .doesNotContain("resources/v1")
                .doesNotContain("550e8400")
                .doesNotContain("AKIA-super-secret")
                .doesNotContain("endpoint")
                .doesNotContain("secret")
                .doesNotContain("/");
        // 原始 cause 保留在异常链中，供结构化日志使用，但消息保持安全。
        assertThat(error.getCause()).isSameAs(sensitiveCause);
    }

    @Test
    void everyErrorKindKeepsItsMessageFreeOfStorageDetails() {
        Exception cause = new RuntimeException(
                "key=resources/v1/images/2026/08/abc.png, bucket=huajiang, http://169.254.140.78:9000");

        for (ResourceStorageErrorKind kind : ResourceStorageErrorKind.values()) {
            ResourceStorageException error = new ResourceStorageException(kind, kind + " 资源操作失败", cause);

            assertThat(error.getMessage())
                    .doesNotContain("resources/v1")
                    .doesNotContain("huajiang")
                    .doesNotContain("169.254.140.78")
                    .doesNotContain(".png")
                    .doesNotContain("bucket");
            assertThat(error.kind()).isEqualTo(kind);
        }
    }

    @Test
    void worksWithoutCause() {
        ResourceStorageException error = new ResourceStorageException(
                ResourceStorageErrorKind.SIZE_LIMIT, "资源超过大小上限");

        assertThat(error.getCause()).isNull();
        assertThat(error.getMessage()).isEqualTo("资源超过大小上限");
    }
}
