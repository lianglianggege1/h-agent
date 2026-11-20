package com.h.capabilities.seam.matrix;

/**
 * 本轮执行的 Matrix 发言人身份与归属用户。团队房间内多个 agent 客户端都在场，
 * 交付适配器按 ACTOR 选择发送账号，保证成果消息归属实际执行的 agent。
 * ORIGIN 为本轮背后的工作台用户 id（按事件真实 sender 或委派链解析），
 * 协作工具据此在 com.h.team.userId 标注归属，使记忆/资产落到真实登录用户。
 * 两个 Worker 的 handle 均为同步执行（工具在同线程调用），执行前 set、finally clear。
 */
public final class TeamActorContext {

    private static final ThreadLocal<String> ACTOR = new ThreadLocal<>();
    private static final ThreadLocal<String> ROOM = new ThreadLocal<>();
    private static final ThreadLocal<String> ORIGIN = new ThreadLocal<>();

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

    public static void setOrigin(String userId) {
        ORIGIN.set(userId);
    }

    public static String getOrigin() {
        return ORIGIN.get();
    }

    public static void clear() {
        ACTOR.remove();
        ROOM.remove();
        ORIGIN.remove();
    }
}