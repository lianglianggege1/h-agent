package com.h.backend.chat.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MiniMax API 配置绑定测试（计划 §10 任务 5：本地资源存储配置已随本地文件存储实现删除）。
 */
class ImageGenerationPropertiesTest {

    @Test
    void shouldBindMiniMaxProperties() {
        Map<String, String> values = Map.of(
                "image-generation.minimax.base-url", "https://api.example.com",
                "image-generation.minimax.api-key", "test-key",
                "image-generation.minimax.model", "image-01",
                "image-generation.minimax.aspect-ratio", "16:9",
                "image-generation.minimax.prompt-optimizer", "true",
                "image-generation.minimax.n", "3",
                "image-generation.minimax.request-timeout-seconds", "180"
        );

        ImageGenerationProperties properties = new Binder(new MapConfigurationPropertySource(values))
                .bind("image-generation", ImageGenerationProperties.class)
                .orElseThrow(IllegalStateException::new);

        ImageGenerationProperties.MiniMax minimax = properties.minimaxOrDefault();
        assertEquals("https://api.example.com", minimax.baseUrl());
        assertEquals("test-key", minimax.apiKey());
        assertEquals("image-01", minimax.model());
        assertEquals("16:9", minimax.aspectRatio());
        assertEquals(3, minimax.n());
        assertEquals(180, minimax.requestTimeoutSeconds());
    }
}
