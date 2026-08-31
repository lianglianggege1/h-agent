package com.h.backend.chat.application;

import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.domain.memory.HarnessMemoryDocument;
import com.h.backend.chat.domain.memory.HarnessMemoryDocumentException;
import com.h.backend.chat.domain.memory.HarnessMemoryDocumentException.Kind;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前认证用户唯一 Harness MEMORY.md 的读写门面：在 BaseStore seam 后隐藏
 * AgentScope namespace、value_json 编码与 CAS 细节，只暴露读取与按已观察 revision 保存。
 */
@Service
public class HarnessMemoryDocumentManager {

    private static final Logger log = LoggerFactory.getLogger(HarnessMemoryDocumentManager.class);

    static final int MAX_CONTENT_BYTES = 65_536;

    private static final String ITEM_KEY = "/MEMORY.md";

    private static final String DEFAULT_TEMPLATE = """
            # 用户长期记忆

            ## 工作偏好

            ## 个人信息

            ## 项目知识

            ## 表达方式
            """;

    private final BaseStore store;
    private final Clock clock;

    @Autowired
    public HarnessMemoryDocumentManager(@Qualifier("harnessWorkspaceStore") BaseStore store) {
        this(store, Clock.systemUTC());
    }

    public HarnessMemoryDocumentManager(BaseStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public HarnessMemoryDocument view(long userId) {
        StoreItem item = readItem("view", userId);
        if (item == null) {
            return new HarnessMemoryDocument(DEFAULT_TEMPLATE, 0L, false, null);
        }
        HarnessMemoryDocument document = toDocument("view", userId, item);
        log.debug("action=harness_memory.view outcome=success userId={} revision={}",
                userId, document.revision());
        return document;
    }

    public HarnessMemoryDocument save(long userId, String content, long expectedRevision) {
        int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > MAX_CONTENT_BYTES) {
            log.warn("action=harness_memory.save outcome=too_large userId={} contentBytes={} maxBytes={}",
                    userId, contentBytes, MAX_CONTENT_BYTES);
            throw new HarnessMemoryDocumentException(Kind.CONTENT_TOO_LARGE);
        }

        StoreItem current = readItem("save", userId);
        long actualRevision = current == null ? 0L : current.version();
        if (expectedRevision != actualRevision) {
            log.warn("action=harness_memory.save outcome=conflict userId={} expectedRevision={} "
                            + "actualRevision={} contentBytes={}",
                    userId, expectedRevision, actualRevision, contentBytes);
            throw new HarnessMemoryDocumentException(Kind.REVISION_CONFLICT);
        }

        boolean written;
        try {
            written = store.putIfVersion(namespace(userId), ITEM_KEY, buildValue(current, content), expectedRevision);
        } catch (RuntimeException error) {
            log.warn("action=harness_memory.save outcome=unavailable userId={} expectedRevision={} contentBytes={}",
                    userId, expectedRevision, contentBytes, error);
            throw new HarnessMemoryDocumentException(Kind.STORE_UNAVAILABLE, error);
        }
        if (!written) {
            log.warn("action=harness_memory.save outcome=conflict userId={} expectedRevision={} "
                            + "actualRevision={} contentBytes={}",
                    userId, expectedRevision, quietLatestRevision(userId), contentBytes);
            throw new HarnessMemoryDocumentException(Kind.REVISION_CONFLICT);
        }

        HarnessMemoryDocument saved = toDocument("save", userId, readItem("save", userId));
        log.info("action=harness_memory.save outcome=success userId={} expectedRevision={} "
                        + "revision={} contentBytes={}",
                userId, expectedRevision, saved.revision(), contentBytes);
        return saved;
    }

    private Map<String, Object> buildValue(StoreItem current, String content) {
        Map<String, Object> value = current == null || current.value() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(current.value());
        Instant now = clock.instant();
        value.put("content", content);
        value.put("encoding", "utf-8");
        if (current == null) {
            value.put("created_at", now.toString());
        }
        value.put("modified_at", now.toString());
        return value;
    }

    private HarnessMemoryDocument toDocument(String action, long userId, StoreItem item) {
        if (item == null) {
            throw corrupt(action, userId);
        }
        Map<String, Object> value = item.value();
        Object content = value == null ? null : value.get("content");
        if (!(content instanceof String contentText)) {
            throw corrupt(action, userId);
        }
        Object encoding = value.get("encoding");
        if (encoding != null && !"utf-8".equals(encoding)) {
            throw corrupt(action, userId);
        }
        return new HarnessMemoryDocument(contentText, item.version(), true,
                parseUpdatedAt(action, userId, value.get("modified_at")));
    }

    private Instant parseUpdatedAt(String action, long userId, Object modifiedAt) {
        if (modifiedAt == null) {
            return null;
        }
        if (modifiedAt instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ignored) {
                // 落入下方 corrupt 分支统一处理。
            }
        }
        throw corrupt(action, userId);
    }

    private StoreItem readItem(String action, long userId) {
        try {
            return store.get(namespace(userId), ITEM_KEY);
        } catch (RuntimeException error) {
            log.warn("action=harness_memory.{} outcome=unavailable userId={}", action, userId, error);
            throw new HarnessMemoryDocumentException(Kind.STORE_UNAVAILABLE, error);
        }
    }

    private long quietLatestRevision(long userId) {
        try {
            StoreItem item = store.get(namespace(userId), ITEM_KEY);
            return item == null ? 0L : item.version();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private HarnessMemoryDocumentException corrupt(String action, long userId) {
        // 正文与 value_json 不进日志，避免私人记忆泄漏。
        log.error("action=harness_memory.{} outcome=corrupt userId={}", action, userId);
        return new HarnessMemoryDocumentException(Kind.CONTENT_CORRUPT);
    }

    private static List<String> namespace(long userId) {
        return List.of("agents", ChatAgentIds.HARNESS, "users", String.valueOf(userId), "root");
    }
}
