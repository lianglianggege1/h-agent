package com.h.backend.chat.domain.subagentdefinition;

import com.h.backend.chat.domain.subagentdefinition.model.ResolvedSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentTurnSnapshot;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.middleware.SubagentEntry;

import java.util.List;

/**
 * Subagent 定义版本到 AgentScope child runtime 的物化 seam（设计 4.2）。
 *
 * <p>它把以下复杂度隔离在实现内部，调用方只依赖本接口：</p>
 * <ul>
 *   <li>Definition Version → AgentScope declaration/factory 的转换；</li>
 *   <li>精确 toolkit 过滤，包括"空数组表示无能力"；</li>
 *   <li>内置共享 Remote workspace 与用户 SESSION-isolated Remote workspace；</li>
 *   <li>父模型继承、steps、Skill filter、permission DENY 传播；</li>
 *   <li>leaf worker、关闭 shell、关闭 subagent/task/Agent 生成、关闭 Memory/Plan/Skill 管理；</li>
 *   <li>state store、middleware、execution config 和事件转发配置。</li>
 * </ul>
 */
public interface SubagentRuntimeFactory {

    /**
     * 把 turn snapshot 中 USER 来源的定义转换为可 spawn 的 {@link SubagentEntry}。
     *
     * <p>BUILTIN 与 synthetic {@code general-purpose} 不在此列：它们在 HarnessAgent 构建期
     * 已通过静态声明注册（设计 7.2），由 SDK 原生 factory 物化。实现必须为每次调用返回
     * 独立的 factory closure，不得共享可变状态。</p>
     *
     * @param snapshot 当前父 turn 的不可变 Catalog 快照
     * @return 用户定义对应的 entries；无用户定义时为空列表
     */
    List<SubagentEntry> entriesFor(SubagentTurnSnapshot snapshot);

    /**
     * 按 pinned version 物化 child Agent（父 turn spawn 与 child follow-up 共用）。
     *
     * <p>实现按定义版本的 workspace mode 决定隔离：BUILTIN 复用父 USER-scoped Remote
     * filesystem；USER 使用 SESSION-isolated Remote filesystem；禁止退化为 Local
     * filesystem。能力以当前平台安全政策与定义版本声明重新求交集。</p>
     *
     * @param definition 已解析到具体版本的定义（含编译结果）
     * @param parentContext 父调用上下文（提供 userId、模型与 filesystem 解析依据）
     * @return 物化后的 child Agent
     */
    ReActAgent materialize(ResolvedSubagentDefinition definition, RuntimeContext parentContext);
}
