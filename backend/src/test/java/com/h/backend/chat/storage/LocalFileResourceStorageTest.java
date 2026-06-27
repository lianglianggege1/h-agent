package com.h.backend.chat.storage;

import com.h.backend.chat.config.ImageGenerationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
