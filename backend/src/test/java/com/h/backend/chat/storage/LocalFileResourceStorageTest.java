package com.h.backend.chat.infrastructure.storage;

import com.h.backend.chat.infrastructure.config.ImageGenerationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileResourceStorageTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // save：现有行为保持（前缀目录、扩展名、大小）
    // ------------------------------------------------------------------

    @Test
    void savesAudioResourcesUnderCallAudioDirectory() {
        LocalFileResourceStorage storage = storage();

        StoredResource stored = storage.save(new ResourceSaveCommand(
                "AUDIO", new byte[]{1, 2, 3}, "audio/webm", "webm", null, null
        ));

        assertThat(stored.storageType()).isEqualTo("LOCAL_FILE");
        assertThat(stored.storageKey()).startsWith("call-audio/");
        assertThat(stored.fileName()).endsWith(".webm");
        assertThat(stored.mimeType()).isEqualTo("audio/webm");
        assertThat(stored.fileSize()).isEqualTo(3L);
        assertThat(Files.exists(tempDir.resolve(stored.storageKey()))).isTrue();
    }

    @Test
    void infersAudioExtensionFromMimeType() {
        LocalFileResourceStorage storage = storage();

        StoredResource stored = storage.save(new ResourceSaveCommand(
                "AUDIO", new byte[]{1, 2, 3}, "audio/webm", null, null, null
        ));

        assertThat(stored.storageKey()).startsWith("call-audio/");
        assertThat(stored.storageKey()).endsWith(".webm");
        assertThat(stored.fileName()).endsWith(".webm");
    }

    @Test
    void savesGeneratedFilesUnderGeneratedFilesDirectory() {
        LocalFileResourceStorage storage = storage();

        StoredResource stored = storage.save(new ResourceSaveCommand(
                "FILE", new byte[]{1, 2, 3}, "application/pdf", "pdf", null, null
        ));

        assertThat(stored.storageKey()).startsWith("generated-files/");
        assertThat(stored.fileName()).startsWith("file-");
        assertThat(stored.fileName()).endsWith(".pdf");
    }

    @Test
    void savesGeneratedVideosUnderGeneratedVideosDirectory() {
        LocalFileResourceStorage storage = storage();

        StoredResource stored = storage.save(new ResourceSaveCommand(
                "VIDEO", new byte[]{1, 2, 3}, "video/mp4", "mp4", null, null
        ));

        assertThat(stored.storageKey()).startsWith("generated-videos/");
        assertThat(stored.fileName()).startsWith("video-");
        assertThat(stored.fileName()).endsWith(".mp4");
    }

    @Test
    void savesVideoStreamThroughTheSameResourceSaveCommand() throws Exception {
        LocalFileResourceStorage storage = storage();

        StoredResource stored = storage.save(ResourceSaveCommand.fromStream(
                "VIDEO", new ByteArrayInputStream(new byte[]{1, 2, 3}), "video/mp4", "mp4", 10
        ));

        assertThat(stored.fileSize()).isEqualTo(3L);
        assertThat(Files.exists(tempDir.resolve(stored.storageKey()))).isTrue();
        assertThat(Files.size(tempDir.resolve(stored.storageKey()))).isEqualTo(3L);
    }

    // ------------------------------------------------------------------
    // save：大小上限（计划 §6.1：业务上限与绝对上限取小，超限映射 SIZE_LIMIT）
    // ------------------------------------------------------------------

    @Test
    void saveRejectsContentAboveBusinessMaxBytes() {
        LocalFileResourceStorage storage = storage();

        assertThatThrownBy(() -> storage.save(ResourceSaveCommand.fromStream(
                "VIDEO", new ByteArrayInputStream(new byte[11]), "video/mp4", "mp4", 10
        )))
                .isInstanceOf(ResourceStorageException.class)
                .satisfies(error -> assertThat(((ResourceStorageException) error).kind())
                        .isEqualTo(ResourceStorageErrorKind.SIZE_LIMIT));
    }

    @Test
    void saveRejectsDeclaredSizeAboveLimitBeforeReadingTheStream() {
        LocalFileResourceStorage storage = storage();
        InputStream neverConsumed = new ByteArrayInputStream(new byte[1]);

        assertThatThrownBy(() -> storage.save(ResourceSaveCommand.fromStream(
                "VIDEO", neverConsumed, 11L, "video/mp4", "mp4", 10
        )))
                .isInstanceOf(ResourceStorageException.class)
                .satisfies(error -> assertThat(((ResourceStorageException) error).kind())
                        .isEqualTo(ResourceStorageErrorKind.SIZE_LIMIT));
    }

    // ------------------------------------------------------------------
    // open(String, ResourceRange)：完整读取字段契约
    // ------------------------------------------------------------------

    @Test
    void openReturnsCompleteContentForFullRead() throws IOException {
        LocalFileResourceStorage storage = storage();
        StoredResource stored = storage.save(new ResourceSaveCommand(
                "FILE", new byte[]{1, 2, 3, 4, 5}, "application/octet-stream", "bin", null, null
        ));

        try (ResourceContent content = storage.open(stored.storageKey(), ResourceRange.fullRead())) {
            assertThat(content.totalSize()).isEqualTo(5L);
            assertThat(content.responseLength()).isEqualTo(5L);
            assertThat(content.offset()).isZero();
            assertThat(content.partial()).isFalse();
            assertThat(content.mimeType()).isNotBlank();
            assertThat(content.inputStream().readAllBytes()).containsExactly(1, 2, 3, 4, 5);
        }
    }

    // ------------------------------------------------------------------
    // open(String, ResourceRange)：区间读取（206 部分内容语义）
    // ------------------------------------------------------------------

    @Test
    void openReturnsPartialContentForClosedRange() throws IOException {
        LocalFileResourceStorage storage = storage();
        StoredResource stored = storage.save(new ResourceSaveCommand(
                "FILE", new byte[]{1, 2, 3, 4, 5}, "application/octet-stream", "bin", null, null
        ));

        try (ResourceContent content = storage.open(stored.storageKey(), ResourceRange.fromHeader("bytes=1-3"))) {
            assertThat(content.totalSize()).isEqualTo(5L);
            assertThat(content.offset()).isEqualTo(1L);
            assertThat(content.responseLength()).isEqualTo(3L);
            assertThat(content.partial()).isTrue();
            assertThat(content.inputStream().readAllBytes()).containsExactly(2, 3, 4);
        }
    }

    @Test
    void openReturnsPartialContentForOpenEndedRange() throws IOException {
        LocalFileResourceStorage storage = storage();
        StoredResource stored = storage.save(new ResourceSaveCommand(
                "FILE", new byte[]{1, 2, 3, 4, 5}, "application/octet-stream", "bin", null, null
        ));

        try (ResourceContent content = storage.open(stored.storageKey(), ResourceRange.fromHeader("bytes=3-"))) {
            assertThat(content.offset()).isEqualTo(3L);
            assertThat(content.responseLength()).isEqualTo(2L);
            assertThat(content.partial()).isTrue();
            assertThat(content.inputStream().readAllBytes()).containsExactly(4, 5);
        }
    }

    @Test
    void openReturnsPartialContentForSuffixRange() throws IOException {
        LocalFileResourceStorage storage = storage();
        StoredResource stored = storage.save(new ResourceSaveCommand(
                "FILE", new byte[]{1, 2, 3, 4, 5}, "application/octet-stream", "bin", null, null
        ));

        try (ResourceContent content = storage.open(stored.storageKey(), ResourceRange.fromHeader("bytes=-2"))) {
            assertThat(content.offset()).isEqualTo(3L);
            assertThat(content.responseLength()).isEqualTo(2L);
            assertThat(content.partial()).isTrue();
            assertThat(content.inputStream().readAllBytes()).containsExactly(4, 5);
        }
    }

    @Test
    void openClampsEndBeyondTotalSize() throws IOException {
        LocalFileResourceStorage storage = storage();
        StoredResource stored = storage.save(new ResourceSaveCommand(
                "FILE", new byte[]{1, 2, 3, 4, 5}, "application/octet-stream", "bin", null, null
        ));

        try (ResourceContent content = storage.open(stored.storageKey(), ResourceRange.fromHeader("bytes=4-99"))) {
            assertThat(content.offset()).isEqualTo(4L);
            assertThat(content.responseLength()).isEqualTo(1L);
            assertThat(content.partial()).isTrue();
            assertThat(content.inputStream().readAllBytes()).containsExactly(5);
        }
    }

    // ------------------------------------------------------------------
    // open：错误语义
    // ------------------------------------------------------------------

    @Test
    void openThrowsNotFoundWhenFileIsMissing() {
        LocalFileResourceStorage storage = storage();

        assertThatThrownBy(() -> storage.open("generated-files/missing.bin", ResourceRange.fullRead()))
                .isInstanceOf(ResourceStorageException.class)
                .satisfies(error -> {
                    ResourceStorageException storageError = (ResourceStorageException) error;
                    assertThat(storageError.kind()).isEqualTo(ResourceStorageErrorKind.NOT_FOUND);
                    // 原始 cause 保留在异常链中（NoSuchFileException）。
                    assertThat(storageError.getCause()).isInstanceOf(NoSuchFileException.class);
                    // 消息不包含完整存储 key。
                    assertThat(storageError.getMessage()).doesNotContain("generated-files");
                    assertThat(storageError.getMessage()).doesNotContain("missing.bin");
                });
    }

    @Test
    void openThrowsUnsatisfiableWhenRangeStartsBeyondTotalSize() {
        LocalFileResourceStorage storage = storage();
        StoredResource stored = storage.save(new ResourceSaveCommand(
                "FILE", new byte[]{1, 2, 3}, "application/octet-stream", "bin", null, null
        ));

        assertThatThrownBy(() -> storage.open(stored.storageKey(), ResourceRange.fromHeader("bytes=100-")))
                .isInstanceOf(ResourceRangeException.class)
                .satisfies(error -> {
                    ResourceRangeException rangeError = (ResourceRangeException) error;
                    assertThat(rangeError.reason()).isEqualTo(ResourceRangeException.Reason.UNSATISFIABLE);
                    assertThat(rangeError.totalSize()).isEqualTo(3L);
                });
    }

    // ------------------------------------------------------------------
    // discard：幂等删除（计划 §4.1：对不存在对象幂等）
    // ------------------------------------------------------------------

    @Test
    void discardDeletesAnExistingObject() {
        LocalFileResourceStorage storage = storage();
        StoredResource stored = storage.save(new ResourceSaveCommand(
                "FILE", new byte[]{1, 2, 3}, "application/octet-stream", "bin", null, null
        ));
        assertThat(Files.exists(tempDir.resolve(stored.storageKey()))).isTrue();

        storage.discard(stored.storageKey());

        assertThat(Files.exists(tempDir.resolve(stored.storageKey()))).isFalse();
    }

    @Test
    void discardIsIdempotentWhenObjectDoesNotExist() {
        LocalFileResourceStorage storage = storage();

        storage.discard("generated-files/never-saved.bin");
        storage.discard("generated-files/never-saved.bin");

        // 幂等：第二次删除不抛异常。
        storage.discard("generated-files/never-saved.bin");
    }

    private LocalFileResourceStorage storage() {
        ImageGenerationProperties properties = new ImageGenerationProperties(
                null,
                new ImageGenerationProperties.LocalStorage(tempDir.toString(), "")
        );
        return new LocalFileResourceStorage(properties);
    }
}
