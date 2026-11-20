package com.h.capabilities.memory.domain;

import com.h.capabilities.chat.domain.agent.ChatAgentIds;
import com.h.matrixteam.TeamAgentIds;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent 记忆参与策略注册表。Router/scorer/extractor 等中间 Agent 一律关闭；
 * 主聊天 Harness Agent 不在本注册表内，其链路不因长期记忆模块改造。
 * 团队 Worker（LangChain4j / Spring AI）无原生长效记忆，在此集中定义身份与策略，
 * 显式记忆工具一律走 Mem0（mem0_*），由各自 Provider 按本注册表裁定是否提供。
 */
@Component
public class AgentMemoryPolicyCatalog {

    private static final AgentMemoryPolicy STANDARD_CHAT = new AgentMemoryPolicy(
            java.util.Set.of(),
            null,
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

    /** 团队 Worker：显式 Mem0 工具开启，可召回 USER/AGENT/SESSION；不自动 capture（团队回合不经完成提交器）。 */
    private static final AgentMemoryPolicy TEAM_WORKER = new AgentMemoryPolicy(
            java.util.Set.of(MemoryScopeKind.USER, MemoryScopeKind.AGENT, MemoryScopeKind.SESSION),
            null,
            true
    );

    private static final Map<String, AgentMemoryPolicy> POLICIES = Map.ofEntries(
            Map.entry(ChatAgentIds.STANDARD_CHAT, STANDARD_CHAT),
            Map.entry("export-assistant", VISIBLE_DOMAIN_AGENT),
            Map.entry("car-rental-assistant", VISIBLE_DOMAIN_AGENT),
            Map.entry("story-chat-agent", VISIBLE_DOMAIN_AGENT),
            Map.entry("export-assistant.medical-expert", RESPONSIVE_LEAF),
            Map.entry("export-assistant.legal-expert", RESPONSIVE_LEAF),
            Map.entry("export-assistant.technical-expert", RESPONSIVE_LEAF),
            Map.entry("car-rental-assistant.response-generator", RESPONSIVE_LEAF),
            Map.entry("story-chat-agent.creative-writer", RESPONSIVE_LEAF),
            Map.entry(TeamAgentIds.WORKER, TEAM_WORKER),
            Map.entry(TeamAgentIds.SPRING_WORKER, TEAM_WORKER)
    );

    public AgentMemoryPolicy policyOf(String logicalAgentId) {
        return POLICIES.getOrDefault(logicalAgentId, AgentMemoryPolicy.disabled());
    }
}
