package com.h.backend.chat.config;

import com.h.backend.chat.ai.HAssistant;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.DisabledStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
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

    @Bean
    public HAssistant hAssistant(StreamingChatModel streamingChatModel) {
        return AiServices.builder(HAssistant.class)
                .streamingChatModel(streamingChatModel)
                .systemMessageProvider(memoryId -> """
                        你是 H-Agent 的 AI 助手。
                        请使用简洁、自然、友好的中文回答。
                        如果用户的问题信息不足，先给出最小可执行建议，再提示可以补充的信息。
                        """)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .build();
    }
}
