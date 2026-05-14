package com.h.backend.chat.config;

import dev.langchain4j.model.chat.DisabledStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

@Configuration
public class ChatModelConfig {

    @Bean
    public StreamingChatModel streamingChatModel() {

        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            return new DisabledStreamingChatModel();
        }

        Properties properties = new Properties();

        try (var reader = Files.newBufferedReader(envPath)) {
            properties.load(reader);

            return OpenAiStreamingChatModel.builder()
                    .apiKey(properties.getProperty("API_KEY"))
                    .baseUrl(properties.getProperty("BASE_URL"))
                    .modelName(properties.getProperty("MODEL_NAME"))
                    .timeout(Duration.ofSeconds(60))
                    .build();

        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load .env file", ex);
        }

    }

}
