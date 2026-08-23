package com.h.backend.chat.infrastructure.subagent;

import com.h.backend.chat.domain.subagentdefinition.SubagentRuntimeFactory;
import com.h.backend.chat.domain.subagentdefinition.model.CapabilityDeclaration;
import com.h.backend.chat.domain.subagentdefinition.model.CompiledSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.ResolvedSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionSource;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentRuntimeKind;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentTurnSnapshot;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentWorkspaceMode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Catalog middleware（设计 7.1 / 7.2）：onAgent 用 combined manager 覆盖 SDK 安装的
 * per-call manager；onReasoning 把 SDK Subagents 说明段替换为平台段。
 * 顺序本身（order 低于 SDK SubagentsMiddleware）由 7.2 的 middleware-chain 集成验证，
 * 这里验证覆盖与替换行为。
 */
class CatalogSubagentsMiddlewareTest {

    /** 与 2.0.1 SUBAGENT_SECTION_TEMPLATE 一致的最小忠实片段；完整 golden 见 SubagentsPromptAdapterTest。 */
    private static final String SDK_SECTION = """

            ## Subagents

            You have access to subagent tools for spawning and coordinating isolated subagents.

            **`agent_send`** — Send a follow-up message to an existing subagent
            **`agent_list`** — List active subagents

            ### Available agent ids
            - `general-purpose`: General purpose agent for research and analysis
            - `researcher`: 搜集和核对事实，区分证据与推断，产出带出处的结论
            """;

    private static final String SDK_TASK_SUMMARY =
            "\n### Async tasks (current session)\n- task_id: task-1  agent: researcher  status: running\n";

    @Test
    void installsCombinedManagerOverSdkStaticEntries() {
        DefaultAgentManager installed = managerOf(
                entry("general-purpose", "General purpose agent"),
                entry("researcher", "搜集和核对事实"));
        ResolvedSubagentDefinition userDefinition = userDefinition("my-reviewer", "代码审查", 3);
        FakeRuntimeFactory runtimeFactory = FakeRuntimeFactory.of(userDefinition);
        CatalogSubagentsMiddleware middleware = new CatalogSubagentsMiddleware(runtimeFactory);
        RuntimeContext ctx = contextWith(installed, snapshotOf(userDefinition));

        DefaultAgentManager combined = applyOnAgent(middleware, ctx);

        assertNotSame(installed, combined);
        assertTrue(combined.hasAgent("general-purpose"));
        assertTrue(combined.hasAgent("researcher"));
        assertTrue(combined.hasAgent("my-reviewer"));
        // 用户 factory 来自 runtimeFactory.entriesFor（固定版本的 closure），静态 factory 保留。
        assertSame(
                runtimeFactory.factories().get("my-reviewer"),
                combined.getAgentFactories().get("my-reviewer"));
        assertSame(
                installed.getAgentFactories().get("researcher"),
                combined.getAgentFactories().get("researcher"));
    }

    @Test
    void keepsStaticFactoryWhenUserAgentIdCollides() {
        DefaultAgentManager installed = managerOf(entry("researcher", "内置研究员"));
        ResolvedSubagentDefinition collision = userDefinition("researcher", "用户抢占内置 ID", 1);
        CatalogSubagentsMiddleware middleware = new CatalogSubagentsMiddleware(
                FakeRuntimeFactory.of(collision));
        RuntimeContext ctx = contextWith(installed, snapshotOf(collision));

        DefaultAgentManager combined = applyOnAgent(middleware, ctx);

        // 静态声明优先：内置 factory 不被同名用户定义覆盖。
        assertSame(
                installed.getAgentFactories().get("researcher"),
                combined.getAgentFactories().get("researcher"));
        assertEquals(1, combined.getAgentFactories().size());
    }

    @Test
    void passesThroughWithoutSnapshotOrEmptySnapshot() {
        CatalogSubagentsMiddleware middleware =
                new CatalogSubagentsMiddleware(FakeRuntimeFactory.of());
        DefaultAgentManager installed = managerOf(entry("researcher", "内置研究员"));

        // 无 snapshot（非 Catalog 路径）：manager 不被替换。
        RuntimeContext plain = RuntimeContext.builder()
                .userId("1")
                .sessionId("s-1")
                .put(AgentSpawnTool.CTX_AGENT_MANAGER, installed)
                .build();
        assertSame(installed, applyOnAgent(middleware, plain));

        // 空 snapshot：SDK 静态行为保持。
        RuntimeContext emptySnapshot = contextWith(installed, snapshotOf());
        assertSame(installed, applyOnAgent(middleware, emptySnapshot));
    }

