package com.h.backend.chat.infrastructure.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * LangChain4j 与 AgentScope 共用的大模型配置。
 *
 * <p>开发时既可能从仓库根目录启动，也可能从 {@code backend/} 启动，因此先读当前目录
 * 的 {@code .env}，不存在时再读父目录。两个运行时必须经过同一入口，避免模型名或密钥
 * 漂移。</p>
 */
public record ChatModelEnvironment(String apiKey, String modelName, String baseUrl) {

    public static final String MINIMAX_ANTHROPIC_BASE_URL =
            "https://api.minimaxi.com/anthropic/v1";

    public static Optional<ChatModelEnvironment> load(Path workingDirectory) {
        Path normalized = workingDirectory.toAbsolutePath().normalize();
        List<Path> candidates = normalized.getParent() == null
                ? List.of(normalized.resolve(".env"))
                : List.of(normalized.resolve(".env"), normalized.getParent().resolve(".env"));
        return candidates.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .map(ChatModelEnvironment::read);
    }

    private static ChatModelEnvironment read(Path envPath) {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(envPath)) {
            properties.load(reader);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load .env file: " + envPath, ex);
        }

        String apiKey = required(properties, "API_KEY", envPath);
        String modelName = required(properties, "MODEL_NAME", envPath);
        return new ChatModelEnvironment(apiKey, modelName, MINIMAX_ANTHROPIC_BASE_URL);
    }

    private static String required(Properties properties, String key, Path envPath) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is missing in " + envPath);
        }
        return value.trim();
    }
}
