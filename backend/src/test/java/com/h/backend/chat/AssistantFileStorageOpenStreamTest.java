package com.h.backend.chat;

import com.h.backend.chat.infrastructure.filesystem.AssistantFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AssistantFileStorage 受大小约束的流式读取方法测试（新计划任务 3）：
 * send_file_to_chat 不再要求把工作文件整读为 byte array。
 */
class AssistantFileStorageOpenStreamTest {

    @TempDir
    Path tempDir;

    @Test
    void openSessionFileStreamReturnsStreamWithSizeAndProbedType() throws Exception {
        AssistantFileStorage storage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        String memoryId = "1:22:session-1";
        storage.write(memoryId, "/report.txt", "hello-stream");

        AssistantFileStorage.AssistantSessionFileStream file = storage.openSessionFileStream(memoryId, "/report.txt");

        assertTrue(file.success());
        assertEquals("/report.txt", file.virtualPath());
        assertEquals("report.txt", file.fileName());
        assertEquals("hello-stream".getBytes(StandardCharsets.UTF_8).length, file.size());
        assertNotNull(file.mimeType());
        assertNotNull(file.stream());
        try (InputStream stream = file.stream()) {
            assertArrayEquals("hello-stream".getBytes(StandardCharsets.UTF_8), stream.readAllBytes());
        }
    }

    @Test
    void openSessionFileStreamRejectsFileExceedingSizeLimit() {
        AssistantFileStorage storage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 4);
        String memoryId = "1:22:session-1";
        storage.write(memoryId, "/big.txt", "too-long-content");

        AssistantFileStorage.AssistantSessionFileStream file = storage.openSessionFileStream(memoryId, "/big.txt");

        assertTrue(!file.success());
        assertEquals("File exceeds max readable size: /big.txt", file.error());
    }

    @Test
    void openSessionFileStreamReturnsErrorWhenFileMissing() {
        AssistantFileStorage storage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);

        AssistantFileStorage.AssistantSessionFileStream file =
                storage.openSessionFileStream("1:22:session-1", "/missing.txt");

        assertTrue(!file.success());
        assertEquals("File '/missing.txt' not found", file.error());
    }

    @Test
    void readSessionFileRemainsAvailableForCompatibility() {
        AssistantFileStorage storage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        String memoryId = "1:22:session-1";
        storage.write(memoryId, "/report.txt", "legacy");

        // 既有 byte[] 读取 API 保留（API 兼容性），任务 5 评估是否清理。
        AssistantFileStorage.AssistantSessionFile file = storage.readSessionFile(memoryId, "/report.txt");

        assertTrue(file.success());
        assertArrayEquals("legacy".getBytes(StandardCharsets.UTF_8), file.content());
    }
}
