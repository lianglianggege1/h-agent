package com.h.backend.chat.infrastructure.config;

import com.h.backend.common.testsupport.CapturingLogAppender;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.log.LoggingHttpClient;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompactHttpLoggerTest {
    @Test
    void frameworkLogsOneLineJsonWithoutChangingWireBodyOrHeaderMasking() throws Exception {
        String body = "{\n  \"messages\": [ { \"text\": \"line 1\\nline 2\" } ]\n}";
        var request = HttpRequest.builder().method(HttpMethod.POST).url("https://example.com/messages")
                .addHeader("x-api-key", "secret-api-key-value").body(body).build();
        var transport = mock(HttpClient.class);
        when(transport.execute(request)).thenReturn(mock(SuccessfulHttpResponse.class));
        try (var logs = CapturingLogAppender.attach(CompactHttpLoggerTest.class)) {
            var logger = new CompactHttpLogger(LoggerFactory.getLogger(CompactHttpLoggerTest.class));
            new LoggingHttpClient(transport, true, false, logger).execute(request);
            String message = logs.appender().events().getFirst().getMessage().getFormattedMessage();
            assertFalse(message.contains("\n"));
            assertFalse(message.contains("secret-api-key-value"));
            assertTrue(message.contains("secre...ue"));
            String loggedBody = message.substring(message.indexOf("- body: ") + 8);
            var json = new ObjectMapper();
            assertEquals(json.readTree(body), json.readTree(loggedBody));
            verify(transport).execute(same(request));
            assertEquals(body, request.body());
        }
    }

    @Test
    void preservesExceptionAndItsCauseForErrorLogs() {
        var cause = new java.net.http.HttpTimeoutException("request timed out");
        var error = new RuntimeException("review failed", cause);
        try (var logs = CapturingLogAppender.attach(CompactHttpLoggerTest.class)) {
            var logger = new CompactHttpLogger(LoggerFactory.getLogger(CompactHttpLoggerTest.class));
            logger.error("LLM failed request={}", "probe", error);
            var event = logs.appender().events().getFirst();
            assertSame(error, event.getThrown());
            assertSame(cause, event.getThrown().getCause());
            assertEquals(org.apache.logging.log4j.Level.ERROR, event.getLevel());
        }
    }
}
