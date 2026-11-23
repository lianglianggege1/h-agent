package com.h.otheragents.a2a.config;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.langchain4j.ObservingChatModel;
import com.h.agent.observability.langchain4j.ObservingStreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.DisabledStreamingChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.DisabledChatModel;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

@Configuration
public class ChatModelConfig {

    private static final String LANGCHAIN4J_HTTP_REQUEST_LOGGER =
            "dev.langchain4j.http.client.log.HttpRequestLogger";

    @Bean
    public ChatModel chatModel(AgentObservability observability) {
        Properties properties = settings();
        if (properties == null) return new DisabledChatModel();
        ChatModel delegate = AnthropicChatModel.builder()
                .apiKey(properties.getProperty("API_KEY"))
                .baseUrl(anthropicApiUrl(properties))
                .modelName(properties.getProperty("MODEL_NAME"))
                .maxTokens(8192)
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(true)
                .logger(LoggerFactory.getLogger(LANGCHAIN4J_HTTP_REQUEST_LOGGER))
                .build();
        return new ObservingChatModel(delegate, observability, "anthropic");
    }

    @Bean
    @ConditionalOnProperty(prefix = "other-agents.a2a.story", name = "enabled", havingValue = "true")
    public StreamingChatModel storyStreamingModel(AgentObservability observability) {
        Properties properties = settings();
        if (properties == null) return new DisabledStreamingChatModel();
        StreamingChatModel delegate = AnthropicStreamingChatModel.builder()
                .apiKey(properties.getProperty("API_KEY"))
                .baseUrl(anthropicApiUrl(properties))
                .modelName(properties.getProperty("MODEL_NAME"))
                .maxTokens(8192)
                .timeout(Duration.ofSeconds(120))
                .build();
        return new ObservingStreamingChatModel(delegate, observability, "anthropic");
    }

    private static String anthropicApiUrl(Properties properties) {
        // LangChain4j 向此地址追加 messages，SDK 基址需先加 /v1。
        return properties.getProperty("ANTHROPIC_BASE_URL", "https://api.minimaxi.com/anthropic") + "/v1";
    }

    private static Properties settings() {
        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) envPath = Path.of("../.env");
        if (!Files.exists(envPath)) return null;
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(envPath)) {
            properties.load(reader);
            return properties;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load .env file", ex);
        }
    }
}
