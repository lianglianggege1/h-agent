package com.h.backend.chat;

import com.h.backend.chat.infrastructure.filesystem.AssistantFileStorage;
import com.h.backend.chat.infrastructure.tools.FilesystemTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldStoreFilesUnderCurrentUserAndSessionDirectory() {
        FilesystemTool tool = newTool();

        assertEquals(
                "Written to /notes/a.txt",
                tool.writeFile("7:22:session-a", "/notes/a.txt", "hello")
        );

        assertEquals("hello", tool.readFile("7:22:session-a", "/notes/a.txt", 0, 0));
        assertTrue(tool.readFile("7:22:session-b", "/notes/a.txt", 0, 0).startsWith("Error: File"));
        assertTrue(tool.readFile("8:22:session-a", "/notes/a.txt", 0, 0).startsWith("Error: File"));
    }

    @Test
    void shouldParseDomainAgentMemoryId() {
        FilesystemTool tool = newTool();
        String memoryId = "7:agent:car-rental-assistant:session-a";

        assertEquals(
                "Written to /report.txt",
                tool.writeFile(memoryId, "report.txt", "domain")
        );

        assertEquals("domain", tool.readFile(memoryId, "/report.txt", 0, 0));
    }

    @Test
    void shouldEditAndListSessionFiles() {
        FilesystemTool tool = newTool();
        tool.writeFile("7:22:session-a", "/draft.txt", "hello\nhello");

        assertEquals(
                "Error: String appears 2 times in file; set replace_all=true to replace all",
                tool.editFile("7:22:session-a", "/draft.txt", "hello", "hi", false)
        );
        assertEquals(
                "Edited /draft.txt (2 replacement(s))",
                tool.editFile("7:22:session-a", "/draft.txt", "hello", "hi", true)
        );

        assertTrue(tool.listFiles("7:22:session-a", "/").contains("/draft.txt"));
    }

    @Test
    void shouldRejectTraversalAndSessionRootDeletion() {
        FilesystemTool tool = newTool();

        assertEquals("Error: Path traversal is not allowed", tool.readFile("7:22:session-a", "/../secret.txt", 0, 0));
        assertEquals("Error: Invalid filesystem path", tool.readFile("7:22:session-a", "~/secret.txt", 0, 0));
        assertEquals("Error: Invalid filesystem path", tool.readFile("7:22:session-a", "\\secret.txt", 0, 0));
        assertEquals("Error: Cannot write to session root /", tool.writeFile("7:22:session-a", "/", "x"));
        assertEquals("Error: Cannot delete session root /", tool.deleteFile("7:22:session-a", "/", true));
    }

    @Test
    void shouldMoveFilesWithoutOverwritingByDefault() {
        FilesystemTool tool = newTool();
        tool.writeFile("7:22:session-a", "/a.txt", "A");
        tool.writeFile("7:22:session-a", "/b.txt", "B");

        assertEquals("Error: Destination already exists: /b.txt", tool.moveFile("7:22:session-a", "/a.txt", "/b.txt", false));
        assertEquals("Moved /a.txt to /c.txt", tool.moveFile("7:22:session-a", "/a.txt", "/c.txt", false));
        assertEquals("A", tool.readFile("7:22:session-a", "/c.txt", 0, 0));
        assertTrue(tool.readFile("7:22:session-a", "/a.txt", 0, 0).startsWith("Error: File"));
    }

    private FilesystemTool newTool() {
        return new FilesystemTool(new AssistantFileStorage(
                tempDir.resolve("assistant-files"),
                1024 * 1024
        ));
    }
}
