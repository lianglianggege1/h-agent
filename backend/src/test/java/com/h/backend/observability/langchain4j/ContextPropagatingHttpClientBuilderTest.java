package com.h.backend.observability.langchain4j;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.log.LoggingHttpClient;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContextPropagatingHttpClientBuilderTest {
    @Test
    void restoresContextBeforeHttpResponseLoggingAndEverySseCallback() throws Exception {
        var transport = mock(HttpClient.class);
        var builder = mock(HttpClientBuilder.class);
        when(builder.build()).thenReturn(transport);
        var delivered = new AtomicReference<ServerSentEventListener>();
        doAnswer(call -> {
            delivered.set(call.getArgument(2));
            return null;
        }).when(transport).execute(any(), any(ServerSentEventParser.class), any());
        var seen = new ArrayList<String>();
        var logger = mock(Logger.class);
        doAnswer(call -> {
            seen.add(MDC.get("traceId"));
            return null;
        }).when(logger).info(anyString(), any(), any(), any());
        var client = new LoggingHttpClient(new ContextPropagatingHttpClientBuilder(builder).build(), false, true, logger);
        var event = new ServerSentEvent("message", "data");
        var eventContext = mock(ServerSentEventContext.class);
        var error = new IllegalStateException("transport failure");
        try {
            MDC.put("traceId", "http-request");
            client.execute(HttpRequest.builder().method(HttpMethod.GET).url("http://test.local").build(),
                    new ServerSentEventListener() {
                        @Override
                        public void onOpen(SuccessfulHttpResponse response) {
                            seen.add(MDC.get("traceId"));
                        }

                        @Override
                        public void onEvent(ServerSentEvent actual, ServerSentEventContext context) {
                            assertSame(event, actual);
                            assertSame(eventContext, context);
                            seen.add(MDC.get("traceId"));
                        }

                        @Override
                        public void onError(Throwable actual) {
                            assertSame(error, actual);
                            seen.add(MDC.get("traceId"));
                            throw new IllegalStateException("callback failure");
                        }

                        @Override
                        public void onClose() {
                            seen.add(MDC.get("traceId"));
                        }
                    });
        } finally {
            MDC.clear();
        }
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                MDC.put("traceId", "worker");
                try {
                    delivered.get().onOpen(SuccessfulHttpResponse.builder().statusCode(200).build());
                    delivered.get().onEvent(event, eventContext);
                    assertThrows(IllegalStateException.class, () -> delivered.get().onError(error));
                    delivered.get().onClose();
                    assertEquals("worker", MDC.get("traceId"));
                } finally {
                    MDC.clear();
                }
            }).get();
        }
        assertEquals(List.of("http-request", "http-request", "http-request", "http-request", "http-request"), seen);
    }
}
