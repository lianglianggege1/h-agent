package com.h.backend.chat.infrastructure.subagent;

import com.h.backend.chat.domain.subagentdefinition.SubagentCapabilityPolicy;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把 classpath 内置定义转换为 HarnessAgent 构建期的静态 {@link SubagentDeclaration}。
 *
 * <p>设计 7.2：调用 {@code .subagents(builtinDeclarations)} 保证即使 Catalog middleware
 * 未接管（无 turn snapshot 的执行路径），内置 Subagent 与 {@code general-purpose} 仍可
 * spawn。声明使用 {@code inlineAgentsBody} 承载发布正文、{@code SHARED} workspace、
 * leaf SUBAGENT 模式，且强制 {@code persistSession=false}。</p>
 *
 * <p>注意：SDK 声明式 factory 不传播 Remote filesystem（设计 2.4），真实物化路径是
 * Phase 2c 的 {@code SubagentRuntimeFactory}；本类只提供构建期兜底声明。
 * synthetic {@code general-purpose} 不注册，避免覆盖 SDK 原生 factory。</p>
 */
@Component
public class BuiltinSubagentDeclarations {

    private final ClasspathBuiltinDefinitionAdapter adapter;
    private final SubagentCapabilityPolicy capabilityPolicy;

    public BuiltinSubagentDeclarations(
            ClasspathBuiltinDefinitionAdapter adapter,
            SubagentCapabilityPolicy capabilityPolicy) {
        this.adapter = adapter;
        this.capabilityPolicy = capabilityPolicy;
    }

    /** 全部非 synthetic 内置定义的 SDK 声明；adapter 加载失败（非法资源）会阻止启动。 */
    public List<SubagentDeclaration> declarations() {
        return adapter.load().stream()
                .filter(definition -> !definition.synthetic())
                .map(this::toDeclaration)
                .toList();
    }

    private SubagentDeclaration toDeclaration(
            ClasspathBuiltinDefinitionAdapter.BuiltinDefinition definition) {
        var compiled = definition.compiled();
        // SDK 语义：tools 为空表示继承全部父工具；内置定义编译后至少含平台默认只读工具，
        // 空列表不可表达，因此仅在非空时设置 allowlist。
        List<String> tools = capabilityPolicy.effective(compiled, null).tools();
        SubagentDeclaration.Builder builder = SubagentDeclaration.builder()
                .name(definition.agentId())
                .description(compiled.description())
                .inlineAgentsBody(compiled.systemPrompt())
                .workspaceMode(WorkspaceMode.SHARED)
                .mode(SubagentDeclaration.Mode.SUBAGENT)
                .steps(compiled.steps())
                .persistSession(false);
        if (!tools.isEmpty()) {
            builder.tools(tools);
        }
        return builder.build();
    }
}
