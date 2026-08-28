# Harness Agent Mem0 Memory Design

> **状态：已被取代，禁止按本文实施。** 2026-08-27 起，项目不再替换 Harness SDK 内建长期
> 记忆，改为建立项目自有的 LangChain4j + Mem0 长期记忆模块。新设计见
> `docs/superpowers/specs/2026-08-27-langchain4j-mem0-long-term-memory-design.md`。本文仅作历史决策与
> Mem0 contract/身份/控制索引设计参考。

- Date: 2026-08-24
- Revised: 2026-08-26
- Scope: `harness-agent` 用户/Worker/任务三级长期记忆
- Status: 已被 `2026-08-27-langchain4j-mem0-long-term-memory-design.md` 取代，仅作历史参考

## 1. 结论

将 Harness 的用户级长期记忆从 `workspace_files` 中的 `MEMORY.md` / `memory/YYYY-MM-DD.md`
迁移到 mem0，但不改动以下三类状态：

1. `agent_state_snapshots`：AgentScope 会话工作状态与恢复真相。
2. Harness session JSONL、compaction summary：会话回放和上下文窗口压缩。
3. 普通/领域 Agent 的 `RedisChatMemoryStore` 与 `chat_memory_snapshots`：它们是另一套
   LangChain4j chat memory，不属于本次改造。

推荐在项目内建立两个调用方导向的深 Module：`HarnessMemoryRuntime` 服务父 Agent 与 Worker 的分层 recall，
`UserMemoryCatalog` 只服务用户管理查询和命令。mem0 HTTP、PostgreSQL 控制索引、capture outbox、远端
幂等协调、认证、DTO、过滤器和错误处理全部属于内部实现，不能泄漏给 Agent 或 controller。Java 后端
通过 HTTP 接入 mem0；不把 Python/Node SDK 嵌入 JVM。

第一期只实现自托管 mem0 OSS REST contract。mem0 的部署、数据库、模型、embedding 与运维步骤由
项目外提供，本设计只约定应用侧 endpoint、认证、调用语义与故障行为，不包含基础设施实施。

切换完成后 mem0 是用户长期记忆**正文与语义演化**的唯一真相源；PostgreSQL 控制索引是项目自有的
owner、version、稳定分类和 saga 状态真相，不保存正文。旧 `MEMORY.md` 与每日台账只在迁移和 shadow
阶段只读保留，不永久双写，也不作为正式写入开始后的数据回滚目标。

## 2. 当前实现

### 2.1 当前长期记忆链路

`HarnessAgentConfig` 使用 AgentScope Harness 2.0.1：

```text
用户/父 Agent turn
    │
    ├─ WorkspaceContextMiddleware
    │    └─ 把 MEMORY.md 全文（受 token 限制）注入 system prompt
    │
    ├─ memory_search / memory_get / memory_save
    │    └─ 读写 MEMORY.md 与 memory/YYYY-MM-DD.md
    │
    ├─ MemoryFlushMiddleware
    │    └─ LLM 从会话抽取内容，追加到每日账本
    │
    └─ MemoryMaintenanceMiddleware
         └─ 定期把每日账本合并、去重到 MEMORY.md

RemoteFilesystemSpec(IsolationScope.USER)
    └─ PostgresBaseStore
         └─ workspace_files
```

用户隔离 namespace 是：

```text
agents / harness-agent / users / {userId} / root    -> MEMORY.md
agents / harness-agent / users / {userId} / memory  -> YYYY-MM-DD.md
```

### 2.2 不应混淆的“记忆”

| 数据 | 当前存储 | 本次是否迁移 |
|---|---|---:|
| Harness 用户长期事实、偏好、决策 | `workspace_files` 的 Markdown | 是 |
| Harness 对话工作状态 | `agent_state_snapshots` | 否 |
| Harness session log / compaction summary | Workspace/session state | 否 |
| 普通/领域 Agent 消息窗口 | Redis + `chat_memory_snapshots` | 否 |
| Skills、plans、tasks、artifacts | `workspace_files` | 否 |

## 3. 目标与非目标

### 3.1 目标

1. 用语义检索替代每轮注入整份 `MEMORY.md`。
2. 用户、Worker、任务三级长期记忆可分别按 `user_id`、`user_id + agent_id`、
   `user_id + agent_id + run_id` 定位，并严格按服务端认证身份隔离。
3. 显式“记住……”具备可确认的写入结果；自动抽取可异步、可重试、可观测。
4. mem0 故障不应令正常聊天整体不可用。
5. 允许部署级 `filesystem -> shadow -> mem0` 灰度；正式写入 mem0 后，应用只能回滚到仍支持 mem0 的稳定版本。
6. 保持会话压缩、会话恢复、子 Agent 生命周期和普通聊天记忆行为不变。

### 3.2 非目标

1. 不把 AgentScope 的 `AgentStateStore` 改成 mem0。
2. 不把原始完整聊天记录当作长期记忆全部写进 mem0。
3. 不把 `run_id` 任务记忆替代为 AgentState、session log 或 compaction summary；它只保存经筛选后需要
   语义召回的任务事实。
4. 不在第一期启用 graph memory、跨用户共享记忆或不属于任何用户的全局 agent persona memory。
5. 不设计或实施 mem0 Server、数据库、模型、embedding、网络和密钥基础设施。

## 4. 关键设计决策

### 4.1 mem0 作为独立外部依赖

后端是 Java/Spring；mem0 官方 REST Server 向任意 HTTP 客户端提供 add/search/update/delete，适合
放在独立部署单元。项目内只实现 HTTP adapter，不泄漏 mem0 DTO 到 domain/application 包。

