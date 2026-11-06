package com.h.backend.document.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.*;
import java.util.Map;

/** 使用合成密钥校验配置绑定和优先级，测试输出禁止包含凭据。 */
class DocumentEnvironmentTest {
    @Test
    void rootAndBackendStartupBindAllDocumentOptions(@TempDir Path root) throws Exception {
        Files.writeString(
                root.resolve(".env"),
                "MODELBEST_API_KEY=\"synthetic-local-secret\"\n"
                        + "CHAT_DOCUMENTS_THRESHOLD=0.83\nCHAT_DOCUMENTS_CLOUD_VALIDATED=false\n"
                        + "CHAT_DOCUMENTS_MAX_PAGES=17\n"
                        + "CHAT_DOCUMENTS_SOFFICE_COMMAND=/local/soffice\n");
        Path backend = Files.createDirectories(root.resolve("backend"));
        var env = new StandardEnvironment();
        DocumentEnvironmentPostProcessor.apply(env, backend);
        var properties = Binder.get(env).bind("chat.documents", DocumentProperties.class).get();
        assertEquals("synthetic-local-secret", properties.getModelbestApiKey());
        assertEquals(.83, properties.getThreshold());
        assertEquals(17, properties.getMaxPages());
        assertEquals("/local/soffice", properties.getSofficeCommand());
        assertFalse(properties.toString().contains("synthetic-local-secret"));
        assertTrue(
                new com.h.backend.document.infrastructure.provider.ModelBestDocumentClient(
                                properties, new tools.jackson.databind.ObjectMapper())
                        .available());
        env.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "explicit",
                                Map.of(
                                        "chat.documents.threshold",
                                        "0.97",
                                        "chat.documents.modelbest-api-key",
                                        "synthetic-override")));
        var override = Binder.get(env).bind("chat.documents", DocumentProperties.class).get();
        assertEquals(.97, override.getThreshold());
        assertEquals("synthetic-override", override.getModelbestApiKey());
    }
}
