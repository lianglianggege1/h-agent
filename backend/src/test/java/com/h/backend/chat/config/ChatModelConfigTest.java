package com.h.backend.chat.infrastructure.config;

import com.h.agent.observability.NoopAgentObservability;
import com.h.agent.observability.langchain4j.ObservingStreamingChatModel;
import dev.langchain4j.http.client.log.LoggingHttpClient;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatModelConfigTest {

    private static final Path ENV_PATH = Path.of(".env");

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(ENV_PATH);
    }

    @Test
    void streamingChatModelShouldEnableHttpRequestAndResponseLogging() throws Exception {
        Files.writeString(ENV_PATH, """
                API_KEY=test-key
                BASE_URL=https://example.com/v1
                MODEL_NAME=test-model
                """);

        ChatModelConfig config = new ChatModelConfig();

        StreamingChatModel streamingChatModel = config.streamingChatModel(NoopAgentObservability.getInstance());
        ObservingStreamingChatModel observingModel = assertInstanceOf(ObservingStreamingChatModel.class, streamingChatModel);
        AnthropicStreamingChatModel model = assertInstanceOf(AnthropicStreamingChatModel.class, observingModel.delegate());

        Object client = readField(model, "client");
        Object httpClient = readField(client, "httpClient");
        LoggingHttpClient loggingHttpClient = assertInstanceOf(LoggingHttpClient.class, httpClient);

        assertTrue((Boolean) readField(loggingHttpClient, "logRequests"));
        assertTrue((Boolean) readField(loggingHttpClient, "logResponses"));
        assertEquals(
                "dev.langchain4j.http.client.log.HttpRequestLogger",
                readField(readField(loggingHttpClient, "log"), "name")
        );
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
