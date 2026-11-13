package com.h.backend.chat.infrastructure.ai.a2a;

import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Re-resolves the runtime address for each send; never replays a possibly executed message. */
final class NacosA2AHttpClient implements A2AHttpClient {
    private final A2AHttpClient delegate;
    private final Supplier<String> endpoint;

    NacosA2AHttpClient(A2AHttpClient delegate, Supplier<String> endpoint) {
        this.delegate = delegate;
        this.endpoint = endpoint;
    }

    @Override
    public GetBuilder createGet() { return delegate.createGet(); }

    @Override
    public DeleteBuilder createDelete() { return delegate.createDelete(); }

    @Override
    public PostBuilder createPost() {
        return new PostBuilder() {
            private final PostBuilder request = delegate.createPost();

            @Override
            public PostBuilder url(String ignored) { return this; }

            @Override
            public PostBuilder addHeader(String name, String value) {
                request.addHeader(name, value);
                return this;
            }

            @Override
            public PostBuilder addHeaders(Map<String, String> headers) {
                request.addHeaders(headers);
                return this;
            }

            @Override
            public PostBuilder body(String body) {
                request.body(body);
                return this;
            }

            @Override
            public PostBuilder followRedirects(boolean follow) {
                request.followRedirects(follow);
                return this;
            }

            @Override
            public A2AHttpResponse post() throws IOException, InterruptedException {
                return request.url(endpoint.get()).post();
            }

            @Override
            public CompletableFuture<Void> postAsyncSSE(Consumer<ServerSentEvent> messageConsumer,
                    Consumer<Throwable> errorConsumer, Runnable completeRunnable) throws IOException, InterruptedException {
                return request.url(endpoint.get()).postAsyncSSE(messageConsumer, errorConsumer, completeRunnable);
            }
        };
    }
}
