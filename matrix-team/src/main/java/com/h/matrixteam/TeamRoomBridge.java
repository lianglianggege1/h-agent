package com.h.matrixteam;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 房间桥接：/sync 长轮询收消息，按策略触发 handler，房间内串行执行。
 * 自身消息与重复事件不触发；Agent 连续发言超过上限后暂停互相触发，直到人类再次发言。
 * handler 自行决定如何回复（可调用 MatrixClient 发结构化消息）。
 */
public final class TeamRoomBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TeamRoomBridge.class);
    private static final int SEEN_CAPACITY = 4096;

    public interface Handler {
        void handle(TeamMessage message) throws Exception;
    }

    private final MatrixClient client;
    private final TeamPolicy policy;
    private final List<String> roomIds;
    private final Handler handler;
    private final int maxAgentTurns;

    private final Map<String, Boolean> seen = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_CAPACITY;
        }
    };
    private final Map<String, ExecutorService> roomExecutors = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> agentTurns = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread syncThread;

    public TeamRoomBridge(MatrixClient client, TeamPolicy policy, List<String> roomIds,
                          Handler handler, int maxAgentTurns) {
        this.client = client;
        this.policy = policy;
        this.roomIds = List.copyOf(roomIds);
        this.handler = handler;
        this.maxAgentTurns = maxAgentTurns;
    }

    public void start() {
        for (String roomId : roomIds) {
            client.join(roomId);
        }
        running = true;
        syncThread = Thread.ofVirtual().name("matrix-sync-" + client.userId()).start(this::runLoop);
        log.info("Team room bridge started user={} rooms={}", client.userId(), roomIds);
    }

    private void runLoop() {
        String since = null;
        // 首次同步只取游标，不回放历史消息，避免重启后重复执行旧委派。
        try {
            since = client.sync(null, 0).path("next_batch").asText();
        } catch (RuntimeException e) {
            log.error("Initial Matrix sync failed, bridge stops: {}", e.getMessage());
            return;
        }
        while (running) {
            try {
                JsonNode response = client.sync(since, 30_000);
                since = response.path("next_batch").asText(since);
                JsonNode rooms = response.path("rooms").path("join");
                rooms.fieldNames().forEachRemaining(roomId -> {
                    for (JsonNode event : rooms.path(roomId).path("timeline").path("events")) {
                        accept(roomId, event);
                    }
                });
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn("Matrix sync error, retry in 5s: {}", e.getMessage());
                sleep(5_000);
            }
        }
    }

    private void accept(String roomId, JsonNode event) {
        TeamMessage message = TeamProtocol.parse(roomId, event);
        if (message == null || message.sender().equals(client.userId())) {
            return;
        }
        synchronized (seen) {
            if (seen.putIfAbsent(message.eventId(), Boolean.TRUE) != null) {
                return;
            }
        }
        AtomicInteger turns = agentTurns.computeIfAbsent(roomId, key -> new AtomicInteger());
        if (!policy.isAgent(message.sender())) {
            turns.set(0);
        } else if (turns.incrementAndGet() > maxAgentTurns) {
            log.warn("Agent turn limit reached in room {}, ignoring agent message until human speaks", roomId);
            return;
        }
        if (!policy.shouldRespond(message)) {
            return;
        }
        roomExecutors.computeIfAbsent(roomId, key -> Executors.newSingleThreadExecutor(
                runnable -> Thread.ofPlatform().name("matrix-room-" + key).daemon(true).unstarted(runnable)
        )).submit(() -> {
            try {
                handler.handle(message);
            } catch (Exception e) {
                log.error("Team handler failed room={} event={}", roomId, message.eventId(), e);
                try {
                    client.sendText(roomId, "处理上一条消息时出错：" + e.getMessage(), List.of(), null, null);
                } catch (RuntimeException sendError) {
                    log.error("Failed to report handler error to room {}: {}", roomId, sendError.getMessage());
                }
            }
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        if (syncThread != null) {
            syncThread.interrupt();
        }
        roomExecutors.values().forEach(ExecutorService::shutdownNow);
    }
}