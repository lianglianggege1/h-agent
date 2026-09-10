# 自动化提案卡片原位展示实施改造文档

日期：2026-09-10

状态：待实施

范围：自动化提案的后端定位、查询接口、聊天时间线渲染、状态更新与验证。

## 1. 问题与目标

当前聊天页先渲染全部聊天消息，再统一渲染当前 Session 的全部自动化提案。因此提案卡片无论在哪一轮生成，都会出现在聊天记录末尾；用户继续聊天、刷新页面或重新加载历史后，卡片仍跟随末尾移动。

现有数据只记录 `automation_proposals.source_session_id`，只能说明提案属于哪个 Session，不能说明它属于哪一次对话。前端若仅按 `createdAt` 将消息与提案混排，会受到数据库时间精度、流式消息落库时机、历史分页和状态更新时间影响，无法形成可靠位置。

改造目标：

1. 提案卡片固定在生成它的那一轮助手回复下方。
2. 助手回复失败且没有落库时，卡片固定在该轮用户消息下方。
3. 卡片第一次展示后，确认、取消、过期、刷新、加载历史及后续聊天均不改变位置。
4. `PENDING` 卡片默认展开；`CONFIRMED`、`DISCARDED`、`EXPIRED` 默认折叠，仍可查看原内容。
5. 状态更新不把页面强制滚动到聊天底部。
6. 提案继续由自动化领域拥有，不进入模型上下文，也不伪装成普通聊天消息。

本次不改变提案确认、任务创建和开启语义，不调整自动化执行及 XXL-Job 链路。

## 2. 设计决定

### 2.1 提案是对话轮次的附属卡片

提案不是用户或助手说出的一句话。它具有 `PENDING → CONFIRMED / DISCARDED / EXPIRED` 状态变化、确认按钮和任务引用，是自动化领域中的业务对象。

因此不新增 `AUTOMATION_PROPOSAL` 聊天消息类型，也不把完整提案快照写入 `chat_session_messages.payload_json`。这样可以避免：

- 可变业务状态混入原则上追加式的聊天消息；
- 提案卡片被计入会话消息数量或模型记忆；
- 同一状态同时存在于消息 payload 和 `automation_proposals`，形成双写；
- 后续修改卡片展示字段时需要迁移聊天历史。

提案保留在 `automation_proposals`，新增与生成它的 `AgentRun` 的不可变关联。后端查询模块负责把这个关系转换为前端可直接使用的消息锚点。

### 2.2 以 AgentRun 表达“生成提案的那一轮”

工具被调用时，最终助手消息通常尚未落库，但当前 `AgentRun` 已经存在，并已关联本轮用户消息。运行成功后，`agent_runs.assistant_message_id` 会指向最终助手消息。

新增关系：

```text
AutomationProposal.sourceAgentRunId
    → AgentRun.userMessageId
    → AgentRun.assistantMessageId（成功完成后存在）
```

卡片锚点按以下规则解析：

| AgentRun 状态 | 锚点消息 | 是否向聊天页发布 |
| --- | --- | --- |
| RUNNING / WAITING_APPROVAL | 无 | 否 |
| SUCCEEDED，存在 assistantMessageId | assistantMessageId | 是 |
| SUCCEEDED，不存在 assistantMessageId | userMessageId | 是 |
| FAILED / CANCELLED | userMessageId | 是 |

锚点只在 Run 进入终态后对前端可见。`assistant_message_id` 与 `user_message_id` 在终态后不可修改，所以卡片第一次出现时位置已经稳定，不需要在多个执行器完成分支中额外回写提案。

这也覆盖流式响应中断：只要后端最终成功保存助手消息，卡片仍位于助手消息后；如果本轮失败，卡片位于发起本轮的用户消息后，不会丢到会话末尾。

### 2.3 服务器确定关联身份

模型和浏览器都不能提交 `sourceAgentRunId` 或 `anchorMessageId`。`AutomationProposalModule` 根据已鉴权的 `userId + sourceSessionId` 查找当前唯一开放的顶级 AgentRun，并保存其 ID。

聊天并发许可与 `AgentRunService.hasOpenRun(sessionId)` 已保证同一实际 Agent Session 最多存在一个开放 Run。新增查询仍需校验：

- Run 属于当前用户；
- Run 属于当前 Session；
- Run 状态允许工具调用；
- 找不到或找到多个 Run 时拒绝创建提案并记录错误，不能猜测最近一次消息。

