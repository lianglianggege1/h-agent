package com.h.backend.generation.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationPropertiesTest {
    @TempDir
    Path tempDirectory;

    @Test
    void readsMiniMaxApiKeyFromPropertiesFormatEnvFile() throws Exception {
        Files.writeString(tempDirectory.resolve(".env"), "MINIMAX_API_KEY=video-key-from-file\n");

        String apiKey = GenerationProperties.MiniMax.resolveApiKey(tempDirectory, null, null, null);

        assertEquals("video-key-from-file", apiKey);
    }

    @Test
    void configuredValueWinsOverEnvironmentAndEnvFile() throws Exception {
        Files.writeString(tempDirectory.resolve(".env"), "MINIMAX_API_KEY=file-key\n");

        String apiKey = GenerationProperties.MiniMax.resolveApiKey(
                tempDirectory, "configured-key", "minimax-environment-key", "environment-key"
        );

        assertEquals("configured-key", apiKey);
    }

    @Test
    void fallsBackToExistingApiKeyFromPropertiesFormatEnvFile() throws Exception {
        Files.writeString(tempDirectory.resolve(".env"), "API_KEY=shared-minimax-key\n");

        String apiKey = GenerationProperties.MiniMax.resolveApiKey(tempDirectory, null, null, null);

        assertEquals("shared-minimax-key", apiKey);
    }
}
