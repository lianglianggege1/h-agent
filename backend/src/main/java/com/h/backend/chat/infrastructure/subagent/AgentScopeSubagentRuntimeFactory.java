package com.h.backend.chat.infrastructure.subagent;

import com.h.backend.chat.domain.agent.HarnessSubagentLifecycleMiddleware;
import com.h.backend.chat.domain.agent.ParentAssignmentSystemPromptMiddleware;
import com.h.backend.observability.agentscope.AgentScopeObservationInstaller;
import com.h.backend.chat.domain.subagentdefinition.SubagentCapabilityPolicy;
import com.h.backend.chat.domain.subagentdefinition.SubagentRuntimeFactory;
import com.h.backend.chat.domain.subagentdefinition.model.CompiledSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.ResolvedSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionSource;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentTurnSnapshot;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link SubagentRuntimeFactory} 的 AgentScope 2.0.1 实现（设计 4.2 / 7.4）。
 *
 * <p>物化规则：</p>
 * <ul>
 *   <li>BUILTIN（含 synthetic {@code general-purpose}）：委托父 Agent 的静态声明 factory，
 *       与 agent_spawn 物化路径完全一致（共享 USER-scoped Remote workspace、
 *       SDK 原生 model/steps/middleware 继承）；</li>
 *   <li>USER：在平台侧构建 leaf child——继承父模型与 DistributedStore、
 *       SESSION-isolated Remote filesystem（禁止退化为 Local filesystem）、
 *       平台能力交集后的精确 toolkit、发布版本正文 + Subagent 上下文段作为 system prompt，
 *       并显式关闭 subagents / shell / Memory / Plan / Skill 管理等 leaf 约束。</li>
 * </ul>
 *
 * <p>每次 {@code materialize} 调用都构建全新实例，不共享可变状态；
 * 同一 turn snapshot 内的 factory closure 固定到同一 Definition Version（设计 7.7）。</p>
 */
