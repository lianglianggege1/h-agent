package com.h.backend.chat.application;

import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.capabilities.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.matrixteam.TeamAgentIds;
import com.h.capabilities.common.exception.BusinessException;
import com.h.capabilities.chat.domain.memory.HarnessMemoryDocument;
import com.h.capabilities.chat.domain.memory.HarnessMemoryDocumentException;
import com.h.capabilities.chat.domain.memory.HarnessMemoryDocumentException.Kind;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 当前认证用户在指定 Agent 下的 MEMORY.md 读写门面：在 BaseStore seam 后隐藏
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
    private final Supplier<AgentRegistry> registry;
    private final String namespaceAgentId;
    private final boolean teamManagerEnabled;

    public List<AgentDefinition> listAgents() {
        List<AgentDefinition> agents = new ArrayList<>(
                registry.get().listEnabled().stream().filter(AgentDefinition::supportsHarnessMemory).toList());
        if (teamManagerEnabled) {
            // 团队 Manager 不是产品聊天 Agent（不进 AgentRegistry，避免混入聊天选择器），
            // 但其原生 MEMORY.md 与主聊天同库同隔离规则，这里按同一门面暴露给用户。
            agents.add(new AgentDefinition(TeamAgentIds.MANAGER, "团队 Manager", "团队协作", List.of(),
                    "团队聊天室 Manager 的跨会话长期记忆", null,
                    AgentRuntimeType.HARNESS_STREAMING, true, true));
        }
        return List.copyOf(agents);
    }

    public HarnessMemoryDocumentManager forAssistant(String agentId) {
        if (teamManagerEnabled && TeamAgentIds.MANAGER.equals(agentId)) {
            return new HarnessMemoryDocumentManager(store, clock, registry, agentId, teamManagerEnabled);
        }
        AgentDefinition definition = registry.get().requireEnabled(agentId);
        if (!definition.supportsHarnessMemory()) {
            throw new BusinessException(41003, "该 Agent 未启用长期记忆");
        }
        return new HarnessMemoryDocumentManager(store, clock, registry, agentId, teamManagerEnabled);
    }

    @Autowired
    public HarnessMemoryDocumentManager(@Qualifier("harnessWorkspaceStore") BaseStore store,
                                        ObjectProvider<AgentRegistry> registry,
                                        @Value("${MATRIX_TEAM_ENABLED:false}")
                                        boolean teamManagerEnabled) {
        // Agent 构建时也会依赖本门面，延迟解析注册表以避免启动依赖环。
        this(store, Clock.systemUTC(), registry::getObject, ChatAgentIds.HARNESS, teamManagerEnabled);
    }

    public HarnessMemoryDocumentManager(BaseStore store, Clock clock, AgentRegistry registry) {
        this(store, clock, registry, false);
    }

    public HarnessMemoryDocumentManager(BaseStore store, Clock clock, AgentRegistry registry,
                                        boolean teamManagerEnabled) {
        this(store, clock, () -> registry, ChatAgentIds.HARNESS, teamManagerEnabled);
    }

    private HarnessMemoryDocumentManager(BaseStore store, Clock clock, Supplier<AgentRegistry> registry,
                                         String agentId, boolean teamManagerEnabled) {
        this.store = store;
        this.clock = clock;
        this.registry = registry;
        this.namespaceAgentId = agentId;
        this.teamManagerEnabled = teamManagerEnabled;
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

    private List<String> namespace(long userId) {
        return List.of("agents", namespaceAgentId, "users", String.valueOf(userId), "root");
    }
}
