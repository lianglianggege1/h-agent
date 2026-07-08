package com.h.backend.chat.infrastructure.filesystem;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Component
public class AssistantFileStorage {

    private final Path baseDir;
    private final long maxFileSizeBytes;

    @Autowired
    public AssistantFileStorage(AssistantFileProperties properties) {
        this(Path.of(properties.getBaseDir()), properties.getMaxFileSizeBytes());
    }

    public AssistantFileStorage(Path baseDir, long maxFileSizeBytes) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public String read(String memoryId, String path, int offset, int limit) {
        try {
            ResolvedFile target = resolve(memoryId, path);
            if (!Files.exists(target.realPath()) || !Files.isRegularFile(target.realPath())) {
                return "Error: File '" + target.virtualPath() + "' not found";
            }
            if (Files.size(target.realPath()) > maxFileSizeBytes) {
                return "Error: File exceeds max readable size: " + target.virtualPath();
            }
            String content = Files.readString(target.realPath(), StandardCharsets.UTF_8);
            if (content.isEmpty()) {
                return "";
            }
            String[] lines = content.split("\n", -1);
            int start = Math.max(0, offset);
            if (start >= lines.length) {
                return "Error: Line offset " + offset + " exceeds file length (" + lines.length + " lines)";
            }
            int end = limit > 0 ? Math.min(start + limit, lines.length) : lines.length;
            return String.join("\n", java.util.Arrays.copyOfRange(lines, start, end));
        } catch (IOException | IllegalArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
    }

    public String write(String memoryId, String path, String content) {
        try {
            ResolvedFile target = resolve(memoryId, path);
            if (target.sessionRoot()) {
                return "Error: Cannot write to session root /";
            }
            if (Files.exists(target.realPath())) {
                return "Error: Cannot write to " + target.virtualPath() + " because it already exists";
            }
            if (target.realPath().getParent() != null) {
                Files.createDirectories(target.realPath().getParent());
            }
            Files.writeString(target.realPath(), content == null ? "" : content, StandardCharsets.UTF_8);
            return "Written to " + target.virtualPath();
        } catch (IOException | IllegalArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
    }

    public String edit(String memoryId, String path, String oldString, String newString, boolean replaceAll) {
        try {
            ResolvedFile target = resolve(memoryId, path);
            if (!Files.exists(target.realPath()) || !Files.isRegularFile(target.realPath())) {
                return "Error: File '" + target.virtualPath() + "' not found";
            }
            String content = Files.readString(target.realPath(), StandardCharsets.UTF_8);
            String normalizedOld = normalizeNewlines(oldString);
            String normalizedNew = normalizeNewlines(newString);
            int occurrences = countOccurrences(content, normalizedOld);
            if (occurrences == 0) {
                return "Error: String not found in file: '" + normalizedOld + "'";
            }
            if (occurrences > 1 && !replaceAll) {
                return "Error: String appears " + occurrences + " times in file; set replace_all=true to replace all";
            }
            String updated = replaceAll
                    ? content.replace(normalizedOld, normalizedNew)
                    : replaceFirst(content, normalizedOld, normalizedNew);
            Files.writeString(target.realPath(), updated, StandardCharsets.UTF_8);
            return "Edited " + target.virtualPath() + " (" + occurrences + " replacement(s))";
        } catch (IOException | IllegalArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
    }

    public String list(String memoryId, String path) {
        try {
            ResolvedFile target = resolve(memoryId, path);
            if (!Files.exists(target.realPath()) || !Files.isDirectory(target.realPath())) {
                return "Empty or not a directory: " + target.virtualPath();
            }
            List<String> entries = new ArrayList<>();
            try (Stream<Path> stream = Files.list(target.realPath())) {
                stream.sorted().forEach(entry -> entries.add(formatFileInfo(target.sessionRootPath(), entry)));
            }
            return entries.isEmpty() ? "Empty or not a directory: " + target.virtualPath() : String.join("\n", entries);
        } catch (IOException | IllegalArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
    }

    public String delete(String memoryId, String path, boolean recursive) {
        try {
            ResolvedFile target = resolve(memoryId, path);
            if (target.sessionRoot()) {
                return "Error: Cannot delete session root /";
            }
            if (!Files.exists(target.realPath())) {
                return "Deleted " + target.virtualPath();
            }
            if (Files.isDirectory(target.realPath()) && !recursive) {
                return "Error: Directory deletion requires recursive=true";
            }
            if (Files.isDirectory(target.realPath())) {
                try (Stream<Path> walk = Files.walk(target.realPath())) {
                    List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
                    for (Path entry : paths) {
                        Files.deleteIfExists(entry);
                    }
                }
            } else {
                Files.deleteIfExists(target.realPath());
            }
            return "Deleted " + target.virtualPath();
        } catch (IOException | IllegalArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
    }

    public String move(String memoryId, String fromPath, String toPath, boolean overwrite) {
        try {
            ResolvedFile from = resolve(memoryId, fromPath);
            ResolvedFile to = resolve(memoryId, toPath);
            if (from.sessionRoot() || to.sessionRoot()) {
                return "Error: Cannot move session root /";
            }
            if (!Files.exists(from.realPath())) {
                return "Error: Source does not exist: " + from.virtualPath();
            }
            if (Files.exists(to.realPath()) && !overwrite) {
                return "Error: Destination already exists: " + to.virtualPath();
            }
            if (to.realPath().getParent() != null) {
                Files.createDirectories(to.realPath().getParent());
            }
            if (overwrite) {
                Files.move(from.realPath(), to.realPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(from.realPath(), to.realPath());
            }
            return "Moved " + from.virtualPath() + " to " + to.virtualPath();
        } catch (IOException | IllegalArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
    }

    public AssistantSessionFile readSessionFile(String memoryId, String path) {
        try {
            ResolvedFile target = resolve(memoryId, path);
            if (!Files.exists(target.realPath()) || !Files.isRegularFile(target.realPath())) {
                return AssistantSessionFile.error("File '" + target.virtualPath() + "' not found");
            }
            if (Files.size(target.realPath()) > maxFileSizeBytes) {
                return AssistantSessionFile.error("File exceeds max readable size: " + target.virtualPath());
            }
            String fileName = target.realPath().getFileName() == null ? "file" : target.realPath().getFileName().toString();
            String mimeType = Files.probeContentType(target.realPath());
            return AssistantSessionFile.ok(target.virtualPath(), fileName, mimeType, Files.readAllBytes(target.realPath()));
        } catch (IOException | IllegalArgumentException ex) {
            return AssistantSessionFile.error(ex.getMessage());
        }
    }

    private ResolvedFile resolve(String memoryId, String path) {
        AssistantFileScope scope = parseScope(memoryId);
        Path sessionRoot = baseDir
                .resolve(String.valueOf(scope.userId()))
                .resolve(scope.sessionId())
                .normalize();
        String virtualPath = normalizeVirtualPath(path);
        String relative = virtualPath.equals("/") ? "" : virtualPath.substring(1);
        Path realPath = relative.isBlank() ? sessionRoot : sessionRoot.resolve(relative).normalize();
        if (!realPath.startsWith(sessionRoot)) {
            throw new IllegalArgumentException("Path escapes session root");
        }
        return new ResolvedFile(sessionRoot, realPath, virtualPath, relative.isBlank());
    }

    private AssistantFileScope parseScope(String memoryId) {
        String[] parts = memoryId == null ? new String[0] : memoryId.split(":", 4);
        if (parts.length == 3) {
            return new AssistantFileScope(parseUserId(parts[0]), sanitizeSegment(parts[2]));
        }
        if (parts.length == 4 && "agent".equals(parts[1])) {
            return new AssistantFileScope(parseUserId(parts[0]), sanitizeSegment(parts[3]));
        }
        throw new IllegalArgumentException("Invalid chat memory id");
    }

    private Long parseUserId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid chat memory id");
        }
    }

    private String sanitizeSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Invalid chat memory id");
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String normalizeVirtualPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        String normalized = path.strip();
        if (normalized.startsWith("~") || normalized.contains("\\")) {
            throw new IllegalArgumentException("Invalid filesystem path");
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        normalized = normalized.replaceAll("/+", "/");
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Path traversal is not allowed");
            }
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String formatFileInfo(Path sessionRoot, Path entry) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
            String virtualPath = virtualize(sessionRoot, entry);
            String modifiedAt = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()).toString();
            if (attrs.isDirectory()) {
                return "[DIR]  " + virtualPath + "/ " + modifiedAt;
            }
            return "[FILE] " + virtualPath + " (" + attrs.size() + " bytes) " + modifiedAt;
        } catch (IOException ex) {
            return "[UNKNOWN] " + virtualize(sessionRoot, entry);
        }
    }

    private String virtualize(Path sessionRoot, Path file) {
        Path relative = sessionRoot.relativize(file.toAbsolutePath().normalize());
        String suffix = relative.toString().replace('\\', '/');
        return suffix.isBlank() ? "/" : "/" + suffix;
    }

    private String normalizeNewlines(String value) {
        return (value == null ? "" : value).replace("\r\n", "\n").replace("\r", "\n");
    }

    private int countOccurrences(String content, String oldString) {
        if (oldString.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(oldString, idx)) >= 0) {
            count++;
            idx += oldString.length();
        }
        return count;
    }

    private String replaceFirst(String content, String oldString, String newString) {
        int idx = content.indexOf(oldString);
        return content.substring(0, idx) + newString + content.substring(idx + oldString.length());
    }

    private record AssistantFileScope(Long userId, String sessionId) {
    }

    private record ResolvedFile(Path sessionRootPath, Path realPath, String virtualPath, boolean sessionRoot) {
    }

    public record AssistantSessionFile(
            boolean success,
            String error,
            String virtualPath,
            String fileName,
            String mimeType,
            byte[] content
    ) {

        static AssistantSessionFile ok(String virtualPath, String fileName, String mimeType, byte[] content) {
            return new AssistantSessionFile(true, null, virtualPath, fileName, mimeType, content);
        }

        static AssistantSessionFile error(String error) {
            return new AssistantSessionFile(false, error, null, null, null, null);
        }
    }
}
