package com.h.backend.chat.infrastructure.subagent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden test：固定 AgentScope 2.0.1 的 Subagents 段输入与平台段输出。
 * 升级依赖时先跑本测试；标记失配说明 SDK 模板已变，需要同步修订
 * {@link SubagentsPromptAdapter} 的切割标记与平台段内容。
 */
class SubagentsPromptAdapterTest {

    /** 与 2.0.1 SUBAGENT_SECTION_TEMPLATE 一致的最小忠实片段（含 agent_send/agent_list 指引）。 */
    private static final String SDK_SECTION = """

            ## Subagents

            You have access to subagent tools for spawning and coordinating isolated subagents.

            ### Agent Tools

            **`agent_spawn`** — Spawn an isolated subagent
            - `agent_id` (required): which subagent to instantiate
            - `task` (optional): initial prompt; omit to create a persistent session
            - `label` (optional): human-readable name for referencing via send

            **`agent_send`** — Send a follow-up message to an existing subagent
            - `agent_key`: copy the **full value** after `agent_key:` from spawn output

            **`agent_list`** — List active subagents

            ### Available agent ids
            - `general-purpose`: General purpose agent for research and analysis
            - `researcher`: 搜集和核对事实，区分证据与推断，产出带出处的结论
            """;

    private static final String SDK_TASK_SUMMARY = """
            \n### Async tasks (current session)
            - task_id: task-1  agent: researcher  status: running  started: 2026-08-21T10:00Z
            """;

    @Test
    void replacesSdkSectionAndPreservesTaskSummary() {
        String systemText = "你是协作工作台的父 Agent。" + SDK_SECTION + SDK_TASK_SUMMARY;

        String rewritten = SubagentsPromptAdapter.replaceSdkSection(systemText, List.of(
                new SubagentsPromptAdapter.AgentListing("general-purpose", "通用协作"),
                new SubagentsPromptAdapter.AgentListing("my-reviewer", "代码审查")
        ));

        // 平台段生效：不出现 agent_send/agent_list 指引，要求省略 label。
        assertTrue(rewritten.contains("## Subagents"));
        assertTrue(rewritten.contains("- `my-reviewer`: 代码审查"));
        assertFalse(rewritten.contains("`agent_send`"));
        assertFalse(rewritten.contains("`agent_list`"));
        assertTrue(rewritten.contains("Do NOT pass `label`"));
        // SDK 段被整体移除。
        assertFalse(rewritten.contains("human-readable name for referencing via send"));
        assertFalse(rewritten.contains("- `researcher`: 搜集和核对事实"));
        // task 摘要被保留。
        assertTrue(rewritten.contains("### Async tasks (current session)"));
        assertTrue(rewritten.contains("task_id: task-1"));
        // 父 Agent 原始 system prompt 保留。
        assertTrue(rewritten.startsWith("你是协作工作台的父 Agent。"));
    }

    @Test
    void appendsPlatformSectionWhenSdkMarkerMissing() {
        String rewritten = SubagentsPromptAdapter.replaceSdkSection(
                "基础系统提示", List.of(new SubagentsPromptAdapter.AgentListing("my-reviewer", "审查")));
        assertTrue(rewritten.startsWith("基础系统提示\n"));
        assertTrue(rewritten.contains("- `my-reviewer`: 审查"));
        assertFalse(rewritten.contains("agent_send"));
    }

    @Test
    void rendersEmptyListingGracefully() {
        String section = SubagentsPromptAdapter.renderPlatformSection(List.of());
        assertTrue(section.contains("## Subagents"));
        // 空列表渲染为占位而非模板裸露的 %s。
        assertFalse(section.contains("%s"));
    }

    @Test
    void platformSectionNeverLeadsToBlankDescription() {
        String section = SubagentsPromptAdapter.renderPlatformSection(List.of(
                new SubagentsPromptAdapter.AgentListing("agent-x", null)
        ));
        assertTrue(section.contains("- `agent-x`: (no description)"));
    }
}
