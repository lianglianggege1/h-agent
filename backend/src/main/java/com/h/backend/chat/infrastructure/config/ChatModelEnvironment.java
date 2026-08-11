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
 *
 * <p>LangChain4j 的 {@code AnthropicChatModel} 默认 base URL 为
 * {@code https://api.anthropic.com/v1/}，内部只拼接 {@code messages} 路径，因此
 * {@link #baseUrl()} 需要带 {@code /v1} 后缀。而 AgentScope 的
 * {@code AnthropicChatModel} 底层是官方 Anthropic SDK，SDK 会自动拼接
 * {@code /v1/messages}，所以 {@link #anthropicSdkBaseUrl()} 不能带 {@code /v1}，
 * 否则 URL 会变成 {@code .../anthropic/v1/v1/messages} 导致 404。</p>
 */
public record ChatModelEnvironment(String apiKey, String modelName, String baseUrl, String anthropicSdkBaseUrl) {

    public static final String MINIMAX_ANTHROPIC_BASE_URL =
            "https://api.minimaxi.com/anthropic/v1";

    public static final String MINIMAX_ANTHROPIC_SDK_BASE_URL =
            "https://api.minimaxi.com/anthropic";

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
        String anthropicSdkBaseUrl = optional(properties, "ANTHROPIC_BASE_URL", MINIMAX_ANTHROPIC_SDK_BASE_URL);
        return new ChatModelEnvironment(apiKey, modelName, MINIMAX_ANTHROPIC_BASE_URL, anthropicSdkBaseUrl);
    }

    private static String required(Properties properties, String key, Path envPath) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is missing in " + envPath);
        }
        return value.trim();
    }

    private static String optional(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
