package com.h.backend.chat.infrastructure.subagent;

import com.h.backend.chat.domain.subagentdefinition.SubagentRuntimeFactory;
import com.h.backend.chat.domain.subagentdefinition.model.ResolvedSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentTurnSnapshot;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Catalog 接管 turn 内 Subagent 可用集的 middleware（设计 7.1 / 7.2）。
 *
 * <p>order 必须低于 SDK {@code SubagentsMiddleware} 的默认 order(1)，保证：</p>
 * <ul>
 *   <li>{@code onAgent}：SDK 安装完 per-call {@code CTX_AGENT_MANAGER} 后，本 middleware
 *       用 combined manager 覆盖之 —— SDK general-purpose + 内置静态 factory +
 *       当前 turn snapshot 的用户 factory；</li>
 *   <li>{@code onReasoning}：收到 SDK 已改写的 {@code ReasoningInput} 后，把 SDK 生成的
 *       Subagents 说明段替换为平台拥有的说明段（见 {@link SubagentsPromptAdapter}）。</li>
 * </ul>
 *
 * <p>RuntimeContext 中没有 {@link SubagentTurnSnapshot}（非 Catalog 执行路径）时，
 * 本 middleware 完全透传，不改变 SDK 行为。每个 turn 的 manager 独立构造，
 * 禁止按用户缓存可变 {@link DefaultAgentManager}（设计 8.1）。</p>
 */
public final class CatalogSubagentsMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(CatalogSubagentsMiddleware.class);

    private final SubagentRuntimeFactory runtimeFactory;

    public CatalogSubagentsMiddleware(SubagentRuntimeFactory runtimeFactory) {
        this.runtimeFactory = runtimeFactory;
    }

    @Override
    public int order() {
        // 高 order 在洋葱链外层先执行；0 保证本 middleware 在 SDK SubagentsMiddleware(默认 1) 之后。
        return 0;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        SubagentTurnSnapshot snapshot = snapshotOf(ctx);
        if (snapshot != null && !snapshot.byAgentId().isEmpty()) {
            DefaultAgentManager installed =
                    ctx.get(AgentSpawnTool.CTX_AGENT_MANAGER, DefaultAgentManager.class);
            if (installed != null) {
                ctx.put(AgentSpawnTool.CTX_AGENT_MANAGER, combinedManager(installed, snapshot));
                log.debug("Catalog combined agent manager installed: snapshotId={} agents={}",
                        snapshot.snapshotId(), snapshot.byAgentId().size());
            }
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        SubagentTurnSnapshot snapshot = snapshotOf(ctx);
        if (snapshot == null || snapshot.byAgentId().isEmpty()) {
            // 无 snapshot（非 Catalog 路径）或 snapshot 为空：保留 SDK 段与静态 builtins 行为一致。
            return next.apply(input);
        }
        List<Msg> messages = input.messages() != null ? input.messages() : List.of();
        if (messages.isEmpty() || messages.get(0).getRole() != MsgRole.SYSTEM) {
            // SDK 未注入 SYSTEM 时（静态 entries 为空的理论路径），按平台段补一条 SYSTEM。
            Msg sys = Msg.builder()
                    .name("system")
                    .role(MsgRole.SYSTEM)
                    .content(TextBlock.builder()
                            .text(SubagentsPromptAdapter.renderPlatformSection(
                                    listingsOf(snapshot)))
                            .build())
                    .build();
            List<Msg> rebuilt = new ArrayList<>(messages.size() + 1);
            rebuilt.add(sys);
            rebuilt.addAll(messages);
            return next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
        }

        Msg sys = messages.get(0);
        String text = sys.getTextContent() != null ? sys.getTextContent() : "";
        String rewritten = SubagentsPromptAdapter.replaceSdkSection(text, listingsOf(snapshot));
        if (rewritten.equals(text)) {
            return next.apply(input);
        }
        Msg newSys = Msg.builder()
                .id(sys.getId())
                .name(sys.getName())
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(rewritten).build())
                .metadata(sys.getMetadata())
                .timestamp(sys.getTimestamp())
                .build();
        List<Msg> rebuilt = new ArrayList<>(messages);
        rebuilt.set(0, newSys);
        return next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
    }

    private static SubagentTurnSnapshot snapshotOf(RuntimeContext ctx) {
        return ctx != null ? ctx.get(SubagentTurnSnapshot.class) : null;
    }

    /**
     * combined manager = SDK 安装的静态 entries（general-purpose + 内置声明 factory）
     * + snapshot 的用户 factory（设计 7.2 第 4 条）。每次调用独立构造。
     */
    private DefaultAgentManager combinedManager(DefaultAgentManager installed, SubagentTurnSnapshot snapshot) {
        List<SubagentEntry> combined = new ArrayList<>();
        for (Map.Entry<String, SubagentFactory> entry : installed.getAgentFactories().entrySet()) {
            combined.add(new SubagentEntry(
                    entry.getKey(),
                    entry.getKey(),
                    entry.getValue(),
                    installed.getDeclaration(entry.getKey()).orElse(null)));
        }
        for (SubagentEntry userEntry : runtimeFactory.entriesFor(snapshot)) {
            if (userEntry == null || userEntry.name() == null) {
                continue;
            }
            if (installed.hasAgent(userEntry.name())) {
                // Catalog 保证用户 ID 不得占用内置保留名；出现冲突说明数据异常，静态声明优先并告警。
                log.warn("Snapshot user agentId '{}' collides with a statically registered agent; "
                        + "keeping the static factory, snapshotId={}", userEntry.name(), snapshot.snapshotId());
                continue;
            }
            combined.add(userEntry);
        }
        return new DefaultAgentManager(combined, installed.getWorkspaceManager());
    }

    /** 平台段只列 agent_id + description；不包含 Definition 正文（设计 7.2 第 6 条）。 */
    private static List<SubagentsPromptAdapter.AgentListing> listingsOf(SubagentTurnSnapshot snapshot) {
        List<SubagentsPromptAdapter.AgentListing> listings = new ArrayList<>();
        for (ResolvedSubagentDefinition definition : snapshot.byAgentId().values()) {
            String description = definition.compiled() != null
                    ? definition.compiled().description()
                    : null;
            listings.add(new SubagentsPromptAdapter.AgentListing(definition.agentId(), description));
        }
        return listings;
    }
}
