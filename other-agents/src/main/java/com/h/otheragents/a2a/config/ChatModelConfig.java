package com.h.otheragents.a2a.config;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.langchain4j.ObservingChatModel;
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
        // 与 backend ChatModelEnvironment 一致：先读工作目录 .env，再读父目录（服务可从模块目录启动）
        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            envPath = Path.of("../.env");
        }
        if (!Files.exists(envPath)) {
            return new DisabledChatModel();
        }

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(envPath)) {
            properties.load(reader);
            // LangChain4j 只拼接 messages 路径，base URL 需带 /v1；ANTHROPIC_BASE_URL 为不带 /v1 的 SDK 基址。
            String sdkBaseUrl = properties.getProperty("ANTHROPIC_BASE_URL", "https://api.minimaxi.com/anthropic");
            ChatModel delegate = AnthropicChatModel.builder()
                    .apiKey(properties.getProperty("API_KEY"))
                    .baseUrl(sdkBaseUrl + "/v1")
                    .modelName(properties.getProperty("MODEL_NAME"))
                    .maxTokens(8192)
                    .timeout(Duration.ofSeconds(120))
                    .logRequests(true)
                    .logResponses(true)
                    .logger(LoggerFactory.getLogger(LANGCHAIN4J_HTTP_REQUEST_LOGGER))
                    .build();
            return new ObservingChatModel(delegate, observability, "anthropic");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load .env file", ex);
        }
    }
}
