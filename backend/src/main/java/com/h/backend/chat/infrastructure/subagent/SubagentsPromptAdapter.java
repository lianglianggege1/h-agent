package com.h.backend.chat.infrastructure.subagent;

import java.util.List;

/**
 * 平台拥有的 Subagents system prompt 段适配器（设计 7.2 第 6 条）。
 *
 * <p>AgentScope 2.0.1 的 {@code SubagentsMiddleware} 会在每轮 reasoning 前把一段
 * {@code ## Subagents} 说明追加进 SYSTEM 消息；该段指导模型调用 {@code agent_send} /
 * {@code agent_list}，与平台 DENY 后的工具面不符。本适配器在 Catalog middleware 收到
 * SDK 已改写的消息后：</p>
 * <ol>
 *   <li>移除 SDK 生成的 Subagents 说明段（保留其后追加的 async task 摘要）；</li>
 *   <li>写入平台拥有的完整说明段：只列当前 turn snapshot 的 {@code agent_id +
 *       description}，只指导 {@code agent_spawn} 与仍开放的 task 工具，要求省略
 *       {@code label}，不出现 {@code agent_send}/{@code agent_list}，也不包含任何
 *       Definition 正文。</li>
 * </ol>
 *
 * <p><strong>版本敏感：</strong>段落定位依赖 2.0.1 模板的字面标记（段头
 * {@code "\n## Subagents\n"}、task 摘要头 {@code "\n### Async tasks (current session)\n"}）。
 * 升级 AgentScope 时必须先跑 {@code SubagentsPromptAdapterTest} 的 golden test；
 * 标记失配时本类不切割原文、只追加平台段并交由调用方告警。</p>
 */
public final class SubagentsPromptAdapter {

    /** SDK 2.0.1 段头标记；见 SubagentsMiddleware.SUBAGENT_SECTION_TEMPLATE。 */
    static final String SDK_SECTION_START = "\n## Subagents\n";

    /** SDK 2.0.1 在 Subagents 段之后追加的 task 摘要头标记；见 buildTaskSummary。 */
    static final String SDK_TASK_SUMMARY_START = "\n### Async tasks (current session)\n";

    private SubagentsPromptAdapter() {}

    /** 模型可见的 agent 概要条目：仅 ID 与 description，不携带正文。 */
    public record AgentListing(String agentId, String description) {}

    /**
     * 用平台段替换 system 文本中 SDK 生成的 Subagents 段。
     *
     * <p>SDK 的追加永远位于 SYSTEM 消息尾部（{@code prependToSystemMessage} 合并语义），
     * 因此从段头标记到文末整体替换，仅当 task 摘要标记位于段头之后时保留其后的内容。
     * 未找到段头标记时不切割原文，直接在尾部追加平台段。</p>
     *
     * @param systemText SDK middleware 改写后的 SYSTEM 消息文本
     * @param listings 当前 turn snapshot 的 agent 概要（顺序即展示顺序）
     * @return 替换后的 SYSTEM 消息文本
     */
    public static String replaceSdkSection(String systemText, List<AgentListing> listings) {
        String base = systemText != null ? systemText : "";
        String section = renderPlatformSection(listings);
        int start = base.indexOf(SDK_SECTION_START);
        if (start < 0) {
            return base.isEmpty() ? section : base + "\n" + section.stripLeading();
        }
        int taskStart = base.indexOf(SDK_TASK_SUMMARY_START, start);
        if (taskStart > start) {
            String preservedTail = base.substring(taskStart);
            return base.substring(0, start) + section + preservedTail;
        }
        return base.substring(0, start) + section;
    }

    /** 渲染平台段：以 {@code \n## Subagents\n} 开头，与 SDK 段的拼接位置语义一致。 */
    static String renderPlatformSection(List<AgentListing> listings) {
        StringBuilder agentList = new StringBuilder();
        for (AgentListing listing : listings) {
            if (listing == null || listing.agentId() == null || listing.agentId().isBlank()) {
                continue;
            }
            String description = listing.description() == null || listing.description().isBlank()
                    ? "(no description)"
                    : listing.description();
            agentList.append("- `").append(listing.agentId()).append("`: ")
                    .append(description).append('\n');
        }
        return PLATFORM_SECTION_TEMPLATE.formatted(agentList.toString().stripTrailing());
    }

    private static final String PLATFORM_SECTION_TEMPLATE =
            """

            ## Subagents

            You have access to platform-managed subagents for delegated or background work.
            Subagents are ephemeral leaf workers — each takes one task, runs in isolation, and returns a single result.

            ### Agent Tools

            **`agent_spawn`** — Spawn an isolated subagent
            - `agent_id` (required): which subagent to instantiate, from the list below
            - `task` (optional): the complete, self-contained prompt for the subagent
            - `timeout_seconds`: wait time; 0=fire-and-forget (returns task_id), default=30, max=600
            - Do NOT pass `label` — labels are not supported on this platform; always omit the parameter

            ### Task Tools (for async/background operations)

            **`task_output`** — Retrieve the result of a background task by task_id.
            - **You rarely need this.** Completed tasks are pushed back to you automatically as a `<system-reminder>` block before your next reasoning step.
            - Use `task_output(block=false)` when you need a specific task's latest status/result, the pushed summary was truncated, or you intentionally want to check progress while continuing other reasoning.
            - Use `task_output(block=true)` only for one specific task you are ready to wait for; for multiple tasks use `wait_async_results`.

            **`wait_async_results`** — Wait for background-task results when the next step depends on them.
            - Prefer `wait_async_results(task_ids=...)`: waits until those tasks are terminal and **returns their results in the tool output**.
            - Prefer `wait_async_results(wait_all=true)`: waits for the snapshot of currently non-terminal background tasks (tasks started later are not added) and **returns their results**.

            **`task_cancel`** — Cancel a running background task by task_id. No effect on already-completed tasks.

            **`task_list`** — List all in-flight background tasks. Completed tasks fall off this list after they're pushed to you.

            ### Background task flow
            1. Spawn with `timeout_seconds=0` to fire-and-forget; the response gives you a task_id.
            2. Continue with independent work. If you need fresh state, use `task_output(block=false)` for selected tasks.
            3. If a later step must wait for a known group, call `wait_async_results(task_ids=...)`; if it must wait for every current background task, call `wait_async_results(wait_all=true)`.
            4. If the agent has nothing useful to do, hand control back to the user — they'll prompt again when ready and the next reasoning round will surface any completions.

            ### Timeout promotion
            When a sync spawn exceeds its timeout, the task is **not lost** — it is automatically promoted to a background task. You receive `status: timeout_promoted` with a `task_id`. Treat it like any async task: the result will be pushed back to you automatically as a `<system-reminder>`. Do NOT retry the same task — it is already running in the background.

            ### Available agent ids
            %s

            ### When to use subagents
            - When a task is complex and multi-step, and can be fully delegated in isolation
            - When a task is independent of other tasks and can run in parallel
            - When a task requires focused reasoning or heavy context usage that would bloat the main thread
            - When you only care about the output, not the intermediate steps (e.g. research → synthesized report)

            ### When NOT to use subagents
            - If the task is trivial (a few tool calls or simple lookup)
            - If you need to see intermediate reasoning or steps after completion
            - If delegating does not reduce token usage, complexity, or context switching

            - Subagent results are NOT visible to the user — always summarize them in your response
            """;
}
