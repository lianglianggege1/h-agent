package com.h.matrixteam;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 记录各房间 Agent 执行的进行中状态与最近结束时间，供文档续跑避免与原 run 重复应答。 */
public final class TeamRunTracker {

    private static final class Room {
        volatile boolean active;
        volatile long lastEndedAt;
    }

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public void begin(String roomId) {
        rooms.computeIfAbsent(roomId, key -> new Room()).active = true;
    }

    public void end(String roomId) {
        Room room = rooms.get(roomId);
        if (room != null) {
            room.lastEndedAt = System.currentTimeMillis();
            room.active = false;
        }
    }

    public boolean isActive(String roomId) {
        Room room = rooms.get(roomId);
        return room != null && room.active;
    }

    public long lastEndedAt(String roomId) {
        Room room = rooms.get(roomId);
        return room == null ? 0 : room.lastEndedAt;
    }
}