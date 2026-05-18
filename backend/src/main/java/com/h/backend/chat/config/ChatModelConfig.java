package com.h.backend.chat.config;

import com.h.backend.chat.ai.HAssistant;
import com.h.backend.chat.service.SystemPromptService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.DisabledStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

@Configuration
public class ChatModelConfig {

    @Autowired
    private SystemPromptService systemPromptService;

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
                // 不同用户的系统提示词不一样
                .systemMessageProvider(memoryId -> {
                    String[] parts = memoryId.toString().split(":", 3);
                    Long userId = Long.valueOf(parts[0]);
                    Long promptId = Long.valueOf(parts[1]);

                    return systemPromptService.getSystemPrompt(userId, promptId);
                })
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(new InMemoryChatMemoryStore())
                        .build())
                .build();
    }
}