第一期只实现 OSS endpoint：`POST/GET/PUT/DELETE /memories`、`POST /search` 与
`GET /memories/{id}/history`，使用 `X-API-Key`。不实现 Mem0 Platform V3 Adapter，也不在配置中保留
`flavor` 分支。外部调用方只认识两个项目 Module；内部 `Mem0Gateway` seam 有生产 HTTP Adapter 与测试
Fake Adapter，调用方和 Module interface 都不学习外部 HTTP contract。

### 4.2 三维实体 scope 与身份映射

固定版本的自托管 mem0 OSS 把调用时提供的 `user_id`、`agent_id`、`run_id` 一并写入同一条 memory
payload，并支持用提供字段的 AND 条件逐级缩小结果。第一期据此建立三种领域 scope：

| scope kind | mem0 entity fields | 语义 | 示例 |
|---|---|---|---|
| `USER` | `user_id` | 跟随用户跨 Worker、跨任务复用 | 名字、长期偏好 |
| `AGENT` | `user_id + agent_id` | 当前用户与某个稳定 Worker 之间的长期记忆 | coder 的技术栈、writer 的写作上下文 |
| `RUN` | `user_id + agent_id + run_id` | 当前 Worker 在一个任务/会话内的记忆 | coder 在 task1 修复 BUG-1024 |

服务端映射规则：

```text
mem0 user_id  = "h-agent:user:"  + authenticatedUserId
mem0 agent_id = "h-agent:agent:" + stableLogicalAgentId
mem0 run_id   = "h-agent:run:"   + stableTaskSessionId
```

约束：

1. 每条应用用户可见 memory 都必须有 `user_id`；不创建只带 `agent_id` 或 `run_id` 的用户数据。
2. `run_id` 非空时 `agent_id` 必须非空；scope 只允许 `USER -> AGENT -> RUN` 逐级收窄。
3. `agent_id` 使用不可变逻辑 Agent ID，不使用可改名/可重复的 display name。定义版本放 metadata，
   使同一 Worker 升级后默认继承既有记忆。
4. `run_id` 使用稳定任务/子会话 ID，不使用每轮变化的本地 `agentRunId`、tool call id 或一次性
   execution id；后者只用于来源审计和幂等 key。
5. `scope_kind` 同时写入 metadata 和 PostgreSQL 控制索引。运行时精确召回必须同时过滤
   `scope_kind`，避免 `user_id` 宽查询把所有 Worker/任务记忆无差别注入模型。
6. Mem0 Platform 的实体分区语义与 OSS 不同；本项目只接受固定 OSS 版本，contract test 必须证明
   多字段同记录、AND 查询和 `user_id` 宽查询行为，不能拿 Platform 文档替代真实 contract。
7. 当前自托管 OSS `/search` 的 entity ID 使用顶层 `user_id/agent_id/run_id`；`scope_kind` 作为 metadata
   filter 传递。Adapter 必须按固定版本 `/openapi.json` 序列化，禁止套用 Platform V3 的
   `filters.user_id` 请求形状。

查询矩阵：

```text
用户查看全部       -> user_id
用户全局记忆       -> user_id + scope_kind=USER
某 Worker 长期记忆 -> user_id + agent_id + scope_kind=AGENT
某任务记忆         -> user_id + agent_id + run_id + scope_kind=RUN
```

以当前自托管 OSS contract 为例，三层语义搜索分别序列化为：

```json
{"query":"...","user_id":"h-agent:user:alice","filters":{"scope_kind":"USER"}}
{"query":"...","user_id":"h-agent:user:alice","agent_id":"h-agent:agent:coder","filters":{"scope_kind":"AGENT"}}
{"query":"...","user_id":"h-agent:user:alice","agent_id":"h-agent:agent:coder","run_id":"h-agent:run:task1","filters":{"scope_kind":"RUN"}}
```

示例只表达领域语义；字段位置和 metadata filter 的最终 JSON 必须由固定 `/openapi.json` contract test 锁定。

`user_id` 宽查询用于管理/清理和审计，不直接作为模型 recall；模型 recall 由
`HarnessMemoryRuntime` 执行三层精确查询后合并、去重、重排和截断。

未来需要 workspace/project/team 等更多维度时，优先新增受服务端控制的 metadata + PostgreSQL 控制索引
字段，不复用或拼接 `agent_id/run_id`。这些扩展维度首先是用户内部筛选；若成为真正租户安全边界，仍需
认证 owner 校验，必要时再升级为独立 Mem0 实例/collection，不能只依赖向量 payload 条件。

建议 metadata：

```json
{
  "schema_version": 1,
  "app": "h-agent",
  "scope_kind": "USER | AGENT | RUN",
  "source": "auto_capture | explicit_tool | user_edit | legacy_migration",
  "display_category": "personal | preference | project | decision | commitment | other",
  "source_agent_id": "stable logical agent id",
  "source_agent_definition_version": 1,
  "source_task_session_id": "stable task/child session id",
  "source_agent_run_id": "per-turn local run id",
  "turn_key": "..."
}
```

所有 entity ID 必须由服务端认证身份、已解析 Agent 定义和已验证归属的任务/会话构造。memory tool
不得接受原始 mem0 entity ID；管理页面可以提交应用层 agent/task 筛选值，但后端必须先验证它们属于
当前用户，再映射为 mem0 ID。

`MEM0_API_KEY` 是后端访问固定 mem0 实例的服务凭证，不与每个 h-agent 用户一一映射，也不能承担
应用内租户授权。应用用户隔离由服务端构造的 `user_id` filter + PostgreSQL 控制索引共同保证；
`agent_id` / `run_id` 只负责用户内部的功能分区，不能替代 owner 校验。

### 4.3 父 Agent 与 Worker 的分层 recall/capture

