package com.h.backend.chat.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceSaveCommandTest {

    // ------------------------------------------------------------------
    // 绝对上限与 effectiveMaxBytes（计划 §6.1）
    // ------------------------------------------------------------------

    @Test
    void absoluteMaxBytesIs500MiB() {
        assertThat(ResourceSaveCommand.ABSOLUTE_MAX_BYTES).isEqualTo(524_288_000L);
    }

    @Test
    void effectiveMaxBytesUsesBusinessLimitWhenSmallerThanAbsoluteLimit() {
        ResourceSaveCommand command = ResourceSaveCommand.fromStream(
                "VIDEO", new ByteArrayInputStream(new byte[0]), "video/mp4", "mp4", 1_000L);

        assertThat(command.effectiveMaxBytes()).isEqualTo(1_000L);
    }

    @Test
    void effectiveMaxBytesCannotBeRaisedBeyondAbsoluteLimit() {
        ResourceSaveCommand command = ResourceSaveCommand.fromStream(
                "VIDEO", new ByteArrayInputStream(new byte[0]), "video/mp4", "mp4", Long.MAX_VALUE);

        assertThat(command.effectiveMaxBytes()).isEqualTo(ResourceSaveCommand.ABSOLUTE_MAX_BYTES);
    }

    @Test
    void nonPositiveMaxBytesMeansAbsoluteLimitNotUnlimited() {
        ResourceSaveCommand zero = ResourceSaveCommand.fromStream(
                "VIDEO", new ByteArrayInputStream(new byte[0]), "video/mp4", "mp4", 0L);
        ResourceSaveCommand negative = ResourceSaveCommand.fromStream(
                "VIDEO", new ByteArrayInputStream(new byte[0]), "video/mp4", "mp4", -5L);

        assertThat(zero.effectiveMaxBytes()).isEqualTo(ResourceSaveCommand.ABSOLUTE_MAX_BYTES);
        assertThat(negative.effectiveMaxBytes()).isEqualTo(ResourceSaveCommand.ABSOLUTE_MAX_BYTES);
    }

    @Test
    void byteFormFallsBackToAbsoluteLimit() {
        ResourceSaveCommand command = new ResourceSaveCommand(
                "IMAGE", new byte[]{1, 2, 3}, "image/png", "png", null, null);

        assertThat(command.effectiveMaxBytes()).isEqualTo(ResourceSaveCommand.ABSOLUTE_MAX_BYTES);
    }

    // ------------------------------------------------------------------
    // declaredSize 约定（计划 §6.1）
    // ------------------------------------------------------------------

    @Test
    void byteFormDeclaresItsSize() {
        byte[] content = new byte[]{1, 2, 3, 4};

        ResourceSaveCommand command = new ResourceSaveCommand(
                "IMAGE", content, "image/png", "png", 1024, 768);

        assertThat(command.declaredSize()).isEqualTo(4L);
        assertThat(command.content()).isEqualTo(content);
        assertThat(command.resourceType()).isEqualTo("IMAGE");
        assertThat(command.mimeType()).isEqualTo("image/png");
        assertThat(command.extension()).isEqualTo("png");
        assertThat(command.width()).isEqualTo(1024);
        assertThat(command.height()).isEqualTo(768);
    }

    @Test
    void streamFormWithoutDeclaredSizeLeavesItNull() {
        ResourceSaveCommand command = ResourceSaveCommand.fromStream(
                "VIDEO", new ByteArrayInputStream(new byte[0]), "video/mp4", "mp4", 1_000L);

        assertThat(command.declaredSize()).isNull();
    }

    @Test
    void streamFormWithDeclaredSizeCarriesIt() {
        ResourceSaveCommand command = ResourceSaveCommand.fromStream(
                "VIDEO", new ByteArrayInputStream(new byte[0]), 42L, "video/mp4", "mp4", 1_000L);

        assertThat(command.declaredSize()).isEqualTo(42L);
        assertThat(command.maxBytes()).isEqualTo(1_000L);
    }

    // ------------------------------------------------------------------
    // 流契约：单次可消费，由 Adapter 关闭
    // ------------------------------------------------------------------

    @Test
    void byteFormOpensContentStreamOverTheBytes() throws IOException {
        ResourceSaveCommand command = new ResourceSaveCommand(
                "IMAGE", new byte[]{7, 8, 9}, "image/png", "png", null, null);

        try (InputStream stream = command.openContentStream()) {
            assertThat(stream.readAllBytes()).containsExactly(7, 8, 9);
        }
    }

    @Test
    void streamFormReturnsTheProvidedContentStream() {
        InputStream provided = new ByteArrayInputStream(new byte[]{1});

        ResourceSaveCommand command = ResourceSaveCommand.fromStream(
                "FILE", provided, "application/pdf", "pdf", 10L);

        assertThat(command.openContentStream()).isSameAs(provided);
    }

    @Test
    void commandCarriesNoSessionOrPromptFields() {
        // 计划不变量 12：object key 不包含会话 ID、prompt。
        // ResourceSaveCommand 不再携带这些字段，从类型上杜绝泄漏途径。
        ResourceSaveCommand command = new ResourceSaveCommand(
                "IMAGE", new byte[]{1}, "image/png", "png", null, null);

        assertThat(java.util.Arrays.stream(command.getClass().getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .toList())
                .doesNotContain("sessionId", "prompt");
    }
}
