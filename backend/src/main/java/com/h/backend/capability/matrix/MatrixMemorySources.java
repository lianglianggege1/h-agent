package com.h.backend.capability.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.h.backend.memory.application.AgentMemoryContext;
import com.h.backend.memory.application.MemorySourcePort;
import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamMessage;
import com.h.matrixteam.TeamProtocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 团队记忆来源端口：来源即房间历史文本消息。
 * sourceRef 格式 matrix:{roomId}:{eventId}；read/search 只读历史，不触达 backend 会话库。
 */
public class MatrixMemorySources implements MemorySourcePort {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 4;

    private final List<MatrixClient> clients;

    public MatrixMemorySources(List<MatrixClient> clients) {
        this.clients = clients;
    }

    @Override
    public Map<String, Object> search(AgentMemoryContext ctx, String query) {
        // search 无会话上下文：约定调用方经 read 校验过的 ref 才进入记忆；
        // 这里返回空结果集，团队记忆来源以当前会话消息（validated 自动注入）为主。
        return Map.of("status", "OK", "items", List.of(), "truncated", false, "scanned", 0);
    }

    @Override
    public Map<String, Object> read(Long user, String ref, int offset) {
        if (ref == null || !ref.startsWith("matrix:") || offset < 0) {
            throw new IllegalArgumentException("Invalid source reference");
        }
        String[] parts = ref.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid source reference");
        }
        String roomId = parts[1];
        String eventId = parts[2];
        for (TeamMessage message : history(roomId)) {
            if (eventId.equals(message.eventId()) && !message.isMedia()) {
                String body = message.body() == null ? "" : message.body();
                int start = Math.min(offset, body.length());
                int end = Math.min(body.length(), start + 8000);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("kind", "CHAT");
                result.put("role", "USER");
                result.put("status", "OK");
                result.put("sourceRef", ref);
                result.put("text", body.substring(start, end));
                result.put("nextCursor", end < body.length() ? end : null);
                return result;
            }
        }
        return Map.of("status", "SOURCE_UNAVAILABLE");
    }

    @Override
    public List<String> validated(AgentMemoryContext ctx, List<String> supplied) {
        List<String> refs = new ArrayList<>();
        if (supplied != null) {
            if (supplied.size() > 8) {
                throw new IllegalArgumentException("At most eight sources");
            }
            for (String ref : supplied) {
                if (!"OK".equals(read(ctx.userId(), ref, 0).get("status"))) {
                    throw new IllegalArgumentException("Source unavailable");
                }
                refs.add(ref);
            }
        }
        return List.copyOf(refs);
    }

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
}