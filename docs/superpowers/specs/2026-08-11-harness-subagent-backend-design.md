# Harness 协作 Agent 后端设计

- 日期：2026-08-12
- 状态：已确认并实施
- 依据：[Harness Agent PRD](../../prd/2026-08-08-harness-agent-prd.md)
- 前端契约：[Harness 协作 Agent 前端设计](./2026-08-11-harness-subagent-frontend-design.md)
- 后续扩展：[Harness 协作 Agent 后续扩展路线](./2026-08-11-harness-subagent-future-roadmap.md)

## 1. 核心决定

1. `session_id` 是所有 Agent 共用的全局会话身份，不是 AgentScope 专属概念。
2. 新增统一 `agent_sessions`，以 `parent_session_id` 自关联表达父、子、孙及任意深度。
3. `chat_sessions` 继续保存顶级聊天页面元数据，不复制子 Agent 行。
4. `harness_subagents` 只是 Harness 产品扩展，保存名称、委托和当前状态；拓扑不重复保存。
5. 父子消息共用 `chat_session_messages`。`session_id` 表示消息实际所属 Agent Session；历史字段 `session_record_id` 保留，用于顶级页面授权和级联删除。
6. `agent_runs.session_id` 写本次实际执行的 Agent Session，子运行不再伪装成父运行。
7. 不建立 `harness_session_projection_versions`，最新状态直接读取 `harness_subagents`，消息结果读取消息表。
8. 并发锁按实际 Agent Session：同一 Agent 串行，父子和兄弟可并行；用户与全局上限均为 100。

## 2. 数据模型

### 2.1 `agent_sessions`

| 字段 | 含义 |
| --- | --- |
| `id` | 数据库内部自增主键，沿用项目历史风格 |
| `session_id` | 全局 Agent 会话 ID，唯一 |
| `parent_session_id` | 直接父 Agent 会话 ID；根为空，自关联 `session_id` |
| `user_id` | 所属用户 |
| `agent_id` | 当前会话使用的 Agent 类型 |
| `gateway_subagent_id` | Gateway 内部寻址句柄；非 Gateway 子会话可为空，禁止进入 HTTP/SSE 产品契约 |
| `display_order` | 同一直接父节点下的稳定顺序；根为空 |
| `message_count` | 当前 Agent Session 的持久化消息数 |
| `created_at/updated_at` | 创建和更新时间 |

`chat_sessions.session_id` 外键关联根 `agent_sessions.session_id`。现有聊天会话由迁移回填为根节点，兼容历史数据。

### 2.2 `harness_subagents`

| 字段 | 含义 |
| --- | --- |
| `id` | 数据库内部自增主键 |
| `session_id` | 协作 Agent Session，唯一并关联 `agent_sessions` |
| `display_name` | 用户可见名称 |
| `assignment` | 原始委托的产品投影；同内容还会作为子会话首条标准 `SYSTEM` 消息持久化 |
| `status` | `AVAILABLE/RUNNING/COMPLETED/FAILED`；仅投影已接入的 AgentScope 生命周期事实 |
| `created_at/updated_at` | 首次暴露和最后状态更新时间 |

这里不保存父 ID、用户 ID、Gateway ID、顺序和 run ID；这些事实分别由 `agent_sessions`、`agent_runs` 提供，避免重复真相。

### 2.3 `chat_session_messages`

本期最终字段如下，未增加 target/subagent/run/reply/status/content-kind/metadata 等冗余列：

| 字段 | 含义 |
| --- | --- |
| `id` | 消息自增主键 |
| `session_record_id` | 历史兼容字段，关联所属顶级 `chat_sessions.id`，负责授权和级联删除 |
| `session_id` | 消息实际所属的 Agent Session，关联 `agent_sessions.session_id` |
| `user_id` | 所属用户 |
| `sequence_no` | 实际 Agent Session 内的消息顺序；每个父/子会话独立编号 |
| `message_type` | `USER/AI/SYSTEM/REASONING/IMAGE/VIDEO` 等 |
| `role_code` | `user/assistant/blocked/system` 等角色 |
| `content_text` | 文本内容 |
| `payload_json` | 既有扩展载荷 |
| `created_at` | 创建时间 |

父历史按根 `session_id` 查询，子历史按实际子 `session_id` 查询。每个实际 Agent Session 使用自己的 `agent_sessions.message_count` 原子分配 `sequence_no`；父、子及任意后代会话分别从 1 编号并独立分页，不建立跨 Session 的展示顺序。

### 2.4 `agent_runs`

表用途不变：记录每次 Agent turn 的开始、完成、模型、工具、错误和输入/输出消息关联。关键语义调整只有一项：`session_id` 永远写实际执行 Session；因此子 Agent run 可直接定位，不需要反查用户消息猜测归属。

## 3. 运行与 Gateway

`HarnessRuntime` 是应用层和 AgentScope 的版本隔离 seam：

```java
Flux<AgentEvent> streamParent(Object agentBean, String message, RuntimeContext context);
void initializeSubagent(Object agentBean, HarnessSubagentContext context);
Flux<AgentEvent> streamSubagent(Object agentBean, HarnessSubagentContext context, String message);
```

