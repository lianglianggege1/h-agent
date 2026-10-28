package com.h.backend.observability.langchain4j;

import com.h.agent.observability.lifecycle.ExecutionContextSnapshot;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/** Restores context before the framework's HTTP response logger and SSE handlers run. */
public final class ContextPropagatingHttpClientBuilder implements HttpClientBuilder {
    private final HttpClientBuilder delegate;

    public ContextPropagatingHttpClientBuilder() {
        this(HttpClientBuilderLoader.loadHttpClientBuilder());
    }

    ContextPropagatingHttpClientBuilder(HttpClientBuilder delegate) {
        this.delegate = delegate;
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        delegate.connectTimeout(timeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        delegate.readTimeout(timeout);
        return this;
    }

    @Override
    public HttpClient build() {
        HttpClient client = delegate.build();
        return new HttpClient() {
            @Override
            public SuccessfulHttpResponse execute(HttpRequest request) {
                return client.execute(request);
            }

            @Override
            public CompletableFuture<SuccessfulHttpResponse> executeAsync(HttpRequest request) {
                var captured = ExecutionContextSnapshot.capture();
                var upstream = client.executeAsync(request);
                var result = new CompletableFuture<SuccessfulHttpResponse>();
                upstream.whenComplete((response, error) -> {
                    try (var scope = captured.scope()) {
                        if (error == null) result.complete(response);
                        else result.completeExceptionally(error);
                    }
                });
                result.whenComplete((response, error) -> {
                    if (result.isCancelled()) upstream.cancel(true);
                });
                return result;
            }

            @Override
            public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request, ServerSentEventParser parser) {
                var captured = ExecutionContextSnapshot.capture();
                var upstream = client.stream(request, parser);
                return downstream -> {
                    try (var scope = captured.scope()) {
                        upstream.subscribe(new Flow.Subscriber<>() {
                            @Override
                            public void onSubscribe(Flow.Subscription subscription) {
                                try (var scope = captured.scope()) {
                                    downstream.onSubscribe(subscription);
                                }
                            }

                            @Override
                            public void onNext(HttpStreamingEvent event) {
                                try (var scope = captured.scope()) {
                                    downstream.onNext(event);
                                }
                            }

                            @Override
                            public void onError(Throwable error) {
                                try (var scope = captured.scope()) {
                                    downstream.onError(error);
                                }
                            }

                            @Override
                            public void onComplete() {
                                try (var scope = captured.scope()) {
                                    downstream.onComplete();
                                }
                            }
                        });
                    }
                };
            }

            @Override
            public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                var captured = ExecutionContextSnapshot.capture();
                client.execute(request, parser, new ServerSentEventListener() {
                    @Override
                    public void onOpen(SuccessfulHttpResponse response) {
                        try (var scope = captured.scope()) {
                            listener.onOpen(response);
                        }
                    }

                    @Override
                    public void onEvent(ServerSentEvent event) {
                        try (var scope = captured.scope()) {
                            listener.onEvent(event);
                        }
                    }

                    @Override
                    public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
                        try (var scope = captured.scope()) {
                            listener.onEvent(event, context);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        try (var scope = captured.scope()) {
                            listener.onError(error);
                        }
                    }

                    @Override
                    public void onClose() {
                        try (var scope = captured.scope()) {
                            listener.onClose();
                        }
                    }
                });
            }
        };
    }
}
