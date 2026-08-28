package com.h.backend.skill.domain;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record SkillFileSet(Map<String, byte[]> files) {

    public SkillFileSet {
        files = Map.copyOf(files);
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IllegalArgumentException("Skill 文件集包含空路径或空内容");
            }
        }
    }

    public static SkillFileSet of(Map<String, byte[]> files) {
        return new SkillFileSet(files);
    }

    public byte[] get(String path) {
        return files.get(path);
    }

    public String requireText(String path) {
        byte[] content = files.get(path);
        if (content == null) {
            return null;
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    public Set<String> paths() {
        return files.keySet();
    }

    public List<String> sortedPaths() {
        return files.keySet().stream()
                .sorted(Comparator.comparing(path -> path.getBytes(StandardCharsets.UTF_8),
                        Arrays::compare))
                .toList();
    }

    public long totalBytes() {
        return files.values().stream().mapToLong(bytes -> bytes == null ? 0 : bytes.length).sum();
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }
}
