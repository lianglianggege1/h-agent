package com.h.backend.chat.domain.agent;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-invocation cancellation; never interrupt a shared Agent instance belonging to other sessions. */
@Component
public final class HarnessExecutionRegistry {
    private record Key(String userId, String sessionId) { }
    private final ConcurrentHashMap<Key, Set<Sinks.Empty<Void>>> executions = new ConcurrentHashMap<>();

    public <T> Flux<T> track(String userId, String sessionId, Flux<T> source) {
        if (userId == null || sessionId == null) return source;
        return Flux.defer(() -> {
            Key key = new Key(userId, sessionId);
            Sinks.Empty<Void> stop = Sinks.empty();
            executions.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(stop);
            return source.takeUntilOther(stop.asMono()).doFinally(signal -> executions.computeIfPresent(key, (ignored, current) -> {
                current.remove(stop);
                return current.isEmpty() ? null : current;
            }));
        });
    }

    public void stop(String userId, String sessionId) {
        Set<Sinks.Empty<Void>> pending = executions.get(new Key(userId, sessionId));
        if (pending != null) Set.copyOf(pending).forEach(sink -> sink.tryEmitError(new java.util.concurrent.CancellationException("用户已停止任务")));
    }
}
