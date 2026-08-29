package com.h.backend.observability.a2a;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservabilityConfig;
import com.h.agent.observability.AgentObservabilityTesting;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2A 客户端 HTTP 观测包装契约测试（设计 13.2）：W3C 头注入、remote_call Span
 * 父级为调用线程 current context、生命周期恰好结束一次、GET 不建业务 Trace。
 */
class ObservingA2AHttpClientTest {

    private AgentObservability observability;
    private InMemorySpanExporter exporter;

    private AgentObservability create() {
        exporter = InMemorySpanExporter.create();
        AgentObservabilityConfig config = AgentObservabilityConfig.builder()
                .baseUrl("http://langfuse.local")
                .publicKey("pk-test")
                .secretKey("sk-test")
                .rootRatio(1.0)
                .scheduleDelayMillis(10)
                .build();
        return AgentObservabilityTesting.build(config, exporter);
    }

    @AfterEach
    void tearDown() {
        if (observability != null) {
            observability.close();
        }
    }

    @Test
    void syncPostInjectsW3cHeadersAndNestsUnderCurrentAgentSpan() throws Exception {
        observability = create();
        FakeClient delegate = new FakeClient();
        ObservingA2AHttpClient client = new ObservingA2AHttpClient(observability, delegate);

        AgentObservation agent = observability.span(
                ObservationSpec.of("agent story-flow", HObsKind.AGENT, "langchain4j"), observability.currentContext());
        try (var ignored = observability.scope(agent.context())) {
            A2AHttpResponse response = client.createPost()
                    .url("http://localhost:8082/a2a/agents/creative-writer")
                    .body("{\"jsonrpc\":\"2.0\"}")
                    .post();
            assertTrue(response.success());
        }
        AgentObservabilityTesting.flush(observability);

        SpanData remoteCall = spanByName("remote_call a2a.message/send");
        assertEquals(agent.spanId(), remoteCall.getParentSpanId(),
                "remote_call must be a child of the agent span current on the calling thread");
        assertEquals("a2a-client", remoteCall.getAttributes().get(AttributeKey.stringKey(HAttrs.RUNTIME)));
        assertEquals("http://localhost:8082/a2a/agents/creative-writer",
                remoteCall.getAttributes().get(AttributeKey.stringKey("url.full")));
        assertEquals("success", remoteCall.getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));

