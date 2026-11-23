package com.h.capabilities.seam.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.h.matrixteam.MatrixClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 团队房间归属授权：判定「某工作台用户是否某房间的已加入成员」，作为所有跨房间能力的统一闸门。
 * 共享 bot 账号同时身处多个用户的房间，社交/记忆/资产工具若不加限制即可越权触达他人房间；
 * 本组件以房间真实成员身份为准——同一用户可跨自己的所有房间（团队群 + 单聊）读取/互通，
 * 但绝不允许触达其他用户的房间。成员判定经任一在场 bot 客户端查询 roomMembers，带 TTL 缓存，
 * 查询失败（未加入/无权限/网络）一律视为不可访问（fail-closed）。
 * 仅在团队启用时装配；两服务共享同一 DB 与 bot 账号，授权跨进程一致。
 */
@Component
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class TeamRoomAuthorizer {

    private static final long TTL_MILLIS = 30_000L;
    private static final long NEGATIVE_TTL_MILLIS = 5_000L;
    private static final int MAX_CACHE_ENTRIES = 4096;

    private record CachedMembers(Set<String> members, long expiresAt) {
    }

    private final TeamHumanDirectory directory;
    private final List<MatrixClient> clients;
    private final Map<String, CachedMembers> cache = new ConcurrentHashMap<>();

    public TeamRoomAuthorizer(TeamHumanDirectory directory, List<MatrixClient> clients) {
        this.directory = directory;
        this.clients = List.copyOf(clients);
    }

    /** 该工作台用户是否为该房间的已加入成员；用户未开通 Matrix 身份或房间不可读时返回 false。 */
    public boolean canAccess(long userId, String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return false;
        }
        String matrixUserId = directory.matrixUserIdOf(userId);
        if (matrixUserId == null || matrixUserId.isBlank()) {
            return false;
        }
        return members(roomId).contains(matrixUserId);
    }

    /** 绑定某用户的房间访问谓词，供协作工具按本轮归属过滤可枚举/读取/发送的房间。 */
    public Predicate<String> scopeFor(long userId) {
        return roomId -> canAccess(userId, roomId);
    }

    private Set<String> members(String roomId) {
        long now = System.currentTimeMillis();
        CachedMembers cached = cache.get(roomId);
        if (cached != null && cached.expiresAt() > now) {
            return cached.members();
        }
        for (MatrixClient client : clients) {
            try {
                JsonNode response = client.roomMembers(roomId);
                Set<String> joined = new HashSet<>();
                for (JsonNode event : response.path("chunk")) {
                    if ("join".equals(event.path("content").path("membership").asText())) {
                        joined.add(event.path("state_key").asText());
                    }
                }
                return store(roomId, joined, now + TTL_MILLIS);
            } catch (RuntimeException unavailable) {
                // 当前账号未加入或无权限，换下一个客户端
            }
        }
        // 所有客户端都无法读取该房间成员：fail-closed，短暂缓存负结果避免反复打 homeserver。
        return store(roomId, Set.of(), now + NEGATIVE_TTL_MILLIS);
    }

    private Set<String> store(String roomId, Set<String> members, long expiresAt) {
        if (cache.size() > MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(roomId, new CachedMembers(members, expiresAt));
        return members;
    }
}