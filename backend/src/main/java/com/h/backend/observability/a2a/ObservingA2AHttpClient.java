package com.h.backend.observability.a2a;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A2A 客户端 HTTP 接缝的观测包装（设计 13.2）：每次真实 message 请求外创建
 * {@code remote_call} CLIENT Span 并注入 W3C traceparent/tracestate/baggage，
 * 使 other-agents 侧延续同一条 Trace。Span 父级取调用线程上的 current context，
 * 即 AgentScope/LangChain4j 监听器打开的 Agent Span。
 * <p>
 * 生命周期为 HTTP 层语义：2xx 结束为 success，非 2xx 或 IO 异常结束为 failure，
 * SSE 在 complete、error 或 cancel 时恰好结束一次。JSON-RPC 层错误（HTTP 200
 * 携带 error body）由上层 Span 表达。GET（AgentCard 启动期查询）不建业务 Trace。
 */
final class ObservingA2AHttpClient implements A2AHttpClient {

    private static final String SYNC_SPAN_NAME = "remote_call a2a.message/send";
    private static final String STREAMING_SPAN_NAME = "remote_call a2a.message/stream";

    private final AgentObservability observability;
    private final A2AHttpClient delegate;

    ObservingA2AHttpClient(AgentObservability observability, A2AHttpClient delegate) {
        this.observability = observability;
        this.delegate = delegate;
    }

    @Override
    public GetBuilder createGet() {
        return delegate.createGet();
    }

    @Override
    public DeleteBuilder createDelete() {
        return delegate.createDelete();
    }

    @Override
    public PostBuilder createPost() {
        return new ObservingPostBuilder(delegate.createPost());
    }

    private AgentObservation startRemoteCall(String name, String url) {
        Map<String, String> attributes = url == null || url.isBlank()
                ? Map.of()
                : Map.of("url.full", url);
        return observability.span(
                ObservationSpec.of(name, HObsKind.REMOTE_CALL, "a2a-client", attributes),
                observability.currentContext());
    }

    private void injectW3cHeaders(AgentObservation span, PostBuilder builder) {
        Map<String, String> headers = new HashMap<>();
        observability.inject(span.context(), headers);
        if (!headers.isEmpty()) {
            builder.addHeaders(headers);
        }
    }

    private final class ObservingPostBuilder implements PostBuilder {

        private final PostBuilder delegate;
        private String url;

        ObservingPostBuilder(PostBuilder delegate) {
            this.delegate = delegate;
        }

        @Override
        public PostBuilder url(String url) {
            this.url = url;
            delegate.url(url);
            return this;
        }

        @Override
        public PostBuilder addHeader(String name, String value) {
            delegate.addHeader(name, value);
            return this;
        }

        @Override
        public PostBuilder addHeaders(Map<String, String> headers) {
            delegate.addHeaders(headers);
            return this;
        }

        @Override
        public PostBuilder body(String body) {
            delegate.body(body);
            return this;
        }

        @Override
        public A2AHttpResponse post() throws IOException, InterruptedException {
            AgentObservation span = startRemoteCall(SYNC_SPAN_NAME, url);
            injectW3cHeaders(span, delegate);
            try {
                A2AHttpResponse response = delegate.post();
                if (response.success()) {
                    span.succeed();
                } else {
                    span.fail(new IllegalStateException("A2A HTTP status " + response.status()));
                }
                return response;
            } catch (IOException | InterruptedException | RuntimeException error) {
                span.fail(error);
                throw error;
            }
        }

        @Override
        public CompletableFuture<Void> postAsyncSSE(
                Consumer<ServerSentEvent> messageConsumer,
                Consumer<Throwable> errorConsumer,
                Runnable completeRunnable) throws IOException, InterruptedException {
            AgentObservation span = startRemoteCall(STREAMING_SPAN_NAME, url);
            injectW3cHeaders(span, delegate);
            AtomicBoolean ended = new AtomicBoolean();
            CompletableFuture<Void> source = delegate.postAsyncSSE(
                    messageConsumer,
                    error -> {
                        if (ended.compareAndSet(false, true)) {
                            span.fail(error);
                        }
                        errorConsumer.accept(error);
                    },
                    () -> {
                        if (ended.compareAndSet(false, true)) {
                            span.succeed();
                        }
                        completeRunnable.run();
                    });
            CompletableFuture<Void> result = new CompletableFuture<Void>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    if (!super.cancel(mayInterruptIfRunning)) {
                        return false;
                    }
                    if (ended.compareAndSet(false, true)) {
                        span.cancel("cancelled");
                    }
                    source.cancel(mayInterruptIfRunning);
                    return true;
                }
            };
            source.whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(throwable);
                }
            });
            return result;
        }
    }
}