标准聊天和 Harness 顶级聊天使用同一规则。若以后允许协作 Agent 直接创建提案，必须先由统一会话寻址模块解析顶级 Session 和顶级 AgentRun；不能把子 Agent 消息 ID 直接当成顶级聊天锚点。

## 3. 后端实施

### 3.1 数据结构

新增迁移 `V20260910_02__anchor_automation_proposals_to_agent_runs.sql`，在 `automation_proposals` 增加：

```sql
ALTER TABLE automation_proposals
    ADD COLUMN source_agent_run_id BIGINT;

ALTER TABLE automation_proposals
    ADD CONSTRAINT fk_automation_proposals_source_run
    FOREIGN KEY (source_agent_run_id)
    REFERENCES agent_runs(id)
    ON DELETE CASCADE;

CREATE INDEX idx_automation_proposals_source_run
    ON automation_proposals(source_agent_run_id);

CREATE INDEX idx_automation_proposals_session_created
    ON automation_proposals(user_id, source_session_id, created_at, id);
```

聊天来源的提案必须具有 `source_agent_run_id`。目前系统未上线，不为旧提案编写基于时间的猜测回填；开发库中的旧提案可以清理或重建。若管理后台将来支持不依赖聊天创建提案，应明确使用另一种来源类型，而不是让聊天提案静默缺失关联。

同步修改：

- `AutomationProposal`；
- `AutomationProposalEntity`；
- proposal mapper 与 repository 映射；
- 测试 fixture。

### 3.2 AgentRun 查询接口

在 `AgentRunService` 增加一个面向业务语义的查询：

```java
AgentRunSummary requireOpenRun(Long userId, String sessionId);
```

实现只接受能够执行工具的开放状态，返回 Run ID、用户消息 ID和实际 Session ID。不要在 Automation 工具中直接查询 mapper，也不要使用 `selectLatest...` 猜测关联。

该接口把“当前会话中的工具调用属于哪个 Run”隐藏在 AgentRun 模块内部。调用方无需理解 `RUNNING`、`WAITING_APPROVAL`、会话所有权和多行异常处理，测试也通过同一接口验证行为。

### 3.3 提案创建

`AutomationProposalModule.createChangeProposal(...)` 继续接收可信的 `userId` 和运行时提供的 `sourceSessionId`，内部执行：

1. 校验并归一化任务命令；
2. 解析当前开放 AgentRun；
3. 校验 Run 所属用户和 Session；
4. 在同一事务内保存 proposal、`source_agent_run_id` 和审计事件；
5. 返回 proposal ID 给工具。

两个工具适配器仍只提供当前 Session：

- `LangChain4jAutomationTool` 使用 `@ToolMemoryId` 解析 Session；
- `AgentScopeAutomationTool` 使用 `RuntimeContext` 的 Session。

工具参数中不增加消息 ID或 Run ID，避免模型伪造位置。

### 3.4 时间线查询

保留现有接口：

```http
GET /api/automations/proposals?sessionId={sessionId}
```

将 repository 查询扩展为 proposal 与 `agent_runs` 的受控联查，DTO 增加：

```json
{
  "sourceAgentRunId": 51,
  "anchorMessageId": "2048",
  "anchorPlacement": "AFTER"
}
```

规则：

- Run 未终结时，`anchorMessageId=null`，前端暂不展示；
- Run 成功且有助手消息时返回助手消息 ID；
- 其他终态返回用户消息 ID；
- `anchorPlacement` 当前固定为 `AFTER`，如果没有第二种布局需求，可以不进入数据库；
- 多个提案锚定同一消息时按 `createdAt ASC, id ASC` 返回；
- 查询必须同时验证 proposal 和 AgentRun 均属于当前用户及请求 Session。

推荐在应用层定义只读结果 `AnchoredProposalView`，由 repository 一次返回所需关联字段。Controller 不自行拼接多个查询，避免 N+1 和不同调用方产生不同锚点规则。

### 3.5 状态变化

确认、取消和过期只更新 proposal 的业务字段：

- `status`；
- `result_task_id`；
- `confirmed_at / confirmed_by`；
- `updated_at`。

不修改 `source_agent_run_id`，也不重新计算和保存另一个位置。卡片位置由终态 AgentRun 的不可变消息关系决定。

### 3.6 失败与恢复

