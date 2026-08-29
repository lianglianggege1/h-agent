package com.h.agent.observability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Shared .env loading for all applications: reads ./.env then ../.env.
 * Values already present in the environment take precedence over file values.
 */
public final class EnvFileLoader {

    private EnvFileLoader() {
    }

    public static Map<String, String> load() {
        return load(Path.of("").toAbsolutePath());
    }

    public static Map<String, String> load(Path workingDirectory) {
        Path normalized = workingDirectory.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        Map<String, String> values = new LinkedHashMap<>();
        for (Path candidate : parent == null
                ? new Path[]{normalized.resolve(".env")}
                : new Path[]{normalized.resolve(".env"), parent.resolve(".env")}) {
            if (Files.isRegularFile(candidate)) {
                values.putAll(read(candidate));
                break;
            }
        }
        return values;
    }

    private static Map<String, String> read(Path path) {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException ignored) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            values.put(name, properties.getProperty(name));
        }
        return values;
    }

    public static String resolve(Map<String, String> fileValues, String key) {
        String fromEnvironment = System.getenv(key);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        String fromFile = fileValues.get(key);
        if (fromFile == null || fromFile.isBlank()) {
            return null;
        }
        return fromFile.startsWith("\"") && fromFile.endsWith("\"") && fromFile.length() > 1
                ? fromFile.substring(1, fromFile.length() - 1)
                : fromFile;
    }
}
