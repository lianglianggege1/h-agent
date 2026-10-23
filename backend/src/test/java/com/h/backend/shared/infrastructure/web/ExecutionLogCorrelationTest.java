package com.h.backend.shared.infrastructure.web;

import com.h.agent.observability.AgentObservabilityConfig;
import com.h.agent.observability.AgentObservabilityTesting;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.langchain4j.ObservingChatModel;
import com.h.agent.observability.langchain4j.ObservingStreamingChatModel;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.backend.chat.infrastructure.config.ChatStreamAsyncConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.apache.logging.log4j.core.impl.ContextDataInjectorFactory;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionLogCorrelationTest {
    // The same injector Log4j uses when capturing an event before AsyncAppender queues it.
    private static String logValue(String key) {
        return ContextDataInjectorFactory.createInjector()
                .injectContextData(List.of(), new SortedArrayStringMap()).getValue(key);
    }

    @Test
    void chatVirtualThreadsCaptureRequestIdsAndIsolateConcurrentTasks() throws Exception {
        try (var executor = new ChatStreamAsyncConfig().chatStreamExecutor()) {
            var gate = new CountDownLatch(1);
            MDC.put("traceId", "request-one");
            MDC.put("spanId", "span-one");
            var first = executor.submit(() -> {
                gate.await();
                assertTrue(Thread.currentThread().isVirtual());
                return logValue("traceId");
            });
            MDC.put("traceId", "request-two");
            var second = executor.submit(() -> logValue("traceId"));
            gate.countDown();
            assertEquals("request-one", first.get());
            assertEquals("request-two", second.get());
            MDC.clear();
            var generated = executor.submit(() -> Map.of(
                    "trace", logValue("traceId"), "span", logValue("spanId"))).get();
            assertTrue(generated.get("trace").matches("[0-9a-f]{32}"));
            assertTrue(generated.get("span").matches("[0-9a-f]{16}"));
            assertNull(logValue("traceId"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void failedTaskRestoresWorkerMdcAndUnsampledOtelContext() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor();
             var provider = SdkTracerProvider.builder()
                     .setSampler(Sampler.alwaysOff()).build()) {
            var span = provider.get("test").spanBuilder("unsampled").startSpan();
            Runnable task;
            try (var scope = span.makeCurrent()) {
                task = ExecutionLogContext.wrap(() -> {
                    assertEquals(span.getSpanContext().getTraceId(), logValue("traceId"));
                    assertEquals(span.getSpanContext().getSpanId(), logValue("spanId"));
                    throw new IllegalStateException("expected");
                });
            }
            executor.submit(() -> {
                MDC.put("traceId", "worker");
                try {
                    assertThrows(IllegalStateException.class, task::run);
                    assertEquals("worker", logValue("traceId"));
                    assertFalse(Span.current().getSpanContext().isValid());
                } finally {
                    MDC.clear();
                }
            }).get();
            span.end();
        }
    }

    @Test
    void modelRequestAndStreamingCallbacksUseGenerationIds() throws Exception {
        var observability = AgentObservabilityTesting.build(
                AgentObservabilityConfig.builder().rootRatio(1).build(), InMemorySpanExporter.create());
        try (var worker = Executors.newSingleThreadExecutor()) {
            var root = observability.start(new AgentExecutionStart(
                    "agent.run", "session", 1L, "agent", "session", "CHAT", "run",
                    List.of(), Map.of(), null));
            var handler = new AtomicReference<
                    StreamingChatResponseHandler>();
            var generation = new AtomicReference<String>();
            var response = ChatResponse.builder()
                    .aiMessage(AiMessage.from("reply")).build();
            var request = ChatRequest.builder()
                    .messages(UserMessage.from("hello")).build();
            var sync = new ObservingChatModel(
                    new ChatModel() {
                        @Override
                        public ChatResponse doChat(
                                ChatRequest ignored) {
                            assertEquals(root.traceId(), logValue("traceId"));
                            assertNotEquals(Span.fromContext(root.observationContext().otelContext())
                                    .getSpanContext().getSpanId(), logValue("spanId"));
                            return response;
                        }
                    }, observability, "test");
            var streaming = new ObservingStreamingChatModel(
                    new StreamingChatModel() {
                        @Override
                        public void doChat(ChatRequest ignored,
                                StreamingChatResponseHandler callback) {
                            assertEquals(root.traceId(), logValue("traceId"));
                            generation.set(logValue("spanId"));
                            handler.set(callback);
                        }
                    }, observability, "test");
            try (var scope = root.scope()) {
                sync.chat(request);
                streaming.chat(request, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String text) {
                        assertEquals(root.traceId(), logValue("traceId"));
                        assertEquals(generation.get(), logValue("spanId"));
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse ignored) {
                        assertEquals(root.traceId(), logValue("traceId"));
                        assertEquals(generation.get(), logValue("spanId"));
                    }

                    @Override
                    public void onError(Throwable error) {
                        assertEquals(root.traceId(), logValue("traceId"));
                        assertEquals(generation.get(), logValue("spanId"));
                    }
                });
            }
            worker.submit(() -> {
                handler.get().onPartialResponse("reply");
                assertNull(logValue("traceId"));
                handler.get().onCompleteResponse(response);
                assertNull(logValue("traceId"));
                handler.get().onError(new IllegalStateException("late callback"));
                assertNull(logValue("traceId"));
            }).get();
            root.succeed(null);
        } finally {
            observability.close();
        }
    }

    @Test
    void maintenanceKeepsItsOwnTraceWhenPrimaryScopeIsStillActive() {
        var observability = AgentObservabilityTesting.build(
                AgentObservabilityConfig.builder().rootRatio(1).build(), InMemorySpanExporter.create());
        var start = new AgentExecutionStart("agent.run", "session", 1L, "agent", "session", "CHAT", "run",
                List.of(), Map.of(), null);
        try {
            var primary = observability.start(start);
            try (var primaryScope = primary.scope()) {
                var maintenance = observability.startMaintenance(start, primary.observationContext());
                assertNotEquals(primary.traceId(), maintenance.traceId());
                try (var maintenanceScope = maintenance.scope()) {
                    assertEquals(maintenance.traceId(), logValue("traceId"));
                }
                maintenance.succeed(null);
                assertEquals(primary.traceId(), logValue("traceId"));
            }
            primary.succeed(null);
        } finally {
            observability.close();
        }
    }

    @Test
    void executionAndModelLogsUseRealSpanIdsOnWorkerAndRestoreContext() throws Exception {
        var config = AgentObservabilityConfig.builder().rootRatio(1).build();
        var observability = AgentObservabilityTesting.build(config, InMemorySpanExporter.create());
        try (var worker = Executors.newSingleThreadExecutor()) {
            var execution = observability.start(new AgentExecutionStart(
                    "agent.run", "session", 1L, "agent", "session", "CHAT", "run",
                    List.of(), Map.of(), null));
            worker.submit(() -> {
                MDC.put("traceId", "previous-trace");
                MDC.put("spanId", "previous-span");
                MDC.put("unrelated", "preserved");
                try {
                    try (var scope = execution.scope()) {
                        assertEquals(execution.traceId(), logValue("traceId"));
                        String rootSpan = Span.current().getSpanContext().getSpanId();
                        assertEquals(rootSpan, logValue("spanId"));
                        var child = observability.span(ObservationSpec.of("model", HObsKind.GENERATION,
                                "langchain4j"), observability.currentContext());
                        try (var childScope = observability.scope(child.context())) {
                            assertEquals(execution.traceId(), logValue("traceId"));
                            assertEquals(child.spanId(), logValue("spanId"));
                            assertNotEquals(rootSpan, logValue("spanId"));
                        }
                        child.succeed();
                        assertEquals(rootSpan, logValue("spanId"));
                    }
                    assertEquals("previous-trace", logValue("traceId"));
                    assertEquals("previous-span", logValue("spanId"));
                    assertEquals("preserved", MDC.get("unrelated"));
                } finally {
                    MDC.clear();
                }
            }).get();
            assertNull(worker.submit(() -> logValue("traceId")).get());
            execution.succeed(null);
        } finally {
            observability.close();
        }
    }
}
