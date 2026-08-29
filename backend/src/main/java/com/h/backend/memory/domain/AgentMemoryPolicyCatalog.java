package com.h.backend.memory.domain;

import com.h.backend.chat.domain.agent.ChatAgentIds;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent 记忆参与策略注册表。Router/scorer/extractor 等中间 Agent 一律关闭；
 * Harness Agent 不在本注册表内，其链路不因长期记忆模块改造。
 */
@Component
public class AgentMemoryPolicyCatalog {

    private static final AgentMemoryPolicy STANDARD_CHAT = new AgentMemoryPolicy(
            java.util.Set.of(MemoryScopeKind.USER, MemoryScopeKind.AGENT, MemoryScopeKind.RUN),
            MemoryScopeKind.USER,
            true
    );

    private static final AgentMemoryPolicy VISIBLE_DOMAIN_AGENT = new AgentMemoryPolicy(
            java.util.Set.of(MemoryScopeKind.USER, MemoryScopeKind.AGENT, MemoryScopeKind.RUN),
            MemoryScopeKind.RUN,
            false
    );

    /** 响应型叶子：显式开启召回；本批次不开启叶子自动 capture。 */
    private static final AgentMemoryPolicy RESPONSIVE_LEAF = new AgentMemoryPolicy(
            java.util.Set.of(MemoryScopeKind.USER, MemoryScopeKind.AGENT, MemoryScopeKind.RUN),
            null,
            false
    );

    private static final Map<String, AgentMemoryPolicy> POLICIES = Map.of(
            ChatAgentIds.STANDARD_CHAT, STANDARD_CHAT,
            "export-assistant", VISIBLE_DOMAIN_AGENT,
            "car-rental-assistant", VISIBLE_DOMAIN_AGENT,
            "story-chat-agent", VISIBLE_DOMAIN_AGENT,
            "export-assistant.medical-expert", RESPONSIVE_LEAF,
            "export-assistant.legal-expert", RESPONSIVE_LEAF,
            "export-assistant.technical-expert", RESPONSIVE_LEAF,
            "car-rental-assistant.response-generator", RESPONSIVE_LEAF,
            "story-chat-agent.creative-writer", RESPONSIVE_LEAF
    );

    public AgentMemoryPolicy policyOf(String logicalAgentId) {
        return POLICIES.getOrDefault(logicalAgentId, AgentMemoryPolicy.disabled());
    }
}