当前 USER subagent 继续调用 `disableMemoryTools()` / `disableMemoryHooks()`，它们只关闭 AgentScope 的
Markdown memory，不代表禁用项目自有 Mem0 Module。项目通过统一的 `HarnessMemoryRuntime` 和 completion
流程为父 Agent 与 Worker 提供分层能力：

- 父 Agent recall：`USER` + `harness-agent` 的 `AGENT` + 当前根任务的 `RUN`；
- Worker recall：同一用户的 `USER`（只读共享）+ 自己的 `AGENT` + 当前任务的 `RUN`；
- 三层结果分别查询，合并后按 memory id/正文 hash 去重并统一重排，不能靠一次 user 宽查询替代；
- Worker 不能读取其他 Worker 的 `AGENT` / `RUN` 记忆，也不能提交任意 agent/run scope；
- 父/Worker 失败或取消的 turn 不 capture，reasoning、tool 参数/结果和内部事件仍不写入长期记忆。

第一期自动 capture 使用可测试的确定性 scope policy，一个 turn 只投递一个规范 scope，避免为了分层
再引入一套与 mem0 重复的事实抽取器：

- 父 Agent 成功 turn 自动进入 `USER`，延续当前 `MEMORY.md` 的跨会话用户记忆语义；
- Worker 成功 turn 自动进入自己的 `RUN`，避免把任务过程默认提升为长期用户偏好；
- `AGENT` 由父/Worker 显式 `memory_save(AGENT, ...)` 或管理页面创建，用于确认过的跨任务 Worker 上下文；
- 将来若要自动把 RUN 中的稳定经验提升到 AGENT，新增独立 promotion policy，而不是同时向 RUN/AGENT
  盲双写。

显式 `memory_save` 必须携带 `USER|AGENT|RUN` 领域枚举；调用方只能选择层级，entity ID 仍由服务端从
当前执行上下文补齐。Worker 默认只能自动写 RUN；提升为 AGENT/USER 必须来自显式 save/promotion，
不得把临时任务事实静默提升为用户全局偏好。

### 4.4 自动抽取与显式写入分开

| 场景 | mem0 调用 | 一致性 |
|---|---|---|
| 成功的父/Worker turn | scope policy 路由后发送输入与 final assistant，`infer=true` | 异步、最终一致 |
| 用户显式要求“记住” | `memory_save(scopeKind, entries)`，`infer=false` 直存整理后的事实 | 同步确认 |
| 用户编辑一条记忆 | update by memory id | 同步确认 |
| 用户删除一条记忆 | delete by memory id | 同步确认 |

自动 capture 不再沿用“每用户 30 分钟最多一次”的内存节流。节流可能在进程退出或用户不再继续
聊天时永久漏掉最后若干 turn。推荐每个成功父/Worker turn 生成一条本地 outbox 事件，由 worker 重试提交；
mem0 决定哪些事实值得保存。

自动抽取只允许保存用户事实、明确偏好、已确认决策和真实完成结果。发送给 mem0 的输入只包含
本轮输入/assignment 与 final assistant message；不得包含 system prompt、reasoning、tool arguments、tool results
或其他 Agent 内部事件。未确认建议、模型推测和临时执行状态不得成为长期记忆。允许 mem0 在**相同
entity scope** 内根据新事实更新
或淘汰冲突旧事实，并通过 history 接口保留可见变更历史。

管理页面使用应用维护的六类稳定中文展示分类：个人信息、偏好、项目上下文、决策、承诺/待办、其他。
mem0 原始 categories 可以保留在 metadata 中，但必须映射到应用分类，不能直接决定页面信息架构。

`turn_key` 在本地必须唯一，例如：

```text
{userId}:{logicalAgentId}:{taskSessionId}:{agentRunId}:long-term-memory-capture:v2
```

本地 outbox 对该 key 建唯一约束，只能防止 SSE 重连、任务重试或多实例竞争造成**重复入队**，不能把
远端投递自动提升为 exactly-once。worker 对 mem0 是 at-least-once：请求携带稳定 `turn_key` metadata；
若调用超时或进程在远端成功、本地完成标记前崩溃，事件进入 `RECONCILING`，必须先按目标 entity scope +
`turn_key` 查询远端结果并登记返回的 memory id，不能直接盲重试。固定的 mem0 版本必须在 contract test
中证明所有 ADD/UPDATE 结果都可用该 key 核验；若做不到，`provider=mem0` 不能上线，需在自托管 mem0
前增加支持 idempotency key 的项目侧代理或最小服务补丁。

父/Worker final 保存、Agent run 成功和 capture outbox 入队必须由同一个 `completeTurn(...)` PostgreSQL
事务提交。outbox 保存已持久化的 input/final message id，不复制正文；worker 投递时再读取两条权威
消息。这样不存在“聊天已成功但尚未入队就宕机”的丢失窗口。自动 capture 失败不改变聊天结果；显式
tool 写入失败则必须返回明确失败，不能谎报“已记住”。

### 4.5 Recall 是 transient context

每个父/Worker turn 开始前，以最新输入或 assignment 作为 query，对当前可见的 `USER`、`AGENT`、`RUN`
三层分别搜索一次；合并结果缓存到本次 `RuntimeContext`，并追加到
本轮 system prompt，而不是写进 `AgentState.context`：

```xml
<recalled_user_memory>
- [memory_id=...] ...
- [memory_id=...] ...
</recalled_user_memory>
```

规则：

1. 默认 `topK=8`，总字符数上限 6,000；threshold 从 0.25 起用离线样本校准。
2. 当前用户消息与明确的新事实优先于旧记忆。
3. 记忆内容按不可信数据处理，不执行其中的指令，防止持久化 prompt injection。
4. recall 超时、限流或 5xx 时 fail-open：记录指标后使用空结果继续聊天。
5. 不在 ReAct 每次 reasoning iteration 重复查询；同一 turn 每个 scope 最多查询一次。
6. 当前用户消息与旧记忆冲突时，以当前消息为准，并由本轮 capture 推动旧事实演化。

