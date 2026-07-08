package com.h.backend.chat;

import com.h.backend.chat.infrastructure.filesystem.AssistantFileProperties;
import com.h.backend.chat.infrastructure.filesystem.AssistantFileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AssistantFileStorageSpringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues("chat.filesystem.base-dir=/tmp/h-agent/test-assistant-files");

    @Test
    void shouldCreateAssistantFileStorageBeanFromProperties() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(AssistantFileStorage.class));
        });
    }

    @Configuration
    @EnableConfigurationProperties(AssistantFileProperties.class)
    @Import(AssistantFileStorage.class)
    static class TestConfig {
    }
}
