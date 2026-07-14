package com.h.backend.generation.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

@ConfigurationProperties(prefix = "generation")
public class GenerationProperties {
    private final Polling polling = new Polling();
    private final MiniMax minimax = new MiniMax();
    private final Download download = new Download();

    public Polling getPolling() { return polling; }
    public MiniMax getMinimax() { return minimax; }
    public Download getDownload() { return download; }

    public static class Polling {
        private boolean enabled = true;
        private Duration fixedDelay = Duration.ofSeconds(5);
        private int batchSize = 20;
        private Duration queueingDelay = Duration.ofSeconds(10);
        private Duration processingDelay = Duration.ofSeconds(15);
        private Duration retryDelay = Duration.ofSeconds(30);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getFixedDelay() { return fixedDelay; }
        public void setFixedDelay(Duration fixedDelay) { this.fixedDelay = fixedDelay; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public Duration getQueueingDelay() { return queueingDelay; }
        public void setQueueingDelay(Duration queueingDelay) { this.queueingDelay = queueingDelay; }
        public Duration getProcessingDelay() { return processingDelay; }
        public void setProcessingDelay(Duration processingDelay) { this.processingDelay = processingDelay; }
        public Duration getRetryDelay() { return retryDelay; }
        public void setRetryDelay(Duration retryDelay) { this.retryDelay = retryDelay; }
    }

    public static class MiniMax {
        private String baseUrl = "https://api.minimaxi.com";
        private String apiKey;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() {
            return resolveApiKey(
                    Path.of("").toAbsolutePath().normalize(),
                    apiKey,
                    System.getenv("MINIMAX_API_KEY"),
                    System.getenv("API_KEY")
            );
        }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        static String resolveApiKey(
                Path startingDirectory,
                String configuredApiKey,
                String minimaxEnvironmentApiKey,
                String defaultEnvironmentApiKey
        ) {
            if (hasText(configuredApiKey)) {
                return configuredApiKey;
            }
            if (hasText(minimaxEnvironmentApiKey)) {
                return minimaxEnvironmentApiKey;
            }
            if (hasText(defaultEnvironmentApiKey)) {
                return defaultEnvironmentApiKey;
            }
            for (Path envFile : envFileCandidates(startingDirectory)) {
                String apiKey = loadApiKey(envFile);
                if (hasText(apiKey)) {
                    return apiKey;
                }
            }
            return null;
        }

        private static List<Path> envFileCandidates(Path startingDirectory) {
            Path normalizedDirectory = startingDirectory.toAbsolutePath().normalize();
            Path parent = normalizedDirectory.getParent();
            return parent == null
                    ? List.of(normalizedDirectory.resolve(".env"))
                    : List.of(normalizedDirectory.resolve(".env"), parent.resolve(".env"));
        }

        private static String loadApiKey(Path envFile) {
            if (!Files.exists(envFile)) {
                return null;
            }
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(envFile)) {
                properties.load(reader);
                String minimaxApiKey = properties.getProperty("MINIMAX_API_KEY");
                return hasText(minimaxApiKey) ? minimaxApiKey : properties.getProperty("API_KEY");
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to load .env file: " + envFile, exception);
            }
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    public static class Download {
        private long maxFileSize = 524_288_000L;

        public long getMaxFileSize() { return maxFileSize; }
        public void setMaxFileSize(long maxFileSize) { this.maxFileSize = maxFileSize; }
    }
}
