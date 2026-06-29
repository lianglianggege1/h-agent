package com.h.backend.chat.config;

import com.h.backend.chat.ai.HAssistant;
import com.h.backend.chat.memory.RedisChatMemoryStore;
import com.h.backend.chat.service.SystemPromptService;
import com.h.backend.chat.tools.HToolArgumentsErrorHandler;
import com.h.backend.chat.tools.HToolExecutionErrorHandler;
import com.h.backend.chat.tools.ImageGenerationTool;
import com.h.backend.chat.tools.impl.ToolWithP;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.DisabledChatModel;
import dev.langchain4j.model.chat.DisabledStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.search.simple.SimpleToolSearchStrategy;
import jakarta.annotation.Resource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

@Configuration
@EnableConfigurationProperties(ImageGenerationProperties.class)
public class ChatModelConfig {

    private static final String LANGCHAIN4J_HTTP_REQUEST_LOGGER =
            "dev.langchain4j.http.client.log.HttpRequestLogger";

    @Resource
    private SystemPromptService systemPromptService;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ToolWithP toolWithP;

    @Resource
    private ImageGenerationTool imageGenerationTool;

    @Resource
    private HToolArgumentsErrorHandler hToolArgumentsErrorHandler;

    @Resource
    private HToolExecutionErrorHandler hToolExecutionErrorHandler;

    @Bean
    public StreamingChatModel streamingChatModel() {

        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            return new DisabledStreamingChatModel();
        }

        Properties properties = new Properties();

        try (var reader = Files.newBufferedReader(envPath)) {
            properties.load(reader);
            return AnthropicStreamingChatModel.builder()
                    .apiKey(properties.getProperty("API_KEY"))
                    .baseUrl("https://api.minimaxi.com/anthropic/v1")
                    .modelName(properties.getProperty("MODEL_NAME"))
                    .thinkingType("enabled")
                    .thinkingBudgetTokens(1024)
                    .returnThinking(true)
                    .timeout(Duration.ofSeconds(60))
                    .logRequests(true)
                    .logResponses(true)
                    .logger(LoggerFactory.getLogger(LANGCHAIN4J_HTTP_REQUEST_LOGGER))
                    .build();

        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load .env file", ex);
        }
    }

    @Bean
    public ChatModel chatModel() {
        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            return new DisabledChatModel();
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(envPath)) {
            properties.load(reader);
            return AnthropicChatModel.builder()
                    .apiKey(properties.getProperty("API_KEY"))
                    .baseUrl("https://api.minimaxi.com/anthropic/v1")
                    .modelName(properties.getProperty("MODEL_NAME"))
                    .maxTokens(8192)
                    .timeout(Duration.ofSeconds(60))
                    .logRequests(true)
                    .logResponses(true)
                    .logger(LoggerFactory.getLogger(LANGCHAIN4J_HTTP_REQUEST_LOGGER))
                    .build();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load .env file", ex);
        }
    }

    @Bean
    public HAssistant hAssistant(StreamingChatModel streamingChatModel,
                                 RetrievalAugmentor knowledgeRetrievalAugmentor) {
        return AiServices.builder(HAssistant.class)
                .streamingChatModel(streamingChatModel)
//                .retrievalAugmentor(knowledgeRetrievalAugmentor)
                // 不同用户的系统提示词不一样
                .systemMessageProvider(memoryId -> {
                    String[] parts = memoryId.toString().split(":", 3);
                    Long userId = Long.valueOf(parts[0]);
                    Long promptId = Long.valueOf(parts[1]);

                    return systemPromptService.getSystemPrompt(userId, promptId);
                })
                .tools(toolWithP, imageGenerationTool)
//                .toolSearchStrategy(SimpleToolSearchStrategy.builder().build())
                .toolArgumentsErrorHandler(hToolArgumentsErrorHandler)
                .toolExecutionErrorHandler(hToolExecutionErrorHandler)
                .executeToolsConcurrently() // 并发调用工具
                // 记忆模块提供者
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .build();
    }
}
