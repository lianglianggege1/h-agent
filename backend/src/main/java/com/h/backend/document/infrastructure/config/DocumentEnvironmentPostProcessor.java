package com.h.backend.document.infrastructure.config;

import com.h.agent.observability.EnvFileLoader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.*;

import java.nio.file.Path;
import java.util.*;

/** 将根目录或 backend 父目录的 .env 作为低优先级文档配置源。系统环境和显式 Spring 属性优先。 */
public class DocumentEnvironmentPostProcessor implements EnvironmentPostProcessor {
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        apply(environment, Path.of(""));
    }

    public static void apply(ConfigurableEnvironment environment, Path directory) {
        var file = EnvFileLoader.load(directory);
        Map<String, Object> values = new LinkedHashMap<>();
        for (var key : file.keySet())
            if (key.startsWith("CHAT_DOCUMENTS_")) {
                String value = EnvFileLoader.resolve(file, key);
                if (value != null)
                    values.put(
                            "chat.documents."
                                    + key.substring("CHAT_DOCUMENTS_".length())
                                            .toLowerCase(Locale.ROOT)
                                            .replace('_', '-'),
                            value);
            }
        String key = EnvFileLoader.resolve(file, "MODELBEST_API_KEY");
        if (key != null) values.putIfAbsent("chat.documents.modelbest-api-key", key);
        environment.getPropertySources().addLast(new MapPropertySource("documentDotEnv", values));
    }
}