对“继续”“按上次方案”等弱 query，可把最近一条用户消息或 compaction summary 的短摘要加入 query，
但不能把整个历史发送给搜索端。

## 5. 模块与 seam

### 5.1 外部 Module 与 interface

Agent 运行时和用户管理页不是同一类调用方，不应共同学习一个镜像 mem0 CRUD 的浅 interface。建议创建
两个深 Module：

```java
public interface HarnessMemoryRuntime {
    Mono<MemoryRecall> recall(HarnessMemoryTurn turn);
}

public interface UserMemoryCatalog {
    Mono<UserMemoryView> query(UserMemoryQuery query);
    Mono<UserMemoryMutationResult> execute(UserMemoryCommand command);
}
```

`HarnessMemoryRuntime` 隐藏三层 scope 查询、合并去重、重排、阈值、截断、fail-open 和 prompt 安全；
`UserMemoryCatalog` 隐藏 ownership、分页快照、history、ADD/UPDATE/DELETE、expected version、409、锁、
远端补偿和结果登记。controller 只提交认证 user 下的领域 query/command，不学习 mem0 endpoint 或
entity filter 语法。

turn capture 不从 Agent middleware 暴露成第三个公共 memory interface。父/Worker 完成流程统一经过
`HarnessTurnCompletion` Module：在同一事务中保存 final assistant message、完成 Agent run、插入
带执行身份的 capture outbox。scope policy 和 worker 都是 memory Module 的内部实现。

### 5.2 内部 seam：mem0 gateway 与本地控制索引

mem0 是 true external dependency，内部定义 `Mem0Gateway` seam：

- `Mem0HttpGateway`：生产 HTTP Adapter；
- `FakeMem0Gateway`：module contract test Adapter。

`Mem0Gateway` 只能被 memory Module 的实现调用。它不负责应用用户授权；当前 OSS 按 memory id 的
get/update/delete/history 没有 user filter，必须先由 PostgreSQL 控制索引验证 ownership。

新增不保存正文的 `harness_memory_records`：

```text
memory_id, user_id, scope_kind, agent_id, run_id, version, category, source,
source_turn_key, remote_hash, remote_updated_at,
operation_state, created_at, updated_at, deleted_at
```

职责：

1. memory id -> authenticated user ownership；
2. USER/AGENT/RUN 分层、按 Worker/任务筛选和普通列表的稳定 cursor pagination；
3. expected version 的本地 CAS 与 mutation saga 状态；
4. 自动 capture、显式 mutation 和 migration 的远端幂等核验；
5. 不保存 memory content，mem0 继续是正文唯一真相源。

显式 add 在远端返回 memory id 前没有 record 可预留，因此另设不含正文的
`harness_memory_operations`，以稳定 operation key 保存 user、operation type、request hash、目标 memory id、
状态、远端结果 ids 和时间。它承担显式 ADD/UPDATE/DELETE 的幂等与 reconciliation；record 上的
`operation_state` 只表达该 memory 当前是否可读写。

语义搜索由 mem0 返回有序 ids/scores。为满足搜索结果分页，服务端把一次 search 的有序 id 快照写入
Redis，按 `userId + scopeKind + agentId + runId + queryHash + filters` 隔离，TTL 5 分钟；后续页面用
opaque cursor 读取同一快照。
不宣称 mem0 OSS 原生支持 page/offset。

更新流程在 per-memory 分布式锁内执行：本地 expected version CAS 预留 operation -> 调用 mem0 -> 登记
remote hash/updatedAt 并完成 operation。远端结果不明确时进入 reconciliation，禁止直接回滚版本或盲重试。
跨 owner 与不存在统一返回 404。

### 5.3 建议目录

```text
backend/src/main/java/com/h/backend/chat/
├── domain/memory/
│   ├── HarnessMemoryRuntime.java
│   ├── UserMemoryCatalog.java
│   ├── MemoryScope.java
│   ├── MemoryScopeKind.java
│   ├── MemoryExecutionIdentity.java
│   ├── MemoryRecord.java
│   └── ... request/result value objects
├── application/
│   ├── HarnessTurnCompletion.java
│   └── memory/
│       ├── DefaultHarnessMemoryRuntime.java
│       ├── DefaultUserMemoryCatalog.java
│       ├── HarnessMemoryScopePolicy.java
│       ├── HarnessMemoryOutbox.java
│       ├── HarnessMemoryCaptureWorker.java
│       └── port/Mem0Gateway.java
├── infrastructure/memory/persistence/
│   ├── HarnessMemoryRecordMapper.java
│   ├── HarnessMemoryOperationMapper.java
│   └── HarnessMemoryOutboxMapper.java
└── infrastructure/memory/mem0/
    ├── Mem0Properties.java
    ├── Mem0HttpClient.java
    ├── Mem0OssDto.java
    └── Mem0HttpGateway.java  # 生产 Adapter

backend/src/main/java/com/h/backend/chat/infrastructure/agentscope/
├── Mem0RecallMiddleware.java
├── Mem0MemoryTools.java
└── HarnessSessionSearchTool.java
```

`Mem0HttpClient` 负责传输与 DTO；`Mem0HttpGateway` 负责固定版本的远端 contract；两个公共 Module 负责
项目语义。middleware、controller 和 worker 调度器不应知道 URL、header 或 mem0 response JSON。

## 6. AgentScope Harness 2.0.1 改造点

### 6.1 项目配置改动

`HarnessAgentConfig.harnessAgent(...)` 必须按 provider 条件装配，不能在迁移开始时无条件关闭旧文件记忆：

