package com.h.backend.chat.infrastructure.storage;

import com.h.backend.chat.infrastructure.config.ImageGenerationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileResourceStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAudioResourcesUnderCallAudioDirectory() {
        ImageGenerationProperties properties = new ImageGenerationProperties(
                null,
                new ImageGenerationProperties.LocalStorage(tempDir.toString(), "")
        );
        LocalFileResourceStorage storage = new LocalFileResourceStorage(properties);

        StoredResource stored = storage.save(new ResourceSaveCommand(
                "AUDIO",
                "session-1",
                "call-user-recording",
                new byte[]{1, 2, 3},
                "audio/webm",
                "webm",
                null,
                null
        ));

        assertTrue(stored.storageKey().startsWith("call-audio/"));
        assertTrue(stored.fileName().endsWith(".webm"));
        assertEquals("audio/webm", stored.mimeType());
        assertEquals(3L, stored.fileSize());
        assertTrue(Files.exists(tempDir.resolve(stored.storageKey())));
    }

    @Test
    void infersAudioExtensionFromMimeType() {
        ImageGenerationProperties properties = new ImageGenerationProperties(
                null,
                new ImageGenerationProperties.LocalStorage(tempDir.toString(), "")
        );
        LocalFileResourceStorage storage = new LocalFileResourceStorage(properties);

        StoredResource stored = storage.save(new ResourceSaveCommand(
                "AUDIO",
                "session-1",
                "call-user-recording",
                new byte[]{1, 2, 3},
                "audio/webm",
                null,
                null,
                null
        ));

        assertTrue(stored.storageKey().startsWith("call-audio/"));
        assertTrue(stored.storageKey().endsWith(".webm"));
        assertTrue(stored.fileName().endsWith(".webm"));
    }

    @Test
    void savesGeneratedFilesUnderGeneratedFilesDirectory() {
        ImageGenerationProperties properties = new ImageGenerationProperties(
                null,
                new ImageGenerationProperties.LocalStorage(tempDir.toString(), "")
        );
        LocalFileResourceStorage storage = new LocalFileResourceStorage(properties);

        StoredResource stored = storage.save(new ResourceSaveCommand(
                "FILE",
                "session-1",
                "send-file",
                new byte[]{1, 2, 3},
                "application/pdf",
                "pdf",
                null,
                null
        ));

        assertTrue(stored.storageKey().startsWith("generated-files/"));
        assertTrue(stored.fileName().startsWith("file-"));
        assertTrue(stored.fileName().endsWith(".pdf"));
    }

    @Test
    void savesGeneratedVideosUnderGeneratedVideosDirectory() {
        ImageGenerationProperties properties = new ImageGenerationProperties(
                null,
                new ImageGenerationProperties.LocalStorage(tempDir.toString(), "")
        );
        LocalFileResourceStorage storage = new LocalFileResourceStorage(properties);

        StoredResource stored = storage.save(new ResourceSaveCommand(
                "VIDEO",
                "session-1",
                "send-video",
                new byte[]{1, 2, 3},
                "video/mp4",
                "mp4",
                null,
                null
        ));

        assertTrue(stored.storageKey().startsWith("generated-videos/"));
        assertTrue(stored.fileName().startsWith("video-"));
        assertTrue(stored.fileName().endsWith(".mp4"));
    }

    @Test
    void savesVideoStreamThroughTheSameResourceSaveCommand() throws Exception {
        ImageGenerationProperties properties = new ImageGenerationProperties(
                null,
                new ImageGenerationProperties.LocalStorage(tempDir.toString(), "")
        );
        LocalFileResourceStorage storage = new LocalFileResourceStorage(properties);

        StoredResource stored = storage.save(ResourceSaveCommand.fromStream(
                "VIDEO", "session-1", null, new ByteArrayInputStream(new byte[]{1, 2, 3}),
                "video/mp4", "mp4", 10
        ));

        assertEquals(3L, stored.fileSize());
        assertTrue(Files.exists(tempDir.resolve(stored.storageKey())));
        assertEquals(3L, Files.size(tempDir.resolve(stored.storageKey())));
    }
}