        String traceparent = delegate.headers.get("traceparent");
        assertNotNull(traceparent, "W3C traceparent must be injected into the real request");
        assertEquals(remoteCall.getTraceId(), traceparent.split("-")[1],
                "traceparent must carry the remote_call span's trace id");
        assertNull(delegate.headers.get("Content-Type"),
                "wrapper must not touch protocol headers owned by the transport");
    }

    @Test
    void syncPostFailsSpanOnHttpErrorStatus() throws Exception {
        observability = create();
        FakeClient delegate = new FakeClient();
        delegate.response = response(500);
        ObservingA2AHttpClient client = new ObservingA2AHttpClient(observability, delegate);

        client.createPost().url("http://localhost:8082/a2a/agents/style-editor").post();
        AgentObservabilityTesting.flush(observability);

        assertEquals("failure", spanByName("remote_call a2a.message/send")
                .getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));
    }

    @Test
    void syncPostFailsSpanAndPropagatesIoError() {
        observability = create();
        FakeClient delegate = new FakeClient();
        delegate.postError = new IOException("connection reset");
        ObservingA2AHttpClient client = new ObservingA2AHttpClient(observability, delegate);

        assertThrows(IOException.class,
                () -> client.createPost().url("http://localhost:8082/a2a/agents/style-editor").post());
        AgentObservabilityTesting.flush(observability);

        assertEquals("failure", spanByName("remote_call a2a.message/send")
                .getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));
    }

    @Test
    void getRequestsAreNotTraced() throws Exception {
        observability = create();
        FakeClient delegate = new FakeClient();
        ObservingA2AHttpClient client = new ObservingA2AHttpClient(observability, delegate);

        A2AHttpResponse card = client.createGet()
                .url("http://localhost:8082/a2a/agents/creative-writer/.well-known/agent-card.json")
                .get();
        assertTrue(card.success());
        AgentObservabilityTesting.flush(observability);

        assertTrue(exporter.getFinishedSpanItems().isEmpty(),
                "AgentCard startup queries must not create business traces");
    }

    @Test
    void sseCompletionEndsSpanExactlyOnce() throws Exception {
        observability = create();
        FakeClient delegate = new FakeClient();
        ObservingA2AHttpClient client = new ObservingA2AHttpClient(observability, delegate);

        List<String> outcomes = new ArrayList<>();
        CompletableFuture<Void> future = client.createPost()
                .url("http://localhost:8082/a2a/agents/creative-writer")
                .postAsyncSSE(event -> { }, error -> outcomes.add("error"), () -> outcomes.add("complete"));
        delegate.completeStream();
        delegate.failStream(new RuntimeException("late error after completion"));
        assertNull(future.get());
        AgentObservabilityTesting.flush(observability);

        assertEquals(List.of("complete", "error"), outcomes,
                "late errors are delegated but must not re-end the span");
        SpanData span = spanByName("remote_call a2a.message/stream");
        assertEquals("success", span.getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)),
                "span outcome must stay at the first terminal result");
        assertEquals(1, exporter.getFinishedSpanItems().size());
    }

    @Test
    void sseErrorFailsSpan() throws Exception {
        observability = create();
        FakeClient delegate = new FakeClient();
        ObservingA2AHttpClient client = new ObservingA2AHttpClient(observability, delegate);

        CompletableFuture<Void> future = client.createPost()
                .url("http://localhost:8082/a2a/agents/creative-writer")
                .postAsyncSSE(event -> { }, error -> { }, () -> { });
        RuntimeException failure = new RuntimeException("stream broken");
        delegate.failStream(failure);
        assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get());
        AgentObservabilityTesting.flush(observability);

        SpanData span = spanByName("remote_call a2a.message/stream");
        assertEquals("failure", span.getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));
        assertNotNull(delegate.headers.get("traceparent"));
    }

    @Test
    void sseCancelCancelsSpanAndPropagatesToSource() throws Exception {
        observability = create();
        FakeClient delegate = new FakeClient();
        ObservingA2AHttpClient client = new ObservingA2AHttpClient(observability, delegate);

        CompletableFuture<Void> future = client.createPost()
                .url("http://localhost:8082/a2a/agents/creative-writer")
                .postAsyncSSE(event -> { }, error -> { }, () -> { });
        assertTrue(future.cancel(true));
        assertTrue(delegate.sourceFuture.isCancelled(), "cancel must propagate to the underlying stream");
        AgentObservabilityTesting.flush(observability);

        SpanData span = spanByName("remote_call a2a.message/stream");
        assertEquals("cancelled", span.getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));
    }

    @Test
    void uninitializedProviderReturnsRawDelegate() {
        ObservingA2AHttpClientProvider.initialize(null);
        A2AHttpClient client = new ObservingA2AHttpClientProvider().create();
        assertTrue(client instanceof org.a2aproject.sdk.client.http.JdkA2AHttpClient,
                "provider must pass through the JDK client when uninitialized");
    }

    private SpanData spanByName(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found: " + name));
    }

    private static A2AHttpResponse response(int status) {
        return new A2AHttpResponse() {
            @Override
            public int status() {
                return status;
            }

            @Override
            public boolean success() {
                return status >= 200 && status < 300;
            }

            @Override
            public String body() {
                return "";
            }
        };
    }

    private static final class FakeClient implements A2AHttpClient {
        private final Map<String, String> headers = new HashMap<>();
        private A2AHttpResponse response = response(200);
        private IOException postError;
        private CompletableFuture<Void> sourceFuture;
        private Runnable completeSse = () -> { };
        private Consumer<Throwable> failSse = error -> { };

        private void completeStream() {
            completeSse.run();
            sourceFuture.complete(null);
        }

        private void failStream(Throwable error) {
            failSse.accept(error);
            sourceFuture.completeExceptionally(error);
        }

        @Override
        public GetBuilder createGet() {
            return new GetBuilder() {
                @Override
                public GetBuilder url(String url) {
                    return this;
                }

                @Override
                public GetBuilder addHeader(String name, String value) {
                    headers.put(name, value);
                    return this;
                }

                @Override
                public GetBuilder addHeaders(Map<String, String> values) {
                    headers.putAll(values);
                    return this;
                }

                @Override
                public A2AHttpResponse get() {
                    return response;
                }

                @Override
                public CompletableFuture<Void> getAsyncSSE(
                        Consumer<ServerSentEvent> messageConsumer,
                        Consumer<Throwable> errorConsumer,
                        Runnable completeRunnable) {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }

        @Override
        public PostBuilder createPost() {
            return new PostBuilder() {
                @Override
                public PostBuilder url(String url) {
                    return this;
                }

                @Override
                public PostBuilder addHeader(String name, String value) {
                    headers.put(name, value);
                    return this;
                }

                @Override
                public PostBuilder addHeaders(Map<String, String> values) {
                    headers.putAll(values);
                    return this;
                }

                @Override
                public PostBuilder body(String body) {
                    return this;
                }

                @Override
                public A2AHttpResponse post() throws IOException {
                    if (postError != null) {
                        throw postError;
                    }
                    return response;
                }

                @Override
                public CompletableFuture<Void> postAsyncSSE(
                        Consumer<ServerSentEvent> messageConsumer,
                        Consumer<Throwable> errorConsumer,
                        Runnable completeRunnable) {
                    completeSse = completeRunnable;
                    failSse = errorConsumer;
                    sourceFuture = new CompletableFuture<>();
                    return sourceFuture;
                }
            };
        }

        @Override
        public DeleteBuilder createDelete() {
            return new DeleteBuilder() {
                @Override
                public DeleteBuilder url(String url) {
                    return this;
                }

                @Override
                public DeleteBuilder addHeader(String name, String value) {
                    return this;
                }

                @Override
                public DeleteBuilder addHeaders(Map<String, String> values) {
                    return this;
                }

                @Override
                public A2AHttpResponse delete() {
                    return response;
                }
            };
        }
    }
}