```java
switch (memoryProvider) {
    case FILESYSTEM -> builder
        .memory(legacyMemoryConfig);
    case SHADOW -> builder
        .memory(legacyMemoryConfig)
        .middleware(mem0ShadowRecallCompareMiddleware);
    case MEM0 -> builder
        .disableMemoryTools()
        .disableMemoryHooks()
        .toolkit(toolkitWithMem0MemoryTools)
        .middleware(mem0RecallMiddleware);
}
```

该 provider 是部署实例级配置，因为 `HarnessAgent` 的 tools/hooks/middleware 在 Spring 单例构建时固定。
第一期不在同一进程内做 user-hash canary。若生产必须按用户 canary，应由两个不同 provider 的应用池
配合 sticky routing 完成；项目内不为过渡灰度引入多 HarnessAgent provider 重构。

`filesystem` / `shadow` 沿用旧 memory config 和压缩前 flush；只有 `mem0` 模式把 compaction 配置改为：

```java
.flushBeforeCompact(false)
.offloadBeforeCompact(true)
```

含义是：压缩前不再向 Markdown 长期记忆 flush，但仍保留原始 session JSONL offload 与摘要。
`harnessMemoryConfig` bean、`DEFAULT_FLUSH_PROMPT`、`DEFAULT_CONSOLIDATION_PROMPT` 和相关断言在迁移期
继续服务 `filesystem` / `shadow`；100% 切换并完成 30 天只读观察后，才作为退役任务删除。

自定义工具继续使用现有名字，降低 prompt 和模型行为迁移成本：

- `memory_search(query, limit, scopeKind?)`：在当前执行身份可见的 scope 内语义搜索，返回 memory id、
  scope、内容、时间和 score；
- `memory_get(memoryId)`：按 id 读取，不再接受文件 path/line；
- `memory_save(scopeKind, memories[])`：每个 item 带独立 content/category，Module 从执行上下文补齐
  entity ID 后逐条 `infer=false` 写入，拿到全部
  memory id 后才返回成功；不得靠把多条事实拼成一条记录来简化实现；
- `memory_update(memoryId, content)`；
- `memory_delete(memoryId)`。

tools 只调用 `UserMemoryCatalog`。Agent 可以主动调用 search/get/save。update/delete 只有在本轮用户明确要求修改或删除记忆时才允许调用；
tool description、system guidance 和测试都必须体现这一约束。用户显式新增、修改、删除成功后向聊天流
发送轻量结果卡片；后台自动 capture 静默执行。

显式 mutation 也必须幂等：以 `agentRunId + toolCallId + itemIndex` 形成稳定 operation key，先在控制索引预留
operation，再调用 mem0。工具重试读取既有 operation/result，不得再次新增、更新或删除；远端结果不明确
时返回“尚未确认”并进入 mutation saga reconciliation，不能为了尽快向模型返回而假成功。只有
`CONFIRMED` 才生成成功话术和结果卡片；reconciliation 后确认成功时再幂等补写持久化卡片。

调用 `disableMemoryTools()` 会连带移除 SDK 的 `session_search`。会话检索不是长期记忆，若产品仍需要，
应单独用 `HarnessSessionSearchTool` 恢复，不要因为迁移 mem0 丢掉它。

Worker 的 Mem0 能力不能只装在顶级 `HarnessAgentExecutor`。统一的 execution identity factory 必须覆盖：

- 父 Agent：stable agent=`harness-agent`，task session=`rootSessionId`；
- spawned builtin / Catalog Worker：stable agent=声明或定义的不可变 agentId，task session=child sessionId；
- 直达 Worker follow-up：复用原 child sessionId，不因本轮 execution id 改变 mem0 `run_id`；
- 后台 Worker 完成：经 `HarnessSubagentLifecycleMiddleware` 进入同一 `HarnessTurnCompletion`，保证 final、
  run success 与 outbox 原子提交。

USER subagent 上的 `disableMemoryTools()` / `disableMemoryHooks()` 继续关闭 SDK Markdown memory；项目自有的
Mem0 middleware/completion 必须显式装配或安全继承，并用三类 Worker 路径的集成测试证明，不得把这两个
开关误解为“Worker 不使用 Mem0”。

### 6.2 必须修复的 SDK 硬编码

AgentScope Harness 2.0.1 没有可注入的 long-term memory port。即使关闭 memory hooks，
`HarnessAgent.forceCompactAndRetry()` 在上下文溢出时仍会：

1. 创建默认 `CompactionConfig`（`flushBeforeCompact=true`）；
2. 创建硬编码 `MemoryFlushManager`；
3. 写回 `MEMORY.md` / daily ledger。

必须升级到支持自定义 memory backend 的版本，或向 SDK 提交一个最小补丁，使 emergency compaction
继承已配置的 `flushBeforeCompact` / `offloadBeforeCompact`。没有这个补丁时，生产会形成低频、难发现的
隐性双写，不能算完成迁移。

更理想的上游改动是给 Harness SDK 引入：

```java
LongTermMemoryBackend
    recall(...)
    capture(...)
```

并提供原文件 adapter；但本项目不应先 fork 一整套 memory pipeline。第一期最小 SDK patch + 项目内
mem0 adapter 的风险更低。

## 7. 配置

```yaml
chat:
  harness:
    memory:
      provider: ${HARNESS_MEMORY_PROVIDER:filesystem} # filesystem|shadow|mem0
      mem0:
        base-url: ${MEM0_BASE_URL:http://mem0:8000}
        api-key: ${MEM0_API_KEY:}
        contract-version: ${MEM0_CONTRACT_VERSION:} # 固定 image tag / git commit 对应版本
        openapi-sha256: ${MEM0_OPENAPI_SHA256:}
        connect-timeout: 500ms
        response-timeout: 1500ms
        recall:
          top-k: 8
          threshold: 0.25
          max-chars: 6000
        capture:
          outbox-enabled: true
          max-attempts: 10
```