AgentScope 2.0.1 Gateway 的定向追加只传 `sessionId`，会丢失原始 `userId`，从而读到匿名状态槽。适配器因此按已登记的 `agent_id` 重新物化同类子 Agent，并显式使用原始 `(userId, child sessionId)` 调用。HTTP 请求只提交实际 `session_id`；后端沿 `agent_sessions.parent_session_id` 解析并校验顶级归属，再读取服务端保存的 Agent 类型、直接父 Session 和原始委托。并发锁、telemetry、消息、`agent_runs` 与 AgentScope 状态都始终使用请求中的实际 `session_id`。

首次 exposure 时，原始委托以普通 `MsgRole.SYSTEM`、名称 `parent_assignment` 写入子 Agent 的持久化 `AgentState`，并作为子产品会话的首条 `role_code=system/message_type=SYSTEM` 消息落库。子 Agent 继承的 middleware 在每轮模型调用前把它合并进 provider 的真实 system prompt，并从本次 model-call 视图中移除第二条 SYSTEM 副本；持久状态本身不被移除。两边都按身份幂等：重复 exposure 或再次聊天不会重复插入，也不会因上下文压缩丢掉委托。

子 Agent 追加要求由 `beginSubagentTurn` 在单个事务内完成消息落库、附件绑定和状态切换；资源绑定不再作为可独立调用的会话接口，避免消息已提交但附件或状态未提交的半完成 turn。

嵌套 exposure 使用 AgentScope event `source` 在单条运行流内解析直接父 Session：每个 `AgentStartEvent` 建立 `source → session_id` 映射，`SubagentExposedEvent` 挂到产生它的直接父节点；顶层或 Gateway 子流无 source 时回退到本次 execution Session。

AgentScope 2.0.1 的 exposure 不携带 `task`。适配器从同一流 `agent_spawn` 工具参数中短暂捕获 `task`，在 exposure 投影时写入 `assignment`；该工具参数不会进入公开事件。若 SDK 没有提供完整参数，才回退到展示 label。

## 4. 并发模型

```text
父 Agent session P 请求       ──允许
子 Agent session A 请求 1     ──允许，与 P 并行
子 Agent session A 请求 2     ──拒绝：当前 Agent 正在处理中
子 Agent session B 请求       ──允许，与 P/A 并行
```

Redis session permit key 使用实际 Agent Session ID。用户和全局 semaphore 仍防止资源耗尽，配置均为 100。产品状态在接收子追加时也从终态原子进入 `RUNNING`，作为第二层业务校验。

## 5. 消息与状态提交

- `SUBAGENT_EXPOSED`：创建 `agent_sessions` 子节点与 `harness_subagents(AVAILABLE)`，并提交首条标准 `SYSTEM` 委托消息及 AgentState 委托上下文。
- 子 `AGENT_START`：状态改为 `RUNNING`。
- 子 `AGENT_RESULT`：暂存本次完整 Assistant 结果，不提前改变终态。
- 子 `AGENT_END`：将已收到的结果写入该子 Session 消息并改为 `COMPLETED`。
- 子定向追加：用户消息写入该子 Session，状态改为 `RUNNING`，然后以原始用户与子 Session 身份调用重新物化的子 Agent。
- 失败：当前协作者改为 `FAILED`，run 记录失败。
- 父消息与子消息互不混入历史接口；父页面仍可通过同一个顶级归属执行授权。

## 6. HTTP 契约

- 会话打开：Harness 会话的 `subagents` 字段直接返回协作者列表；列表项含 `sessionId` 与 `parentSessionId`，可恢复完整树。
- 独立刷新：`GET /api/chat/sessions/{sessionId}/subagents` 直接返回同一种协作者列表，不额外包装 snapshot 对象。
- 最新状态：`GET /api/chat/sessions/{rootSessionId}/subagents`，响应数据直接为协作者数组。
- 父子历史：统一为 `GET /api/chat/sessions/{sessionId}/messages`。
- 父子发送：统一使用 `POST /api/chat/messages/stream`，请求只带实际执行 `sessionId`，没有 `target`。
- 子 Agent 完成边界将本轮思考聚合为 `REASONING` 消息，并在最终 `AI` 消息之前原子写入子会话历史；重复完成投影不得产生重复消息。
- SSE `harness_event` 不提供第二套 target 身份；产品状态更新只读取 `projection.subagent.sessionId`，不返回 Gateway 句柄。

“协作快照”仅是当前协作树的查询结果，不是独立事件存储、版本表或消息副本。

## 7. 代码审查要点

- 新表和新/改语义字段必须有 PostgreSQL `COMMENT`。
- 跨用户或无法解析到有效 Harness 根节点的 `sessionId` 必须返回不存在，不能泄露其他用户信息。
- `source.path` 只能在当前事件流内解析拓扑，不能成为持久身份或授权键。
- 任何父/子消息入口都必须使用数据库原子序号分配。
- 不能把子 Agent delta 拼入父 Assistant 气泡。
