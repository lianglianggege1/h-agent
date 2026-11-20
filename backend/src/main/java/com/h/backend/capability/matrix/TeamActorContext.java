package com.h.backend.capability.matrix;

/**
 * 本轮执行的 Matrix 发言人身份。团队房间内多个 agent 客户端都在场，
 * 交付适配器按此选择发送账号，保证成果消息归属实际执行的 agent。
 * 两个 Worker 的 handle 均为同步执行（工具在同线程调用），执行前 set、finally clear。
 */
public final class TeamActorContext {

    private static final ThreadLocal<String> ACTOR = new ThreadLocal<>();
    private static final ThreadLocal<String> ROOM = new ThreadLocal<>();

    private TeamActorContext() {
    }

    public static void set(String matrixUserId) {
        ACTOR.set(matrixUserId);
    }

    public static String get() {
        return ACTOR.get();
    }

    public static void setRoom(String roomId) {
        ROOM.set(roomId);
    }

    public static String getRoom() {
        return ROOM.get();
    }

    public static void clear() {
        ACTOR.remove();
        ROOM.remove();
    }
}