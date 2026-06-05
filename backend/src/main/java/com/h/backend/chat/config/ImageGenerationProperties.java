package com.h.backend.chat.config;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.DisabledStreamingChatModel;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

@ConfigurationProperties(prefix = "image-generation")
public record ImageGenerationProperties(
        LocalStorage storage
) {

    public MiniMax minimaxOrDefault() {

        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            throw new RuntimeException(".env file not found");
        }

        Properties properties = new Properties();

        try (var reader = Files.newBufferedReader(envPath)) {
            properties.load(reader);
            return new MiniMax(
                    properties.getProperty("IMAGE_BASE_URL"),
                    properties.getProperty("IMAGE_API_KEY"),
                    properties.getProperty("IMAGE_MODEL_NAME"),
                    "1:1",
                    true
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load .env file", ex);
        }


    }

    public LocalStorage storageOrDefault() {
        return storage == null ? new LocalStorage("/tmp/h-agent", "") : storage;
    }

    public record MiniMax(
            String baseUrl,
            String apiKey,
            String model,
            String aspectRatio,
            boolean promptOptimizer
    ) {
    }

    public record LocalStorage(
            String baseDir,
            String publicBaseUrl
    ) {
    }
}