- 提案创建成功、AgentRun 最终失败：卡片锚定用户消息，状态仍可操作。
- 提案创建后进程退出、AgentRun 长时间不终结：先由 AgentRun 故障恢复使 Run 进入失败终态，随后查询自然得到用户消息锚点。不要在前端用超时把卡片追加到底部。
- 锚点消息或 Session 被删除：外键级联删除该聊天提案；已创建的 AutomationTask 按现有 Session 删除策略处理。
- proposal 与 source Run 所属 Session 不一致：作为数据损坏拒绝返回，并记录结构化错误。

## 4. 前端实施

### 4.1 类型调整

扩展 `AutomationProposal`：

```ts
sourceAgentRunId: number;
anchorMessageId: string | null;
anchorPlacement: "AFTER";
```

提案状态仍以接口响应为唯一真相，不复制到聊天消息 payload。

### 4.2 构建统一时间线

删除聊天页当前的“所有消息结束后 `automationProposals.map(...)`”渲染。

在 `frontend/lib/chat-message-state.ts` 增加纯函数：

```ts
attachAutomationProposals(
  turns: RenderableTurn[],
  proposals: AutomationProposal[],
): RenderableTimelineItem[]
```

`RenderableTimelineItem` 只有两类：

```ts
type RenderableTimelineItem =
  | { kind: "turn"; turn: RenderableTurn }
  | { kind: "automation-proposal"; proposal: AutomationProposal };
```

算法：

1. 先使用现有 `toRenderableTurns(messages)` 完成 reasoning、image、assistant 的组合；
2. 按 `turn.id` 建立锚点索引；
3. 输出每个 turn 后，紧接输出锚定该 ID 的 proposal；
4. 同一锚点的多个 proposal 按 `createdAt + id` 排序；
5. `anchorMessageId=null` 或锚点尚未进入当前分页的数据不输出；
6. 不允许把未匹配提案兜底追加到数组末尾。

把合并逻辑留在纯模块中，聊天页只渲染时间线项。这样位置、排序和分页行为由一个接口统一决定，测试无需挂载整页。

### 4.3 分页与刷新

聊天页仍可按 Session 一次读取全部提案。消息分页决定卡片何时可见：

- 锚点消息在当前已加载消息中：显示卡片；
- 锚点在更早的历史页：暂不显示；
- 用户加载到该消息后：卡片随该位置出现；
- 刷新页面：相同 message ID 得到相同位置。

不能按 proposal 的 `createdAt` 决定它属于当前哪一页，也不能为了显示提案自动加载全部历史消息。

### 4.4 卡片状态与折叠

`AutomationProposalCard` 保持原有确认和取消动作，增加展示规则：

| 状态 | 默认展示 |
| --- | --- |
| PENDING | 展开完整指令、计划、Agent、权限和操作按钮 |
| CONFIRMED | 折叠为标题、计划摘要和“已创建并开启”状态 |
| DISCARDED | 折叠为标题与“已取消”状态 |
| EXPIRED | 折叠为标题与“已过期”状态 |

终态卡片使用 `<details>` 或等价的受控展开方式允许查看原提案。用户在本次页面手动展开后，轮询刷新状态时不应强制折叠。

### 4.5 状态更新不改变滚动位置

当前聊天页的自动滚动 effect 同时依赖 `automationProposals` 和 `messages`，因此确认卡片也可能触发滚到底部。

调整为：

- 新聊天消息到达时沿用现有底部跟随策略；
- proposal 状态更新不触发 `messageEndRef.scrollIntoView()`；
- 新 proposal 首次出现时，如果其锚点处于视口附近则保持当前位置；
- 若用户已在底部，可继续保持底部吸附；
- 若用户浏览旧消息，不抢夺滚动位置。

可以增加一个轻量的“待确认 1”提示，点击后使用锚点元素的 `scrollIntoView` 返回原卡片。提示只负责导航，不复制卡片或操作按钮。

### 4.6 实时状态与去重

第一阶段继续使用现有请求刷新即可：流结束后查询 proposals，卡片此时已获得终态 Run 对应的锚点。

如果后续增加 `automation_proposal_created/updated` SSE 事件，事件和历史查询必须使用相同 `proposal.id` 合并；SSE 不创建临时卡片 ID，也不改变 `anchorMessageId`。刷新接口始终是恢复真相。

## 5. 目标时序

