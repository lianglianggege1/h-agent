package com.h.backend.chat.domain.agent;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 把子 Agent 自身生命周期里的细粒度事件转交给当前父会话的产品 SSE。
 *
 * <p>AgentScope 2.0.1 的同步 {@code agent_spawn} 通过 {@code call()} 执行子 Agent；该路径
 * 会在返回 {@code Msg} 前过滤子流中的 delta，只给父流留下包装 START/END。这个 relay
 * 订阅子 Agent middleware 真实看到的事件，因此不需要等待 SDK 工具调用结束。</p>
 */
@Component
public final class HarnessSubagentEventRelay {

    private static final int MAX_REPLAY_EVENTS = 4096;

    public record RelayedEvent(String streamId, long sequence, AgentEvent event) { }

    private static final class Channel {
        private String streamId = UUID.randomUUID().toString();
        private final Deque<RelayedEvent> replay = new ArrayDeque<>();
        private final List<Consumer<RelayedEvent>> listeners = new ArrayList<>();
        private long sequence;
        private boolean terminal;
    }

    private final ConcurrentMap<Key, Channel> channels = new ConcurrentHashMap<>();

    /**
     * 先回放当前子运行已产生的事件，再原子切换到实时监听，避免打开子页面时出现历史/实时空隙。
     */
    public Runnable subscribe(String userId, String sessionId, Consumer<RelayedEvent> listener) {
        Key key = new Key(userId, sessionId);
        Channel channel = channels.computeIfAbsent(key, ignored -> new Channel());
        synchronized (channel) {
            channel.replay.forEach(listener);
            channel.listeners.add(listener);
        }
        return () -> {
            Channel current = channels.get(key);
            if (current == null) {
                return;
            }
            synchronized (current) {
                current.listeners.remove(listener);
            }
        };
    }

    public void publish(String userId, String sessionId, AgentEvent event) {
        Channel channel = channels.computeIfAbsent(new Key(userId, sessionId), ignored -> new Channel());
        synchronized (channel) {
            if (event instanceof AgentStartEvent && channel.terminal) {
                channel.streamId = UUID.randomUUID().toString();
                channel.sequence = 0;
                channel.replay.clear();
                channel.terminal = false;
            }
            RelayedEvent relayed = new RelayedEvent(channel.streamId, ++channel.sequence, event);
            channel.replay.addLast(relayed);
            while (channel.replay.size() > MAX_REPLAY_EVENTS) {
                channel.replay.removeFirst();
            }
            for (Consumer<RelayedEvent> listener : List.copyOf(channel.listeners)) {
                listener.accept(relayed);
            }
            if (event instanceof AgentEndEvent) {
                channel.terminal = true;
            }
        }
    }

    private record Key(String userId, String sessionId) {
    }
}
