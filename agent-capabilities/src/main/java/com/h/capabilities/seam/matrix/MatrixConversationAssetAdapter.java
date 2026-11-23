package com.h.capabilities.seam.matrix;

import com.h.capabilities.seam.AnchorMessage;
import com.h.capabilities.seam.AssetRef;
import com.h.capabilities.seam.port.ConversationAssetPort;
import com.h.capabilities.common.exception.BusinessException;
import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamMessage;
import com.h.matrixteam.TeamProtocol;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队会话素材端口：素材即房间历史中的 Matrix 媒体消息，resourceId 约定为 mxc:// 地址。
 * AnchorMessage.id 用事件 ID 的稳定哈希（Matrix 事件 ID 是字符串，文档任务表锚点是 Long）。
 */
public class MatrixConversationAssetAdapter implements ConversationAssetPort {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 6;

    private final List<MatrixClient> clients;
    private final TeamHumanDirectory directory;
    private final TeamRoomAuthorizer authorizer;
    private final Map<Long, AnchorMessage> anchorCache = new java.util.concurrent.ConcurrentHashMap<>();

    public MatrixConversationAssetAdapter(List<MatrixClient> clients, TeamHumanDirectory directory,
                                          TeamRoomAuthorizer authorizer) {
        this.clients = clients;
        this.directory = directory;
        this.authorizer = authorizer;
    }

    @Override
    public void requireScope(long userId, String scopeId) {
        if (scopeId == null || scopeId.isBlank() || !scopeId.startsWith("!")) {
            throw new BusinessException(40000, "无效的团队房间");
        }
        if (!authorizer.canAccess(userId, scopeId)) {
            throw new BusinessException(40300, "无权访问该团队房间：它不属于当前用户");
        }
    }

    @Override
    public AssetRef requireAsset(long userId, String scopeId, String resourceId) {
        requireScope(userId, scopeId);
        TeamMessage media = findMedia(scopeId, resourceId);
        if (media == null) {
            throw new BusinessException(40404, "附件不存在或不在当前房间历史中");
        }
        return new AssetRef(media.url(), userId, media.body(), media.mimetype(), media.size(), media.url());
    }

    @Override
    public byte[] readAsset(long userId, String scopeId, String resourceId, long maxBytes) {
        AssetRef asset = requireAsset(userId, scopeId, resourceId);
        if (asset.fileSize() != null && asset.fileSize() > maxBytes) {
            throw new BusinessException(40000, "附件超出大小限制");
        }
        for (MatrixClient client : clients) {
            try {
                byte[] bytes = client.download(resourceId);
                if (bytes.length > maxBytes) {
                    throw new BusinessException(40000, "附件超出大小限制");
                }
                return bytes;
            } catch (IllegalStateException unavailable) {
                // 当前账号可能未加入该房间，换下一个客户端
            }
        }
        throw new BusinessException(40404, "附件下载失败");
    }

    @Override
    public List<Map<String, Object>> inputs(long userId, String scopeId) {
        requireScope(userId, scopeId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (TeamMessage message : history(scopeId)) {
            if (!message.isMedia() || message.url() == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resourceId", message.url());
            item.put("fileName", message.body());
            item.put("mimeType", message.mimetype());
            item.put("fileSize", message.size());
            result.add(item);
        }
        return result;
    }

    @Override
    public AnchorMessage latestUserMessage(long userId, String scopeId) {
        requireScope(userId, scopeId);
        String matrixUserId = directory.matrixUserIdOf(userId);
        if (matrixUserId == null) {
            return null;
        }
        for (TeamMessage message : history(scopeId)) {
            if (!message.isMedia() && matrixUserId.equals(message.sender())) {
                AnchorMessage anchor = new AnchorMessage(eventHash(message.eventId()), userId, message.body());
                if (anchorCache.size() > 4096) {
                    anchorCache.clear();
                }
                anchorCache.put(anchor.id(), anchor);
                return anchor;
            }
        }
        return null;
    }

    @Override
    public AnchorMessage messageById(long id) {
        return anchorCache.get(id);
    }

    private TeamMessage findMedia(String roomId, String resourceId) {
        for (TeamMessage message : history(roomId)) {
            if (message.isMedia() && resourceId.equals(message.url())) {
                return message;
            }
        }
        return null;
    }

    /** 最新在前，最多 MAX_PAGES 页。 */
    private List<TeamMessage> history(String roomId) {
        List<TeamMessage> result = new ArrayList<>();
        for (MatrixClient client : clients) {
            String before = null;
            try {
                for (int page = 0; page < MAX_PAGES; page++) {
                    JsonNode response = client.messages(roomId, PAGE_SIZE, before);
                    JsonNode chunk = response.path("chunk");
                    if (!chunk.isArray() || chunk.isEmpty()) {
                        break;
                    }
                    for (JsonNode event : chunk) {
                        TeamMessage message = TeamProtocol.parse(roomId, event);
                        if (message != null) {
                            result.add(message);
                        }
                    }
                    before = response.path("end").asText(null);
                    if (before == null || chunk.size() < PAGE_SIZE) {
                        break;
                    }
                }
                return result;
            } catch (IllegalStateException unavailable) {
                result.clear();
            }
        }
        return result;
    }

    public static long eventHash(String eventId) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : eventId.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }
}