API key 只从 secret/environment 注入，禁止写入仓库和业务日志。启动时：

- `provider=mem0` 且配置缺失：fail-fast；
- `provider=shadow` 且 mem0 不可用：告警但允许启动；
- 运行中 recall：fail-open；
- 显式 mutation：fail-closed 并返回业务安全错误。

`filesystem` 只用于迁移前；`shadow` 仍由旧文件服务模型，但把新 turn 写入 mem0 并比较 recall；`mem0`
启用后停止旧文件写入。正式 mem0 写入开始后，旧应用版本若不支持 mem0 就不再是可接受回滚版本。

`shadow` / `mem0` 必须固定 mem0 image tag 或 git commit，并把部署环境 `/openapi.json` 的 SHA-256 与
仓库 contract fixture 比对。禁止使用浮动 `latest`。contract test 必须覆盖：search 顶层 entity 字段、
同一 memory payload 保存 `user_id/agent_id/run_id`、三字段 AND 查询、user 宽查询、`scope_kind` metadata filter、按 id endpoint
不承担 owner 授权、get-all 无原生 page/offset、ADD/UPDATE 返回 id 与 turn_key 可核验、delete 后正文和
history 的清除语义。任一必需能力不满足时 fail-fast 或阻止切换。

## 8. Outbox

建议新增表：

```sql
CREATE TABLE harness_memory_outbox (
    id BIGSERIAL PRIMARY KEY,
    turn_key VARCHAR(512) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    logical_agent_id VARCHAR(255) NOT NULL,
    task_session_id VARCHAR(255) NOT NULL,
    agent_run_id VARCHAR(255) NOT NULL,
    actor_kind VARCHAR(16) NOT NULL,
    input_message_id BIGINT NOT NULL,
    final_message_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL,
    lease_until TIMESTAMP,
    remote_memory_ids_json TEXT,
    last_error_code VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE TABLE harness_memory_records (
    memory_id VARCHAR(255) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    scope_kind VARCHAR(16) NOT NULL,
    agent_id VARCHAR(255),
    run_id VARCHAR(255),
    version BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    source_turn_key VARCHAR(512),
    remote_hash VARCHAR(255),
    remote_updated_at TIMESTAMP,
    operation_state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP,
    CHECK (scope_kind IN ('USER', 'AGENT', 'RUN')),
    CHECK ((scope_kind = 'USER' AND agent_id IS NULL AND run_id IS NULL)
        OR (scope_kind = 'AGENT' AND agent_id IS NOT NULL AND run_id IS NULL)
        OR (scope_kind = 'RUN' AND agent_id IS NOT NULL AND run_id IS NOT NULL)),
    UNIQUE (user_id, memory_id)
);

CREATE TABLE harness_memory_operations (
    operation_key VARCHAR(512) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    scope_kind VARCHAR(16) NOT NULL,
    agent_id VARCHAR(255),
    run_id VARCHAR(255),
    operation_type VARCHAR(32) NOT NULL,
    target_memory_id VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    remote_memory_ids_json TEXT,
    last_error_code VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE TABLE harness_memory_migrations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    legacy_hash VARCHAR(64) NOT NULL,
    source_path VARCHAR(512) NOT NULL,
    source_heading VARCHAR(512),
    mem0_ids_json TEXT,
    status VARCHAR(32) NOT NULL,
    last_error_code VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    UNIQUE (user_id, legacy_hash)
);
```

控制表中的 `agent_id` / `run_id` 保存应用稳定逻辑 ID，不保存带 `h-agent:*:` 前缀的远端值；前缀映射只
存在于 `MemoryScope` implementation 内。`harness_memory_operations` 使用与 records 相同的 scope CHECK，
这样显式 ADD 在远端 memory id 返回前也能按准确 entity scope reconciliation。

outbox 只引用已经持久化的 user/assistant message id，不复制 thinking、tool result、system prompt、密钥或
正文。状态包含 `PENDING`、`PROCESSING`、`RECONCILING`、`COMPLETED`、`DEAD_LETTER`。明确收到并登记
mem0 affected memory ids 后才能 completed；超时、断连等未知结果先 reconciliation。完成记录只保留
turn key、request hash、remote ids、状态、attempts 和时间等非正文审计字段 30 天。达到最大重试次数后
进入 dead-letter 并报警，不阻塞聊天。不实现 Platform event polling。

`harness_memory_records` 是不含正文的控制索引。任何 mem0 add/import/capture 成功都必须登记返回的 id；
任何按 id 的 get/history/update/delete 都先用 `(user_id,memory_id)` 验证 owner。自动 inference 淘汰记录时
同步标记 deleted；周期 reconciliation 修复 mem0 与控制索引的状态漂移。

`harness_memory_operations` 记录显式 mutation 的稳定 operation key、request hash、状态和远端 ids；不保存
命令正文。相同 key + 相同 hash 返回既有结果，相同 key + 不同 hash 视为冲突。未知结果进入
`RECONCILING`，完成审计保留 30 天。

`harness_memory_migrations` 只保存 legacy block hash、来源定位、目标 memory id 和状态，不复制长期保存
原文；它用于 dry-run/import 可重跑、抽样核验和失败续跑。

## 9. `/me/memory` 产品契约

该页面尚未实施，因此直接按 mem0 的逐条 memory 模型首次开发，不提供 Markdown 兼容编辑器。MVP 包含：

