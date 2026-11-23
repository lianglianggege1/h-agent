package com.h.capabilities.seam.matrix;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队人类身份目录：工作台用户 id ↔ 其专属 Matrix 账号（users.matrix_user_id/matrix_password）的双向解析，
 * 以及每用户专属团队房间（users.matrix_room_id）的持久化与「是否团队房间」分类。
 * 每个登录用户开通独立 Matrix 身份与独立团队房间，账号/密码/房间存库，程序据此自动登录并建房/进房；
 * bot 执行按房间事件真实 sender 解析归属，取代「全体共享一个人类账号 + 单一全局房间」的旧模型。
 * 仅在团队启用时装配，两服务共享同一 DB，故房间分类跨进程一致。
 */
@Component
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class TeamHumanDirectory {

    private static final Long ABSENT = -1L;

    /** 某工作台用户的 Matrix 登录凭据（账号 + 密码，存于 users 表）。 */
    public record MatrixAccount(String matrixUserId, String password) {
    }

    private final JdbcTemplate jdbc;
    private final Map<String, Long> byMatrix = new ConcurrentHashMap<>();
    private final Map<Long, String> byUser = new ConcurrentHashMap<>();
    private final Map<Long, MatrixAccount> accounts = new ConcurrentHashMap<>();
    private final Map<Long, String> roomByUser = new ConcurrentHashMap<>();
    private final Map<String, Boolean> teamRooms = new ConcurrentHashMap<>();

    public TeamHumanDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Matrix userId → 工作台用户 id；未映射（bot 或遗留账号）返回 null。 */
    public Long userIdOfMatrix(String matrixUserId) {
        if (matrixUserId == null || matrixUserId.isBlank()) {
            return null;
        }
        Long cached = byMatrix.get(matrixUserId);
        if (cached != null) {
            return ABSENT.equals(cached) ? null : cached;
        }
        List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE matrix_user_id = ?", Long.class, matrixUserId);
        if (ids.isEmpty()) {
            byMatrix.put(matrixUserId, ABSENT);
            return null;
        }
        Long id = ids.get(0);
        byMatrix.put(matrixUserId, id);
        byUser.put(id, matrixUserId);
        return id;
    }

    /** 工作台用户 id → 其 Matrix userId；尚未开通返回 null。 */
    public String matrixUserIdOf(long userId) {
        String cached = byUser.get(userId);
        if (cached != null) {
            return cached;
        }
        List<String> ids = jdbc.queryForList("SELECT matrix_user_id FROM users WHERE id = ?", String.class, userId);
        if (ids.isEmpty() || ids.get(0) == null || ids.get(0).isBlank()) {
            return null;
        }
        String matrixUserId = ids.get(0);
        byUser.put(userId, matrixUserId);
        byMatrix.put(matrixUserId, userId);
        return matrixUserId;
    }

    /** 该 Matrix 账号是否为已映射的工作台人类（bot 不在 users 映射中）。 */
    public boolean isHuman(String matrixUserId) {
        return userIdOfMatrix(matrixUserId) != null;
    }

    /**
     * 解析一条房间消息背后的工作台归属用户：
     * 委派/回报/提问链优先用 com.h.team.userId（originUserId），
     * 人类直接消息按真实 sender 的 Matrix 账号反查；bot 旁听无归属时返回 null。
     */
    public Long resolveOwner(String sender, String originUserId) {
        if (originUserId != null && !originUserId.isBlank()) {
            try {
                return Long.parseLong(originUserId.strip());
            } catch (NumberFormatException ignored) {
                // 非法 originUserId 退回 sender 解析
            }
        }
        return userIdOfMatrix(sender);
    }

    /** 读取某用户的 Matrix 登录凭据；尚未开通返回 null。 */
    public MatrixAccount accountOf(long userId) {
        MatrixAccount cached = accounts.get(userId);
        if (cached != null) {
            return cached;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT matrix_user_id, matrix_password FROM users WHERE id = ?", userId);
        if (rows.isEmpty()) {
            return null;
        }
        Object matrixUserId = rows.get(0).get("matrix_user_id");
        Object password = rows.get(0).get("matrix_password");
        if (matrixUserId == null || password == null
                || matrixUserId.toString().isBlank() || password.toString().isBlank()) {
            return null;
        }
        MatrixAccount account = new MatrixAccount(matrixUserId.toString(), password.toString());
        accounts.put(userId, account);
        byUser.put(userId, account.matrixUserId());
        byMatrix.put(account.matrixUserId(), userId);
        return account;
    }

    /** 开通账号后写入账号与密码并刷新缓存（仅 backend provisioning 调用）。 */
    public void bind(long userId, String matrixUserId, String password) {
        jdbc.update("UPDATE users SET matrix_user_id = ?, matrix_password = ?, updated_at = NOW() WHERE id = ?",
                matrixUserId, password, userId);
        MatrixAccount account = new MatrixAccount(matrixUserId, password);
        accounts.put(userId, account);
        byUser.put(userId, matrixUserId);
        byMatrix.put(matrixUserId, userId);
    }

    /** 工作台用户 id → 其专属团队房间 id；尚未建房返回 null。 */
    public String roomIdOf(long userId) {
        String cached = roomByUser.get(userId);
        if (cached != null) {
            return cached;
        }
        List<String> ids = jdbc.queryForList("SELECT matrix_room_id FROM users WHERE id = ?", String.class, userId);
        if (ids.isEmpty() || ids.get(0) == null || ids.get(0).isBlank()) {
            return null;
        }
        String roomId = ids.get(0);
        roomByUser.put(userId, roomId);
        teamRooms.put(roomId, Boolean.TRUE);
        return roomId;
    }

    /** 建房后写入用户的专属团队房间 id 并刷新缓存（仅 backend provisioning 调用）。 */
    public void bindRoom(long userId, String roomId) {
        jdbc.update("UPDATE users SET matrix_room_id = ?, updated_at = NOW() WHERE id = ?", roomId, userId);
        roomByUser.put(userId, roomId);
        teamRooms.put(roomId, Boolean.TRUE);
    }

    /**
     * 该房间是否为团队群聊房间（按团队规则路由：Manager 默认接待、Worker 只认点名/委派）。
     * 以 users.matrix_room_id 为准：每用户专属团队房间皆视为团队房间；
     * 单聊房间（仅 1 bot + 1 人类）不在其中，按单聊规则路由。两服务共享 DB，分类跨进程一致。
     */
    public boolean isTeamRoom(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return false;
        }
        Boolean cached = teamRooms.get(roomId);
        if (cached != null) {
            return cached;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE matrix_room_id = ?", Integer.class, roomId);
        boolean team = count != null && count > 0;
        teamRooms.put(roomId, team);
        return team;
    }
}