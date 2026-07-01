package com.h.backend.chat.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@ConfigurationProperties(prefix = "image-generation")
public class ImageGenerationProperties {

    private MiniMax minimax;
    private LocalStorage storage;

    public ImageGenerationProperties() {
    }

    public ImageGenerationProperties(MiniMax minimax, LocalStorage storage) {
        this.minimax = minimax;
        this.storage = storage;
    }

    public MiniMax getMinimax() {
        return minimax;
    }

    public void setMinimax(MiniMax minimax) {
        this.minimax = minimax;
    }

    public LocalStorage getStorage() {
        return storage;
    }

    public void setStorage(LocalStorage storage) {
        this.storage = storage;
    }

    public MiniMax minimaxOrDefault() {
        if (minimax != null) {
            return minimax;
        }
        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            return new MiniMax(
                    "https://api.minimaxi.com",
                    "",
                    "image-01",
                    "1:1",
                    true,
                    1,
                    180
            );
        }

        Properties properties = new Properties();

        try (var reader = Files.newBufferedReader(envPath)) {
            properties.load(reader);
            return new MiniMax(
                    properties.getProperty("IMAGE_BASE_URL", "https://api.minimaxi.com"),
                    properties.getProperty("IMAGE_API_KEY", ""),
                    properties.getProperty("IMAGE_MODEL_NAME", "image-01"),
                    properties.getProperty("IMAGE_ASPECT_RATIO", "1:1"),
                    Boolean.parseBoolean(properties.getProperty("IMAGE_PROMPT_OPTIMIZER", "true")),
                    parseImageCount(properties.getProperty("IMAGE_COUNT")),
                    parsePositiveInt(properties.getProperty("IMAGE_REQUEST_TIMEOUT_SECONDS"), 180)
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load .env file", ex);
        }
    }

    public LocalStorage storageOrDefault() {
        if (storage != null) {
            return storage;
        }
        return new LocalStorage("/tmp/h-agent", "");
    }

    public static class MiniMax {
        private String baseUrl;
        private String apiKey;
        private String model;
        private String aspectRatio;
        private boolean promptOptimizer;
        private int n;
        private int requestTimeoutSeconds;

        public MiniMax() {
            this("https://api.minimaxi.com", "", "image-01", "1:1", true, 1, 180);
        }

        public MiniMax(
                String baseUrl,
                String apiKey,
                String model,
                String aspectRatio,
                boolean promptOptimizer
        ) {
            this(baseUrl, apiKey, model, aspectRatio, promptOptimizer, 1, 180);
        }

        public MiniMax(
                String baseUrl,
                String apiKey,
                String model,
                String aspectRatio,
                boolean promptOptimizer,
                int n
        ) {
            this(baseUrl, apiKey, model, aspectRatio, promptOptimizer, n, 180);
        }

        public MiniMax(
                String baseUrl,
                String apiKey,
                String model,
                String aspectRatio,
                boolean promptOptimizer,
                int n,
                int requestTimeoutSeconds
        ) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.aspectRatio = aspectRatio;
            this.promptOptimizer = promptOptimizer;
            this.n = Math.max(1, n);
            this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
        }

        public String baseUrl() {
            return baseUrl;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String apiKey() {
            return apiKey;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String model() {
            return model;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String aspectRatio() {
            return aspectRatio;
        }

        public String getAspectRatio() {
            return aspectRatio;
        }

        public void setAspectRatio(String aspectRatio) {
            this.aspectRatio = aspectRatio;
        }

        public boolean promptOptimizer() {
            return promptOptimizer;
        }

        public boolean isPromptOptimizer() {
            return promptOptimizer;
        }

        public void setPromptOptimizer(boolean promptOptimizer) {
            this.promptOptimizer = promptOptimizer;
        }

        public int n() {
            return n;
        }

        public int getN() {
            return n;
        }

        public void setN(int n) {
            this.n = Math.max(1, n);
        }

        public int requestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
        }
    }

    public record LocalStorage(
            String baseDir,
            String publicBaseUrl
    ) {
    }

    private static int parseImageCount(String value) {
        return parsePositiveInt(value, 1);
    }

    private static int parsePositiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