- 逐条记忆列表、USER/AGENT/RUN scope、Worker、任务、六类筛选、语义搜索和分页；
- 手动新增、单条编辑、删除确认；
- 详情、来源、更新时间和变更历史；
- 编辑提交 expected version，冲突返回 409 并要求加载最新内容；
- 移动端沿用 `/me` 的窄版容器和现有视觉语言。

普通列表、scope/Worker/任务/分类与 version 由 `harness_memory_records` 控制索引提供；正文按页从 mem0
读取。语义搜索
使用 mem0 结果并创建短期 Redis 有序快照，通过 opaque cursor 分页。409 由本地 version CAS + 远端
mutation saga 提供，不假设 mem0 OSS 原生支持 page/offset 或 expected-version update。

不推荐把所有记忆重新拼成一个 Markdown 后允许整篇保存，因为：

1. 无法可靠判断一段文本对应 add、update 还是 delete；
2. 用户改标题、合并段落或重排会破坏 memory id；
3. 并发自动抽取与整篇保存容易互相覆盖；
4. 把整份 Markdown 作为单条 memory 会显著降低语义召回与细粒度删除能力。

页面展示产品化的“用户/Worker/任务”记忆层级，但不展示带前缀的内部 mem0 entity ID 或 API key。
memory id 只作为前后端寻址字段，不在聊天结果卡片中展示。
聊天卡片仅用于用户明确发起的新增、修改和删除，显示操作、分类、内容摘要和“查看详情”；后台自动
抽取不显示卡片。

## 10. 迁移

### 10.1 数据导入

按已知 user id 通过 `PostgresBaseStore` 读取：

```text
[agents, harness-agent, users, {userId}, root] / MEMORY.md
```

旧 Markdown 没有可靠 Worker/任务归属，全部按 `USER` scope 导入：只传 `user_id`，每个独立事实、偏好
或决策作为一条 `infer=false` 记忆，metadata 标记 `scope_kind=USER`、`source=legacy_migration`、heading
和 legacy hash。不要默认导入全部 daily ledger；
`MEMORY.md` 已是整理结果，daily ledger 会重新引入噪声和重复。若某用户没有 `MEMORY.md`，才考虑导入
最近 90 天 ledger，并先离线去重。

迁移工具必须：

- dry-run 输出用户数、block 数、超长/空 block 数；
- 用 `(userId, legacyHash)` 保证可重跑；
- 保存每条 legacy block 到 mem0 id 的映射和迁移状态，并登记 `harness_memory_records` owner/version；
- 抽样做原文、中文 embedding 和跨 session recall 验证；
- 导入完成后暂不删除 `workspace_files` 旧数据。

### 10.2 灰度阶段

1. **Baseline**：收集旧系统 recall/capture 指标和样本。
2. **Shadow**：旧文件仍服务；相同成功 turn 异步写 mem0，mem0 search 只记录对比，不注入模型。
3. **Migrate**：导入现有 `MEMORY.md`，检查数量、抽样和用户隔离。
4. **Contract Gate**：真实 mem0 固定版本通过 ownership、分页控制、CAS saga、远端幂等和删除历史测试。
5. **Switch**：部署实例全局切到 `mem0`，旧文件立即只读；至少覆盖一个业务高峰。若生产必须 canary，
   使用不同 provider 的独立应用池和 sticky routing，不在单例 HarnessAgent 内按 user hash 切换。
6. **Retire**：全量切换后旧文件只读保留 30 天；核验完成后删除或按既有备份策略归档。

`filesystem` 不是正式写入开始后的数据回滚目标。应用发布回滚必须回到仍支持 mem0 contract 的稳定
版本；mem0 故障通过 recall fail-open 和 capture outbox 处理，不把 mem0 自动反向导出成 Markdown。

进入 100% 前必须满足：用户隔离测试零串读；迁移可重跑且抽样一致；离线召回样本质量不弱于旧实现；
p95 recall 小于 1.5 秒；capture 成功率不低于 99.9%；outbox 最老积压小于 5 分钟。阈值可以根据真实
环境调整，但调整必须记录原因，不能取消量化门槛。

## 11. 可靠性、安全与可观测性

### 11.1 可靠性

- WebClient 设置 connect/response timeout；连接池有上限。
- recall 配置 circuit breaker，打开时直接空结果。
- capture 由数据库 outbox 重试，指数退避 + jitter。
- 远端结果不明确时进入 reconciliation；本地唯一 turn key 不被描述为远端 exactly-once。
- 返回内容按条数和总字符截断，避免 mem0 内容撑爆模型上下文。
- 显式 save/update/delete 先查不含正文的控制索引做 ownership check；不能仅凭 memory id 操作。
- 普通长期记忆不设置统一 TTL；事实演化依赖 update/淘汰和用户删除。

### 11.2 安全

- 每次 query/mutation 强制加入服务端 user owner；AGENT/RUN 查询还必须使用已验证的 agent/run scope。
- mem0 只暴露在私网，经 HTTPS/API key 访问；生产禁止 `AUTH_DISABLED=true`。
- 日志仅记录 user hash、memory id、状态、latency、result count，不记录正文。
- recall 内容标记为不可信事实，system prompt 明确禁止执行其中指令。
- 账号注销时提供按 user scope 删除全部 mem0 记录的流程，并验证正文、向量、history 和控制索引均清除。
- 删除一条记忆时从 mem0 硬删除；本地控制索引标记 deleted，操作审计不含正文。固定 mem0 contract 若
  不能物理清除 history 正文，则不得宣称满足隐私删除，必须提供受支持的 history purge 能力后再上线。

### 11.3 指标