```text
用户消息落库
  → 创建 AgentRun（已关联 userMessageId）
  → Agent 调用 create_automation_task
  → Proposal 保存 sourceAgentRunId
  → 助手消息落库
  → AgentRun 终结并关联 assistantMessageId
  → 前端查询 AnchoredProposalView
  → 卡片首次出现在该助手消息下方
  → 确认 / 取消 / 过期仅原位更新状态
```

失败时序：

```text
Proposal 已创建
  → 助手最终消息未生成
  → AgentRun 进入 FAILED / CANCELLED
  → anchorMessageId 解析为 userMessageId
  → 卡片固定出现在该用户消息下方
```

## 6. 涉及文件

后端预计修改：

- `backend/src/main/resources/db/migration/V20260910_02__anchor_automation_proposals_to_agent_runs.sql`；
- `automation/domain/AutomationProposal.java`；
- `automation/application/AutomationProposalModule.java`；
- `automation/application/AutomationProposalRepository.java`；
- proposal entity、mapper、repository implementation；
- `chat/application/AgentRunService.java` 及其实现、mapper；
- `automation/interfaces/dto/AutomationProposalDto.java`；
- `automation/interfaces/web/AutomationController.java`。

前端预计修改：

- `frontend/lib/automations.ts`；
- `frontend/lib/chat-message-state.ts`；
- `frontend/lib/chat-message-state.test.mjs`；
- `frontend/app/chat/page.tsx`。

## 7. 测试计划

### 7.1 后端

通过 AutomationProposal 模块接口验证：

1. 工具创建提案时绑定当前用户和 Session 的唯一开放 AgentRun；
2. 不存在开放 Run 时拒绝创建，不猜测最近消息；
3. 成功 Run 返回 `assistantMessageId` 作为锚点；
4. 失败或取消 Run 返回 `userMessageId` 作为锚点；
5. 非终态 Run 不向时间线发布；
6. 确认、取消、过期后锚点不变；
7. 多个提案属于同一 Run 时顺序稳定；
8. 其他用户或其他 Session 无法读取锚点；
9. proposal、Run、消息关系不一致时拒绝返回；
10. Session 删除后的级联行为符合约定。

### 7.2 前端纯逻辑

通过 `attachAutomationProposals` 验证：

1. 卡片紧跟指定助手消息；
2. 后续追加十条消息后卡片位置不变；
3. 状态由 PENDING 变为 CONFIRMED 后位置不变；
4. 刷新并重新构建时间线后位置不变；
5. 锚点未加载时不错误追加到底部；
6. 加载包含锚点的历史页后卡片出现在正确位置；
7. 同一消息的多个卡片顺序稳定；
8. reasoning 与 assistant 合并后仍能通过 assistant message ID 命中；
9. 失败 Run 使用 user message ID时位置正确。

### 7.3 浏览器验收

1. 创建提案，继续发送多轮消息，卡片停留在原回复下方；
2. 刷新和切换 Session 后位置保持；
3. 确认后卡片原位折叠，页面不跳到底部；
4. 从“待确认”提示跳转时定位原卡片；
5. 加载较早历史消息时不发生明显滚动跳变；
6. 验证标准聊天和 Harness 顶级聊天。

## 8. 实施顺序

1. 增加 proposal 与 AgentRun 关联及后端模块测试；
2. 实现服务端可信 Run 解析和 `AnchoredProposalView`；
3. 扩展 proposal DTO，保持确认、取消接口兼容；
4. 增加前端纯时间线合并函数及测试；
5. 替换聊天页末尾渲染，调整滚动策略；
6. 增加终态折叠和待确认定位；
7. 完成前后端测试、生产构建和浏览器验收；
8. 删除旧的末尾追加逻辑及无用状态分支。

后端关联与前端渲染必须在同一次发布中切换。不能先停止末尾渲染再等待后端提供锚点，也不能先返回锚点却继续渲染第二张末尾卡片。

## 9. 验收标准

- 每张聊天提案都能追溯到唯一 AgentRun 和发起消息；
- 卡片首次出现后不会因新消息、状态更新、刷新或分页改变位置；
- 提案不进入模型上下文，不增加聊天消息计数；
- PENDING 操作能力保持完整，终态卡片默认折叠且可回看；
- 未匹配锚点的提案不会被追加到聊天末尾；
- 前端状态更新不会抢夺用户当前滚动位置；
- 标准聊天与 Harness 顶级聊天行为一致。
