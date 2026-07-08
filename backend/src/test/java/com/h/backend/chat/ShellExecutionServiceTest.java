package com.h.backend.chat;

import com.h.backend.chat.infrastructure.filesystem.AssistantFileStorage;
import com.h.backend.chat.infrastructure.tools.ShellExecutionService;
import com.h.backend.chat.infrastructure.tools.ShellToolProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellExecutionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExecuteCommandInsideCurrentSessionWorkspace() {
        AssistantFileStorage fileStorage = newStorage();
        fileStorage.write("7:22:session-a", "/input.txt", "hello");
        ShellExecutionService service = newService(fileStorage);

        String result = service.execute("7:22:session-a", "pwd && cat input.txt", "/", 5);

        assertTrue(result.contains("Exit code: 0"), result);
        assertTrue(result.contains(tempDir.resolve("assistant-files/7/session-a").toAbsolutePath().normalize().toString()), result);
        assertTrue(result.contains("hello"), result);
    }

    @Test
    void shouldResolveWorkingDirectoryInsideSessionWorkspace() {
        AssistantFileStorage fileStorage = newStorage();
        fileStorage.write("7:22:session-a", "/notes/input.txt", "nested");
        ShellExecutionService service = newService(fileStorage);

        String result = service.execute("7:22:session-a", "pwd && cat input.txt", "/notes", 5);

        assertTrue(result.contains("Exit code: 0"), result);
        assertTrue(result.contains(tempDir.resolve("assistant-files/7/session-a/notes").toAbsolutePath().normalize().toString()), result);
        assertTrue(result.contains("nested"), result);
    }

    @Test
    void shouldRejectEscapingWorkingDirectory() {
        ShellExecutionService service = newService(newStorage());

        String traversal = service.execute("7:22:session-a", "pwd", "/../secret", 5);
        String home = service.execute("7:22:session-a", "pwd", "~/secret", 5);
        String hostAbsolutePath = service.execute("7:22:session-a", "pwd", "/tmp", 5);

        assertTrue(traversal.contains("Error: Path traversal is not allowed"), traversal);
        assertTrue(home.contains("Error: Invalid filesystem path"), home);
        assertTrue(hostAbsolutePath.contains("Error: Working directory does not exist: /tmp"), hostAbsolutePath);
    }

    @Test
    void shouldRejectCommandPathsThatEscapeSessionWorkspace() {
        ShellExecutionService service = newService(newStorage());

        String absolutePath = service.execute("7:22:session-a", "cat /etc/passwd", "/", 5);
        String traversal = service.execute("7:22:session-a", "cat ../secret.txt", "/", 5);
        String parentDirectory = service.execute("7:22:session-a", "cd ..", "/", 5);

        assertTrue(absolutePath.contains("Error: Absolute paths are not allowed in shell commands"), absolutePath);
        assertTrue(traversal.contains("Error: Path traversal is not allowed in shell commands"), traversal);
        assertTrue(parentDirectory.contains("Error: Path traversal is not allowed in shell commands"), parentDirectory);
    }

    @Test
    void shouldTimeOutLongRunningCommand() {
        ShellExecutionService service = newService(newStorage());

        String result = service.execute("7:22:session-a", "sleep 2", "/", 1);

        assertTrue(result.contains("Exit code: 124"), result);
        assertTrue(result.contains("timed out after 1 seconds"), result);
    }

    @Test
    void shouldTruncateLongOutput() {
        ShellToolProperties properties = new ShellToolProperties();
        properties.setMaxOutputBytes(12);
        ShellExecutionService service = new ShellExecutionService(newStorage(), properties);

        String result = service.execute("7:22:session-a", "printf 12345678901234567890", "/", 5);

        assertTrue(result.contains("Exit code: 0"), result);
        assertTrue(result.contains("123456789012"), result);
        assertTrue(result.contains("(output was truncated)"), result);
    }

    @Test
    void shouldDrainLargeOutputWithoutBlocking() {
        ShellToolProperties properties = new ShellToolProperties();
        properties.setMaxOutputBytes(1024);
        ShellExecutionService service = new ShellExecutionService(newStorage(), properties);

        String result = service.execute("7:22:session-a", "yes x | head -n 50000", "/", 5);

        assertTrue(result.contains("Exit code: 0"), result);
        assertTrue(result.contains("(output was truncated)"), result);
    }

    private AssistantFileStorage newStorage() {
        return new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
    }

    private ShellExecutionService newService(AssistantFileStorage fileStorage) {
        return new ShellExecutionService(fileStorage, new ShellToolProperties());
    }
}