    @Test
    void onAgentWithoutInstalledManagerDoesNotFail() {
        CatalogSubagentsMiddleware middleware = new CatalogSubagentsMiddleware(
                FakeRuntimeFactory.of(userDefinition("my-reviewer", "代码审查", 1)));
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("1")
                .sessionId("s-1")
                .put(SubagentTurnSnapshot.class, snapshotOf(userDefinition("my-reviewer", "代码审查", 1)))
                .build();

        // SDK SubagentsMiddleware 尚未安装 manager（理论路径）：不抛错、不安装任何 manager。
        assertNull(applyOnAgent(middleware, ctx));
    }

    @Test
    void replacesSdkPromptSectionWithPlatformListings() {
        CatalogSubagentsMiddleware middleware = new CatalogSubagentsMiddleware(
                FakeRuntimeFactory.of(userDefinition("my-reviewer", "代码审查", 2)));
        Msg sys = Msg.builder()
                .name("system")
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder()
                        .text("你是协作工作台的父 Agent。" + SDK_SECTION + SDK_TASK_SUMMARY)
                        .build())
                .build();
        Msg user = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text("帮我审查").build())
                .build();
        ReasoningInput input = new ReasoningInput(List.of(sys, user), List.of(), null);

        ReasoningInput rewritten = applyOnReasoning(
                middleware, RuntimeContext.builder()
                        .userId("1")
                        .sessionId("s-1")
                        .put(SubagentTurnSnapshot.class, snapshotOf(
                                userDefinition("my-reviewer", "代码审查", 2)))
                        .build(),
                input);

        assertEquals(2, rewritten.messages().size());
        Msg newSys = rewritten.messages().getFirst();
        assertEquals(MsgRole.SYSTEM, newSys.getRole());
        String text = newSys.getTextContent();
        // 平台段只列 snapshot 的 agent_id + description。
        assertTrue(text.contains("- `my-reviewer`: 代码审查"));
        // SDK 段与被 DENY 的工具指引被移除；task 摘要保留。
        assertFalse(text.contains("`agent_send`"));
        assertFalse(text.contains("`agent_list`"));
        assertFalse(text.contains("General purpose agent for research and analysis"));
        assertTrue(text.contains("### Async tasks (current session)"));
        assertTrue(text.startsWith("你是协作工作台的父 Agent。"));
        // 非 SYSTEM 消息原样保留。
        assertEquals(user, rewritten.messages().get(1));
    }

    @Test
    void onReasoningWithoutSnapshotKeepsInputUnchanged() {
        CatalogSubagentsMiddleware middleware =
                new CatalogSubagentsMiddleware(FakeRuntimeFactory.of());
        Msg sys = Msg.builder().role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text("系统提示" + SDK_SECTION).build())
                .build();
        ReasoningInput input = new ReasoningInput(List.of(sys), List.of(), null);

        // 无 snapshot：SDK 段保持原样（静态 builtins 行为）。
        assertSame(input, applyOnReasoning(middleware, RuntimeContext.empty(), input));
    }

    @Test
    void onReasoningPrependsSystemWhenMissing() {
        CatalogSubagentsMiddleware middleware = new CatalogSubagentsMiddleware(
                FakeRuntimeFactory.of(userDefinition("my-reviewer", "代码审查", 1)));
        Msg user = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text("hi").build())
                .build();
        ReasoningInput input = new ReasoningInput(List.of(user), List.of(), null);

        ReasoningInput rewritten = applyOnReasoning(
                middleware, RuntimeContext.builder()
                        .userId("1")
                        .sessionId("s-1")
                        .put(SubagentTurnSnapshot.class, snapshotOf(
                                userDefinition("my-reviewer", "代码审查", 1)))
                        .build(),
                input);

        assertEquals(2, rewritten.messages().size());
        assertEquals(MsgRole.SYSTEM, rewritten.messages().getFirst().getRole());
        assertTrue(rewritten.messages().getFirst().getTextContent().contains("## Subagents"));
        assertTrue(rewritten.messages().getFirst().getTextContent().contains("- `my-reviewer`: 代码审查"));
        assertEquals(user, rewritten.messages().get(1));
    }

    private static SubagentEntry entry(String name, String description) {
        return new SubagentEntry(name, description, rc -> mock(Agent.class), null);
    }

    private static DefaultAgentManager managerOf(SubagentEntry... entries) {
        return new DefaultAgentManager(List.of(entries), null);
    }

    /** 伪造 runtimeFactory：entriesFor 为每个 USER 定义返回固定 factory（按定义缓存）。 */
    private record FakeRuntimeFactory(
            List<ResolvedSubagentDefinition> definitions,
            Map<String, SubagentFactory> factories) implements SubagentRuntimeFactory {

        static FakeRuntimeFactory of(ResolvedSubagentDefinition... definitions) {
            Map<String, SubagentFactory> factories = new java.util.HashMap<>();
            for (ResolvedSubagentDefinition definition : definitions) {
                factories.put(definition.agentId(), rc -> mock(ReActAgent.class));
            }
            return new FakeRuntimeFactory(List.of(definitions), factories);
        }

        @Override
        public List<SubagentEntry> entriesFor(SubagentTurnSnapshot snapshot) {
            List<SubagentEntry> entries = new ArrayList<>();
            for (ResolvedSubagentDefinition definition : definitions) {
                entries.add(new SubagentEntry(
                        definition.agentId(),
                        definition.compiled().description(),
                        factories.get(definition.agentId()),
                        null));
            }
            return entries;
        }

        @Override
        public ReActAgent materialize(ResolvedSubagentDefinition definition, RuntimeContext parentContext) {
            throw new UnsupportedOperationException("not needed in middleware test");
        }
    }

    private static ResolvedSubagentDefinition userDefinition(
            String agentId, String description, int version) {
        return new ResolvedSubagentDefinition(
                100L + version,
                agentId,
                SubagentDefinitionSource.USER,
                version,
                "hash-" + agentId,
                new CompiledSubagentDefinition(
                        agentId,
                        description,
                        CompiledSubagentDefinition.MODE_SUBAGENT,
                        CompiledSubagentDefinition.MODEL_INHERIT,
                        8,
                        CapabilityDeclaration.OMITTED,
                        CapabilityDeclaration.empty(),
                        SubagentWorkspaceMode.ISOLATED,
                        SubagentRuntimeKind.CATALOG_DECLARATION,
                        "正文"
                )
        );
    }

    private static SubagentTurnSnapshot snapshotOf(ResolvedSubagentDefinition... definitions) {
        Map<String, ResolvedSubagentDefinition> byAgentId = new java.util.LinkedHashMap<>();
        for (ResolvedSubagentDefinition definition : definitions) {
            byAgentId.put(definition.agentId(), definition);
        }
        return new SubagentTurnSnapshot(
                "snap-1", 1L, Instant.now(), 1L, byAgentId);
    }

    private static RuntimeContext contextWith(
            DefaultAgentManager installed, SubagentTurnSnapshot snapshot) {
        return RuntimeContext.builder()
                .userId("1")
                .sessionId("s-1")
                .put(SubagentTurnSnapshot.class, snapshot)
                .put(AgentSpawnTool.CTX_AGENT_MANAGER, installed)
                .build();
    }

    /** onAgent 后返回 ctx 中生效的 manager；next 在 middleware 覆盖之后观察 ctx。 */
    private static DefaultAgentManager applyOnAgent(
            CatalogSubagentsMiddleware middleware, RuntimeContext ctx) {
        DefaultAgentManager[] seen = new DefaultAgentManager[1];
        Function<AgentInput, Flux<AgentEvent>> next = input -> {
            seen[0] = ctx.get(AgentSpawnTool.CTX_AGENT_MANAGER, DefaultAgentManager.class);
            return Flux.empty();
        };
        middleware.onAgent(mock(Agent.class), ctx, new AgentInput(List.of()), next).then().block();
        return seen[0];
    }

    private static ReasoningInput applyOnReasoning(
            CatalogSubagentsMiddleware middleware,
            RuntimeContext ctx,
            ReasoningInput input) {
        ReasoningInput[] seen = new ReasoningInput[1];
        Function<ReasoningInput, Flux<AgentEvent>> next = passed -> {
            seen[0] = passed;
            return Flux.empty();
        };
        middleware.onReasoning(mock(Agent.class), ctx, input, next).then().block();
        return seen[0];
    }
}