@Component
public class AgentScopeSubagentRuntimeFactory implements SubagentRuntimeFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeSubagentRuntimeFactory.class);

    /**
     * SDK {@code HarnessAgentBuilderSupport.SUBAGENT_CONTEXT_SECTION} 的 2.0.1 原文复刻
     * （该常量为包私有，无法引用）。SDK 升级时需同步校对本段。
     */
    private static final String SUBAGENT_CONTEXT_SECTION = """
            # Subagent Context

            You are a **subagent** spawned by the main agent for a specific task.

            ## Your Role
            - Complete the assigned task. That's your entire purpose.
            - You are NOT the main agent. Don't try to be.

            ## Rules
            1. **Stay focused** — Do your assigned task, nothing else
            2. **Complete the task** — Your final message will be automatically reported to the main agent
            3. **Don't initiate** — No heartbeats, no proactive actions, no side quests
            4. **Be ephemeral** — You may be terminated after task completion. That's fine.
            5. **Recover from truncated tool output** — If you see `[truncated: output exceeded context limit]`, re-read only what you need using smaller chunks (read with offset/limit, or targeted grep/head/tail) instead of full re-reads

            ## Output Format
            When complete, your final response should include:
            - What you accomplished or found
            - Any relevant details the main agent should know
            - Keep it concise but informative

            ## What You DON'T Do
            - NO user conversations (that's the main agent's job)
            - NO spawning further subagents — you are a leaf worker
            - NO pretending to be the main agent
            - Return plain text results; let the main agent deliver them to the user
            """;

    private final ObjectProvider<HarnessAgent> harnessAgentProvider;
    private final SubagentCapabilityPolicy capabilityPolicy;
    private final ParentAssignmentSystemPromptMiddleware assignmentMiddleware;
    private final HarnessSubagentLifecycleMiddleware lifecycleMiddleware;
    private final AgentScopeObservationInstaller observationInstaller;
    private final Path workspaceRoot;

    public AgentScopeSubagentRuntimeFactory(
            @Qualifier("harnessAgent") ObjectProvider<HarnessAgent> harnessAgentProvider,
            SubagentCapabilityPolicy capabilityPolicy,
            ParentAssignmentSystemPromptMiddleware assignmentMiddleware,
            HarnessSubagentLifecycleMiddleware lifecycleMiddleware,
            AgentScopeObservationInstaller observationInstaller,
            @Value("${chat.harness.workspace-template:/tmp/h-agent/harness-workspace}") String workspace
    ) {
        this.harnessAgentProvider = harnessAgentProvider;
        this.capabilityPolicy = capabilityPolicy;
        this.assignmentMiddleware = assignmentMiddleware;
        this.lifecycleMiddleware = lifecycleMiddleware;
        this.observationInstaller = observationInstaller;
        this.workspaceRoot = Path.of(workspace);
    }

    /**
     * 运行期才解析父 Agent：构造期触碰会在 harnessAgent Bean 创建中形成循环，
     * 且 HarnessAgent 是 final 类，@Lazy 的 CGLIB 代理无法生成。
     * materialize 只发生在父 turn 执行时，此时 Bean 已完全初始化。
     */
    private HarnessAgent harnessAgent() {
        return harnessAgentProvider.getObject();
    }

    @Override
    public List<SubagentEntry> entriesFor(SubagentTurnSnapshot snapshot) {
        if (snapshot == null || snapshot.byAgentId().isEmpty()) {
            return List.of();
        }
        List<SubagentEntry> entries = new ArrayList<>();
        for (ResolvedSubagentDefinition definition : snapshot.byAgentId().values()) {
            if (definition.source() != SubagentDefinitionSource.USER) {
                // BUILTIN / synthetic 由 HarnessAgent 构建期静态注册，SDK 原生 factory 物化。
                continue;
            }
            ResolvedSubagentDefinition pinned = definition;
            entries.add(new SubagentEntry(
                    definition.agentId(),
                    descriptionOf(definition),
                    parentRc -> materialize(pinned, parentRc),
                    null
            ));
        }
        return List.copyOf(entries);
    }

    @Override
    public ReActAgent materialize(ResolvedSubagentDefinition definition, RuntimeContext parentContext) {
        if (definition == null || definition.agentId() == null) {
            throw new IllegalArgumentException("materialize requires a resolved definition");
        }
        if (definition.source() != SubagentDefinitionSource.USER) {
            return materializeViaParentManager(definition, parentContext);
        }
        return buildUserChild(definition, parentContext);
    }

    /** BUILTIN / synthetic：走父 Agent 静态声明 factory，与 spawn 路径行为一致。 */
    private ReActAgent materializeViaParentManager(
            ResolvedSubagentDefinition definition, RuntimeContext parentContext) {
        var manager = harnessAgent().getSubagentAgentManager();
        if (manager == null) {
            throw new IllegalStateException("Harness subagent manager is unavailable");
        }
        Agent child = manager.createAgentIfPresent(definition.agentId(), parentContext)
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown Harness subagent type: " + definition.agentId()
                ));
        if (child instanceof HarnessAgent harnessChild) {
            return harnessChild.getDelegate();
        }
        if (child instanceof ReActAgent reactChild) {
            return reactChild;
        }
        throw new IllegalStateException(
                "Exposed Harness subagent must be ReActAgent or HarnessAgent: "
                        + child.getClass().getName()
        );
    }

    private ReActAgent buildUserChild(
            ResolvedSubagentDefinition definition, RuntimeContext parentContext) {
        CompiledSubagentDefinition compiled = definition.compiled();
        SubagentCapabilityPolicy.EffectiveCapabilities capabilities =
                capabilityPolicy.effective(compiled, null);

        HarnessAgent.Builder sub = HarnessAgent.builder()
                .name(definition.agentId())
                .description(compiled.description())
                // 第一期只支持 model: inherit（设计 7.4 共同规则）。
                .model(harnessAgent().getModel())
                .toolkit(exactToolkit(capabilities.tools()))
                .workspace(isolatedWorkspace(definition.agentId()))
                .defaultSessionId(childStateBucket(definition.agentId(), parentContext))
                .maxIters(compiled.steps())
                .sysPrompt(sysPromptWithSubagentContext(compiled.systemPrompt()))
                // SESSION-isolated Remote filesystem；由父 DistributedStore 提供 BaseStore，
                // 禁止退化为 Local filesystem（设计 7.4 Workspace 规则）。
                .filesystem(new ReservedRemoteFilesystemSpec()
                        .isolationScope(IsolationScope.SESSION))
                .distributedStore(harnessAgent().getDistributedStore())
                .stateStore(harnessAgent().getStateStore())
                // leaf worker 约束：无嵌套 subagent、无 shell、无 Memory/会话持久化。
                .disableSubagents()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableSessionPersistence()
                // 与 SDK 声明式 child 一致：继承父显式 middleware（委托合并 + 生命周期投影）。
                .middleware(assignmentMiddleware)
                .middleware(lifecycleMiddleware)
                // 统一观测（设计 12.3 / 12.5）：USER 动态子 Agent 不走父 builder 复制路径，必须显式安装。
                .middleware(observationInstaller.middleware());

        HarnessAgent child = sub.build();
        log.debug("Materialized USER subagent agentId={} definitionId={} version={} tools={}",
                definition.agentId(), definition.definitionId(), definition.version(),
                capabilities.tools());
        return child.getDelegate();
    }

    /** 平台能力交集后的精确 toolkit：不在有效集合内的工具全部移除（空集合表示无能力）。 */
    private Toolkit exactToolkit(List<String> effectiveTools) {
        Toolkit toolkit = harnessAgent().getToolkit().copy();
        Set<String> allow = Set.copyOf(effectiveTools);
        List<String> toRemove = new ArrayList<>();
        for (ToolSchema schema : toolkit.getToolSchemas()) {
            if (!allow.contains(schema.getName())) {
                toRemove.add(schema.getName());
            }
        }
        toRemove.forEach(toolkit::removeTool);
        return toolkit;
    }

    /** 与 SDK 声明式 child 相同的隔离工作目录约定：agents/<name>/workspace。 */
    private Path isolatedWorkspace(String agentId) {
        Path isolated = workspaceRoot.resolve("agents").resolve(agentId).resolve("workspace");
        try {
            Files.createDirectories(isolated);
        } catch (Exception e) {
            log.warn("Failed to create isolated workspace for subagent '{}' at {}: {}",
                    agentId, isolated, e.getMessage());
        }
        return isolated;
    }

    /**
     * 复刻 SDK {@code deriveChildSessionId}：{@code name@parentSession#userId}
     * 作为 child 持久化 AgentState 桶，避免不同 (user, parent-session) 互读状态。
     */
    private static String childStateBucket(String agentId, RuntimeContext parentContext) {
        if (parentContext == null) {
            return agentId;
        }
        String sid = sanitizeIdentifier(parentContext.getSessionId());
        String uid = sanitizeIdentifier(parentContext.getUserId());
        if (sid == null && uid == null) {
            return agentId;
        }
        StringBuilder bucket = new StringBuilder(agentId);
        if (sid != null) {
            bucket.append('@').append(sid);
        }
        if (uid != null) {
            bucket.append('#').append(uid);
        }
        return bucket.toString();
    }

    private static String sanitizeIdentifier(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.replaceAll("[/\\\\\\s\\p{Cntrl}]", "_");
    }

    private static String sysPromptWithSubagentContext(String basePrompt) {
        String base = basePrompt != null && !basePrompt.isBlank()
                ? basePrompt.stripTrailing()
                : "";
        return base.isEmpty() ? SUBAGENT_CONTEXT_SECTION : base + "\n\n" + SUBAGENT_CONTEXT_SECTION;
    }

    private static String descriptionOf(ResolvedSubagentDefinition definition) {
        CompiledSubagentDefinition compiled = definition.compiled();
        return compiled != null ? compiled.description() : null;
    }

    @SuppressWarnings("unused")
    private static Set<String> reservedAgentIds() {
        // 预留给未来需要在此集中判断保留名的场景；当前由 Catalog 校验保证。
        return new HashSet<>();
    }
}
