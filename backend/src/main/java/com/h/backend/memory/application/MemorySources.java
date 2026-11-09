package com.h.backend.memory.application;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Owned product messages and read-only legacy workspace sources. */
@Component
public class MemorySources {
    private final ChatSessionService chats;
    private final BaseStore workspace;
    public MemorySources(@Lazy ChatSessionService chats, @Qualifier("harnessWorkspaceStore") BaseStore workspace) {
        this.chats = chats; this.workspace = workspace;
    }
    public static String messageRef(String sessionId, Long messageId) {
        return "chat:" + encode(sessionId) + ":" + messageId;
    }
    private static String legacyRef(String key) { return "legacy:" + encode(key); }
    private static String encode(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String s) { return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8); }
    private static List<String> namespace(Long user) { return List.of("agents", ChatAgentIds.HARNESS, "users", user.toString(), "root"); }
    private static boolean legacyKey(String key) {
        return key.equals("/MEMORY.md") || (key.startsWith("/memory/") && key.endsWith(".md") && !key.contains(".."));
    }
    private static String content(StoreItem item) {
        Object body = item.value().get("content");
        if (body instanceof String s) return s;
        if (body instanceof List<?> lines) return String.join("\n", lines.stream().map(String::valueOf).toList());
        return "";
    }
    public Map<String,Object> search(Long user, String query) {
        List<Map<String,Object>> results = new ArrayList<>();
        int scanned = 0;
        boolean exhausted = false;
        // Scan a bounded set of files, never the entire user workspace into model context.
        for (int offset = 0; offset < 500 && results.size() < 20; offset += 50) {
            List<StoreItem> items = workspace.search(namespace(user), 50, offset);
            scanned += items.size();
            for (StoreItem item : items) {
                if (!legacyKey(item.key())) continue;
                String body = content(item);
                int match = body.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
                if (match < 0) continue;
                int start = Math.max(0, match - 100);
                results.add(Map.of("sourceRef", legacyRef(item.key()), "path", item.key(),
                        "text", body.substring(start, Math.min(body.length(), start + 1000))));
                if (results.size() == 20) break;
            }
            if (items.size() < 50) { exhausted = true; break; }
        }
        return Map.of("status", "OK", "items", results, "truncated", !exhausted, "scanned", scanned);
    }
    public Map<String,Object> read(Long user, String ref, int offset) {
        if (ref == null || ref.length() > 2000 || offset < 0) throw new IllegalArgumentException("Invalid source reference");
        String body;
        Map<String,Object> metadata = new LinkedHashMap<>();
        if (ref.startsWith("chat:")) {
            String[] parts = ref.split(":", 3);
            if (parts.length != 3) throw new IllegalArgumentException("Invalid source reference");
            com.h.backend.chat.interfaces.dto.ChatSessionMessageDto message;
            try {
                message = chats.getOwnedMessage(user, decode(parts[1]), Long.valueOf(parts[2]));
            } catch (com.h.backend.common.exception.BusinessException unavailable) {
                return Map.of("status", "SOURCE_UNAVAILABLE");
            }
            body = message.content() == null ? "" : message.content();
            metadata.put("kind", "CHAT"); metadata.put("role", message.role());
            metadata.put("messageType", message.messageType()); metadata.put("createdAt", message.createdAt());
        } else if (ref.startsWith("legacy:")) {
            String key = decode(ref.substring(7));
            if (!legacyKey(key)) throw new IllegalArgumentException("Invalid legacy source");
            StoreItem item = workspace.get(namespace(user), key);
            if (item == null) return Map.of("status", "SOURCE_UNAVAILABLE");
            body = content(item);
            metadata.put("kind", "LEGACY"); metadata.put("path", key);
        } else throw new IllegalArgumentException("Unsupported source reference");
        int start = Math.min(offset, body.length());
        int end = Math.min(body.length(), start + 8000);
        Map<String,Object> result = new LinkedHashMap<>(metadata);
        result.put("status", "OK"); result.put("sourceRef", ref); result.put("text", body.substring(start, end));
        result.put("nextCursor", end < body.length() ? end : null);
        return result;
    }
    public List<String> validated(AgentMemoryContext ctx, List<String> supplied) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (ctx.sourceMessageId() != null) refs.add(messageRef(ctx.sessionId(), ctx.sourceMessageId()));
        if (supplied != null) {
            if (supplied.size() > 8) throw new IllegalArgumentException("At most eight sources");
            for (String ref : supplied) {
                if (!"OK".equals(read(ctx.userId(), ref, 0).get("status"))) throw new IllegalArgumentException("Source unavailable");
                refs.add(ref);
            }
        }
        return List.copyOf(refs);
    }
}