```text
harness_memory_recall_total{provider,scope_kind,outcome}
harness_memory_recall_latency_seconds
harness_memory_recall_result_count
harness_memory_capture_total{source,scope_kind,outcome}
harness_memory_outbox_pending
harness_memory_outbox_oldest_age_seconds
harness_memory_tool_mutation_total{operation,outcome}
harness_memory_reconciliation_total{source,outcome}
harness_memory_operation_pending
harness_memory_index_drift_total{kind}
harness_memory_circuit_state
```

不把“搜索有结果”直接视为质量成功。灰度期还需离线标注：precision@k、是否引用错误/过期事实、当前
消息与旧记忆冲突时的服从率。

## 12. 测试

### 12.1 Module interface tests

- USER 记忆跨 Worker/任务可召回；AGENT 记忆只对同一 user+agent 可见；RUN 记忆只对同一
  user+agent+run 可见；不同 user 永不串数据。
- recall timeout/5xx 返回空记忆，主聊天继续。
- topK/threshold/总字符上限生效。
- explicit save 成功可立即 get；失败不返回成功话术。
- update/delete 先验证 owner。
- update expected version 冲突映射 409，不覆盖新版本。
- update/delete 与自动 capture 在应用侧按规范 entity scope 串行化；mem0 不允许被其他业务旁路写入。
- owner 从控制索引验证，跨 owner 按 id 请求统一 404。
- 普通列表 cursor pagination、分类筛选和语义搜索 Redis 快照分页稳定。
- 相同 turn key 只产生一个 outbox 事件；父/Worker final、run success、outbox 同事务提交。
- 远端成功后本地崩溃进入 reconciliation，不盲重试；完成审计 30 天后清理。

### 12.2 AgentScope integration tests

- 父/Worker recall 在每个可见 scope 最多查询一次，并以 transient system context 注入。
- recall 内容不进入 `AgentState.context`。
- 成功父/Worker turn 在 completion transaction 入队；失败、取消不入队。
- `disableMemoryTools`/`disableMemoryHooks` 后不再注册 SDK file memory tools/middleware。
- 普通 compaction 与 emergency compaction 都不写 `MEMORY.md`，session offload 仍工作。
- `session_search` 在长期记忆迁移后仍可用。
- 用户明确 mutation 发结果卡片；后台 capture 不发卡片。

### 12.3 Mem0Gateway contract tests

用固定版本的 OpenAPI fixture + fake HTTP server 覆盖 OSS 路径、`X-API-Key`、顶层 entity 字段、
USER/AGENT/RUN scope metadata filter、三字段同记录与 AND 查询、history、
错误映射和超时；显式断言按 id endpoint 没有 owner filter、get-all 没有 page/offset，避免测试出虚假的
远端能力。另设阻断切换的真实 mem0 contract test，验证 add -> search -> update -> history -> delete、
turn_key reconciliation 和 history purge。

## 13. 实施顺序

1. 固定 mem0 版本/OpenAPI，完成真实 contract spike；不满足幂等核验或 history purge 时先解决 contract。
2. 引入 `HarnessMemoryRuntime`、`UserMemoryCatalog`、内部 `Mem0Gateway` 和 fake Adapter。
3. 实现 `harness_memory_records` 控制索引、owner 校验、cursor/搜索快照分页和 mutation saga。
4. 实现 recall middleware、显式 memory tools 和 transient prompt 注入。
5. 把父/Worker final、run success、outbox 合并为 completion transaction，实现 scope policy、worker reconciliation 和指标。
6. 修复/升级 AgentScope emergency compaction 的硬编码；恢复独立 `session_search`。
7. 加部署级 `filesystem|shadow|mem0` feature flag 和测试。
8. 实现 dry-run/import migration tool，运行 shadow 对比。
9. 实现 `/api/me/memories`、`/me/memory` 页面和聊天结果卡片。
10. 全局切换、观察 30 天后退役 Markdown memory。

## 14. 验收标准

1. 对启用 mem0 的用户，父 Agent 与 Worker 能按 USER/AGENT/RUN 可见性语义召回已保存事实。
2. 成功父/Worker turn 自动 capture，显式“记住”可选择领域 scope 并同步确认；失败不假成功。
3. 不同用户、不同 Worker、不同任务并发执行无记忆串写或越权召回。
4. 常规和紧急 compaction 都不再写 `MEMORY.md` / daily ledger。
5. `agent_state_snapshots`、session offload、Skills、subagents 和普通聊天 memory 回归测试通过。
6. mem0 不可用时聊天可继续，outbox 可恢复追平。
7. 旧 `MEMORY.md` 导入可重跑、可核验；正式切换后只允许回滚到仍支持 mem0 的稳定应用版本。
8. 管理页面支持 USER/AGENT/RUN、Worker/任务、分类、语义搜索、分页、CRUD、历史和 409 并发冲突处理。
9. 控制索引不保存记忆正文；按 id 操作零串读，分页/CAS 不依赖 mem0 未提供的原生能力。
10. 父/Worker final 与 capture outbox 原子提交；未知远端结果可按目标 entity scope 核验后收敛，
    不宣称未经证明的 exactly-once。

## 15. 参考资料

- [Mem0 OSS REST API Server](https://docs.mem0.ai/open-source/features/rest-api)
- [Mem0 OSS `Memory` scope implementation](https://github.com/mem0ai/mem0/blob/main/mem0/memory/main.py)
- [Mem0 OSS memory types and multi-identifier scoping](https://github.com/mem0ai/mem0/blob/main/docs/core-concepts/memory-types.mdx)
- [Mem0 Add Memories API](https://docs.mem0.ai/api-reference/memory/add-memories)
- [Mem0 Search Memories API](https://docs.mem0.ai/api-reference/memory/search-memories)
- [Mem0 Platform Entity-Scoped Memory（仅用于识别与 OSS 的语义差异）](https://docs.mem0.ai/platform/features/entity-scoped-memory)
