package com.h.backend.chat.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatModelEnvironmentTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldLoadSharedModelConfigurationFromParentEnvWhenBackendIsWorkingDirectory() throws Exception {
        Path backendDirectory = Files.createDirectory(temporaryDirectory.resolve("backend"));
        Files.writeString(temporaryDirectory.resolve(".env"), """
                API_KEY=shared-key
                MODEL_NAME=MiniMax-M2.5
                ANTHROPIC_BASE_URL=https://api.minimaxi.com/anthropic
                """);

        ChatModelEnvironment environment = ChatModelEnvironment.load(backendDirectory).orElseThrow();

        assertEquals("shared-key", environment.apiKey());
        assertEquals("MiniMax-M2.5", environment.modelName());
        assertEquals("https://api.minimaxi.com/anthropic/v1", environment.baseUrl());
        assertEquals("https://api.minimaxi.com/anthropic", environment.anthropicSdkBaseUrl());
    }

    @Test
    void shouldFallbackToDefaultAnthropicSdkBaseUrlWhenEnvMissing() throws Exception {
        Path backendDirectory = Files.createDirectory(temporaryDirectory.resolve("backend"));
        Files.writeString(temporaryDirectory.resolve(".env"), """
                API_KEY=shared-key
                MODEL_NAME=MiniMax-M3
                """);

        ChatModelEnvironment environment = ChatModelEnvironment.load(backendDirectory).orElseThrow();

        assertEquals("https://api.minimaxi.com/anthropic", environment.anthropicSdkBaseUrl());
    }
}
