# AgentScope Java 2.0.1：内置与用户自定义 Subagent 能力调研

- 日期：2026-08-13
- 范围：AgentScope Java 官方文档、官方 GitHub `v2.0.1` 标签源码与示例
- 项目基线：`backend/pom.xml` 当前使用 `agentscope-harness:2.0.1`
- 目的：回答 Harness Agent 能否提供平台内置 Subagent、调用是否排队/阻塞，以及能否支持每个用户创建专属 Agent

## 结论摘要

1. **可以内置通用 Subagent，也可以按用户动态加载专属 Subagent。** Harness 自带 `general-purpose`，还支持 `workspace/subagents/<id>.md`、Java `SubagentDeclaration`、自定义 `subagentFactory` 和远程 Agent Protocol 子 Agent。动态中间件会在每个 reasoning step 重新扫描声明，因此 workspace/远端存储里的变更可以在下一轮生效。
2. **“会不会阻塞”取决于调用模式，不存在一个全局统一队列。** `timeout_seconds > 0` 是同步 fan-out/fan-in：父 Agent 当前 reasoning step 等待子结果；`timeout_seconds = 0` 立即返回 `task_id`，子任务后台运行。一个 reasoning turn 中出现多个并发安全的 `agent_spawn` 时，Toolkit 默认并行执行；父 Agent只在这一批同步工具全部返回后进入下一轮。
3. **子 Agent 不与父 Agent 共用同一个会话。** 每次 spawn 默认创建新的 child session；`persistSession(true)` 才按 `(parentSessionId, agentId, label)` 复用。被暴露给用户的子 Agent 也由独立 `subagentId`/session 直接寻址。
4. **同一 `(userId, sessionId)` 的并发调用会 FIFO 串行，其他 session 可并行；但该门闩是 JVM 实例内的，不是分布式队列。** 多副本部署仍需应用层 session 锁、sticky routing 或分布式串行化，不能只依赖 AgentScope 的内存 gate。
5. **不需要给每个用户常驻一个 Java Agent 实例。** 官方推荐单个 stateless `HarnessAgent` 通过每次调用的 `RuntimeContext.userId + sessionId` 隔离状态。若用户定制的只是 subagent spec、skills、memory/workspace，可使用 USER namespace；若用户还要定制主 Agent 的模型、全局 prompt、Toolkit/MCP、middleware 等构造期配置，则需要平台自己的版本化定义和 agent factory/cache，而不是把它们当成 session state。
6. **“允许用户自由上传 spec”不能直接等同于安全的 Agent 平台。** AgentScope 有工具 allowlist、permission、sandbox、USER/SESSION 隔离和分布式存储能力，但租户身份校验、定义审核/发布、配额、模型白名单、远程 URL/headers 管控、SSRF/密钥治理、审计、版本回滚、任务调度仍属于产品层责任。
7. **AgentScope 2.0.1 的动态 per-user subagent 实现存在需要重点验证的并发风险。** 源码中 `DynamicSubagentsMiddleware` 每轮把当前调用加载到的声明写入共享 `DefaultAgentManager.replaceAgents(...)`，而 `AgentSpawnTool` 默认从该共享 manager 取工厂。并发用户可能互相覆盖快照。此项是基于官方源码的审计推断，不是官方文档承诺；生产实现应在升级/修复验证前采用 per-call immutable registry、per-user agent factory，或在应用层避免共享该动态 manager。

## 1. 动态创建与组合能力

### 1.1 平台内置通用 Subagent

Harness 默认提供无需声明的 `general-purpose`，复用父 Agent 的模型、工具和 skills，并共享父 workspace，适合把上下文密集的小任务从父循环里隔离出去。官方还支持构建时注册多个 Java 声明：

```java
HarnessAgent.builder()
    .subagent(SubagentDeclaration.builder()
        .name("reviewer")
        .description("Code review specialist")
        .model("qwen3-max")
        .steps(8)
        .tools(List.of("read_file", "grep_files"))
        .build())
    .build();
```

声明可选择三种互斥来源：独立 workspace、inline `AGENTS.md` body、远程 URL；也可以用 `subagentFactory(name, factory)` 接入完全自定义的 `Agent` 工厂。声明本身支持 model、temperature、top-p、最大步骤、tool/skill allowlist、workspace mode、是否持久 session、权限继承、用户 exposure 等字段。

来源：

- [官方 Subagent 文档：三种声明来源与 general-purpose](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/harness/subagent.md#three-ways-to-declare)
- [官方 `SubagentDeclaration` 源码：字段、默认值和构造约束](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-harness/src/main/java/io/agentscope/harness/agent/subagent/SubagentDeclaration.java)
- [官方 `HarnessAgent.Builder` 源码：`subagent` / `subagents` / `subagentFactory`](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java#L1750-L1771)

### 1.2 用户自己写 Markdown spec

用户可以在自己的 workspace namespace 写入：

```text
subagents/reviewer.md
```

文件名（去掉 `.md`）就是 `agent_id`；front matter 至少要求 `description`，正文作为系统提示词。2.0.1 parser 支持这些 Markdown 字段：

- `description`
- `workspace.mode/path`
- `model`
- `steps`（兼容旧名 `maxIters`）
- `temperature` / `top_p`
- `variant`（2.0.1 只保留字段，尚未下传到模型层）
- `mode: primary | subagent | all`
- `hidden`
- `expose_to_user`
- `tools` / `skills`

动态加载会在 reasoning 阶段读取 `subagents/*.md`；使用带 namespace 的 `AbstractFilesystem` 时，可以读取 USER 维度的远端切片，然后与本地基础模板、程序化内置声明合并，同名动态声明覆盖基础声明。

需要注意，**2.0.1 的 Markdown parser 没有解析 `persistSession`、`inheritParentPermissions`、远程 `url/headers` 等字段**。这些能力存在于 Java `SubagentDeclaration`，但若产品只开放 Markdown spec，必须接受这个较窄 schema，或由应用层验证后转换成 Java 声明。

来源：

- [官方 Subagent 文档：workspace spec 格式](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/harness/subagent.md#workspace-spec-files)
- [官方 `AgentSpecLoader.parse` 源码：2.0.1 实际解析字段](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-harness/src/main/java/io/agentscope/harness/agent/subagent/AgentSpecLoader.java#L217-L340)
- [官方 `DynamicSubagentsMiddleware` 源码：每轮加载和覆盖规则](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/DynamicSubagentsMiddleware.java#L147-L264)

### 1.3 运行中自动生成 Subagent

官方有 `agent_generate`：根据自然语言生成并校验 spec，再写入 `subagents/<name>.md`，下一 reasoning step 动态加载。它默认关闭，必须显式 opt-in；支持 `dry_run`，并限制 kebab-case 名称和重复名称。

这证明框架具备运行时创作声明的基础能力，但官方文档也明确建议生产环境先让 Agent 起草、再由人审核。对 SaaS 产品，更合理的产品语义应是“草稿 → 校验/审批 → 发布”，而不是让模型直接创建可执行能力。

来源：

- [官方 Subagent 文档：Let the agent author new subagent specs](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/harness/subagent.md#let-the-agent-author-new-subagent-specs)
- [官方 `AgentGenerateTool` 源码：校验、dry-run 和写入行为](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-harness/src/main/java/io/agentscope/harness/agent/tool/AgentGenerateTool.java)

### 1.4 Toolkit 动态组合

`Toolkit` 可注册注解工具、`AgentTool`、schema-only external tool、MCP client 和 `ToolGroup`；`reset_tools` 可让 Agent 在 session 中动态激活/停用非 basic group。工具调用返回 Reactor `Mono`，同一 turn 默认并行，也可将 Toolkit 配成 sequential。

边界是：Toolkit 属于 Agent 的构造配置。给共享单例在运行中全局注册/删除某个用户的工具会污染其他用户；用户差异应通过 tool group 的 session state、subagent allowlist，或每种用户 Agent 定义的独立 Toolkit snapshot/factory 实现。

来源：

- [官方 Tool 文档：自定义工具与 AgentTool contract](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/building-blocks/tool.md#agenttool--toolbase-contract)
- [官方 Tool 文档：ToolGroup 与 runtime activation](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/building-blocks/tool.md#self-managed-tools)
- [官方 `Toolkit` 源码：注册、分组、复制和执行 facade](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-core/src/main/java/io/agentscope/core/tool/Toolkit.java)

## 2. 并行、异步、排队与“是否同一线程”

### 2.1 调用模式矩阵

| 场景 | 框架行为 | 父 Agent 当前 turn | 会话关系 |
|---|---|---|---|
| `agent_spawn(timeout_seconds > 0)` | 同步调用，默认 30 秒、最大 600 秒；超时后可提升为后台任务 | 等待该工具结果；若同 turn 有多个并发安全 tool call，则并行后汇合 | 子 Agent 有独立 session |
| `agent_spawn(timeout_seconds = 0)` | 立即返回 `task_id`，后台执行 | 不等待子任务完成，可继续 reasoning/结束响应 | 子 Agent 有独立 session，任务状态归属父 session |
| `agent_send(..., timeout_seconds > 0)` | 对既有 child instance 发同步 follow-up | 等待返回 | 继续使用该 child session |
| `agent_send(..., timeout_seconds = 0)` | 后台 follow-up | 不等待 | 继续使用该 child session |
| 用户直接发消息给 exposed subagent | Gateway 用 `subagentId` 直接路由，绕过父 Agent | 父 Agent 不参与 | 独立 child session/turn gate |
| 同一 `(userId, sessionId)` 同时发两次主调用 | AgentBase FIFO 串行 | 第二次排在第一次之后 | 同一个 session |
| 不同 `(userId, sessionId)` | 并行 | 互不等待 | 不同 session |

同步子 Agent 会阻塞“父 Agent 的逻辑推进”，但不应理解成占住调用者的 Servlet/Java 原始线程。AgentScope 的 call/tool API 是 Reactor `Mono`/`Flux`，工具被 `subscribeOn(Schedulers.boundedElastic())` 调度；真正是否占用 Web 请求线程还取决于上层有没有调用 `.block()`。换言之：

- **业务语义上同步等待**：父 reasoning step 必须等结果；
- **运行时线程上不保证同一线程**：Reactor 会切 scheduler；
- **Web 会话是否持续占连接**：由 Harness 的 HTTP/SSE 适配层决定，不是 AgentScope subagent 自动决定。

来源：

- [官方 Subagent 文档：sync/background、并行 fan-out/fan-in 和结果收集](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/harness/subagent.md#sync-or-background)
- [官方 `AgentSpawnTool` 源码：`timeout_seconds` 语义和后台 task](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-harness/src/main/java/io/agentscope/harness/agent/tool/AgentSpawnTool.java#L219-L471)
- [官方 `ToolkitConfig` 源码：默认 parallel=true](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-core/src/main/java/io/agentscope/core/tool/ToolkitConfig.java)
- [官方 `ToolExecutor` 源码：并行批、顺序批与 boundedElastic 调度](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-core/src/main/java/io/agentscope/core/tool/ToolExecutor.java#L293-L415)

### 2.2 后台任务不是成熟的全局调度队列

背景任务记录会写到父 session 的 task repository，完成结果可自动 push-back 到父 Agent inbox，也可用 `task_output`、`wait_async_results`、`task_cancel`、`task_list` 管理。共享存储模式下其他节点能读取结果，但官方明确：**执行固定在创建任务的节点**；这不是具备 lease、故障接管、优先级、公平排队和容量调度的分布式 worker queue。

因此，如果 Harness Agent 要面向所有用户提供长任务，仍应单独设计：

- 用户/租户并发配额与全局 worker 容量；
- queued/running/cancelling/retry 状态机；
- lease/heartbeat、节点死亡接管与幂等；
- 浏览器断连策略和持久通知；
- 成本/令牌/最长运行时间预算。

来源：

- [官方 Subagent 文档：background task tools 和 auto push-back](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/harness/subagent.md#background-tasks-push-back-automatically)
- [官方 Subagent 文档：后台任务存储与 node pinning](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/harness/subagent.md#background-task-storage)

### 2.3 同 session 会排队，但只在当前 JVM 实例内

`ReActAgent.callSerializationKey` 使用 `(userId, sessionId)` 作为 key；`AgentBase.serializeOnKey` 用实例字段 `ConcurrentHashMap<Object, Mono<Void>> callGates` 链接 FIFO tail，所以同 key 串行、不同 key 并行。Gateway 还有一层实例内 `ConcurrentHashMap<String, Semaphore>` 公平锁，并在 `boundedElastic` 上 acquire。

由源码可得一个重要部署推论：这些 gate 都是**进程内对象**，没有通过 Redis/MySQL 共享。`DistributedStore` 能让另一个节点恢复相同 state，但本身不能阻止两个节点同时处理同一 session。因此多副本下仍需要以下至少一种策略：

- 相同 session sticky 到同一 live node；
- Harness 应用层使用数据库/Redis session mutex；
- 消息总线按 session key 分区、单消费者顺序执行；
- 乐观版本 + 冲突重试（必须同时约束有副作用的 tool call）。

这是从 2.0.1 官方源码做出的推断。官方生产文档也建议 exposed subagent 消息 sticky 回 live node，让跨节点恢复只作为 failover 路径。

来源：

- [官方 `ReActAgent.callSerializationKey` 源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L520-L533)
- [官方 `AgentBase.serializeOnKey` 源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-core/src/main/java/io/agentscope/core/agent/AgentBase.java#L325-L365)
- [官方 `SessionTurnGate` 源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-harness/src/main/java/io/agentscope/harness/agent/gateway/SessionTurnGate.java)
- [官方生产文档：exposed subagent sticky routing](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/others/going-to-production.md#what-stays-the-same)

## 3. Per-user 自定义 Agent 的框架约束

### 3.1 两种“每用户 Agent”不要混为一谈

**A. 一个共享 Agent runtime + 每用户状态/资产覆盖（优先推荐）**

适合用户差异是：conversation、memory、workspace 文件、skills、subagent Markdown spec。官方模型是共享单例 + 每次调用传 `RuntimeContext(userId, sessionId)`：

- mutable `AgentState` 以 `(userId, sessionId)` 寻址；
- USER scope 让同一用户跨 session 共享 workspace/memory/skills/subagents；
- SESSION scope 让每段会话完全隔离；
- subagent 继承父 `userId`，ISOLATED child session 还带父 session/user bucket，避免跨会话污染。

**B. 每用户一份可版本化 Agent Definition + runtime factory/cache**

适合用户可以改变：主 Agent system prompt、model/provider、Toolkit/MCP、middleware、远程 Agent endpoint、构造期 permission baseline。官方把这些描述为 Agent 实例的 immutable config，并不随 `RuntimeContext` 自动切换，所以需要 Harness 自己保存 `AgentDefinition`，审核后构建对应的 `HarnessAgent`/Toolkit snapshot，并做缓存淘汰与版本绑定。它不等于“每个用户永远常驻一个对象”；可按 `(definitionVersion, runtimeProfile)` 缓存，状态仍由 `(userId, sessionId)` 外置。

来源：

- [官方 Context 文档：stateless agent、单例多用户和状态寻址](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/building-blocks/context.md#stateless-agent-engine)
- [官方 Filesystem 文档：IsolationScope](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/harness/filesystem.md#isolationscope--bucketing-across-users-and-replicas)
- [官方 Filesystem 文档：per-user paths 与 static/runtime asset 边界](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/harness/filesystem.md#how-multi-user-isolation-works)
- [官方 child factory 源码：tool allowlist、state store、模型和 child session 组合](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgentBuilderSupport.java#L373-L503)

### 3.2 Subagent spec 的结构约束

最低限度需要平台校验：

- `agent_id`：来自 Markdown 文件名；若用 `agent_generate`，要求 `[a-z][a-z0-9-]{0,62}`；
- `description`：必填，是父 Agent 决定是否委托的主要信号；
- source mode：workspace、inline body、remote URL 三选一；
- `mode=PRIMARY` 的声明不能被 `agent_spawn`；
- `tools` 是**继承工具的 allowlist**，为空表示继承全部父工具；
- `skills` 也是 allowlist filter；
- ISOLATED 是默认 workspace mode，SHARED 会直接共享父 workspace；
- 默认每次 spawn 都是新 session；Markdown spec 在 2.0.1 无法直接打开 `persistSession`；
- 本地自动构建的 child 会 `.asLeafSubagent()`，避免递归继续 spawn；框架另有最大深度 3 的硬限制。

### 3.3 持久化与内存

| 数据 | AgentScope 能力 | 生产要求 |
|---|---|---|
| 对话、summary、permission/tool/plan/task state | `AgentStateStore`，键为 `(userId, sessionId)` | 多副本用 Redis/MySQL 等分布式实现；InMemory/JsonFile 只适合单机 |
| 长期记忆 | `memory/YYYY-MM-DD.md` + `MEMORY.md`，支持 compaction/offload | USER/SESSION scope；制定保留和 PII 策略 |
| 用户 workspace/subagent/skills | `AbstractFilesystem` + `IsolationScope` | Remote/BaseStore 或 sandbox snapshot；不要让租户绕过 namespace |
| exposed subagent handle | 默认进程内；`distributedStore` 可持久 registry 并重建 child | 仍建议 sticky routing，严格校验 subagentId 所有权 |
| background task record | workspace task repository，可共享读取/取消 | 执行固定创建节点；强可靠任务另建调度系统 |

官方说明 AgentState 一次 call 结束才整体保存，不是每条 message 即时保存；进程在 call 中途崩溃时，最后一轮内存修改可能尚未落盘。长期 memory flush/offload 也有 fire-and-forget 行为，不能把它当成业务审计日志或交易事实。

来源：

- [官方 Context 文档：AgentState 内容和保存时点](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/building-blocks/context.md#agentstate)
- [官方 Memory 文档：双层长期记忆、compaction 和异步 flush](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/harness/memory.md)
- [官方生产文档：DistributedStore 组件职责](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/others/going-to-production.md#at-a-glance-single-node-defaults-vs-distributed-production)

### 3.4 安全能力与产品层缺口

框架已有：

- Permission rule/mode（ALLOW、DENY、ASK，`DONT_ASK`、`EXPLORE` 等）；
- tool 自身 `checkPermissions`，可做不可绕过的 runtime input 检查；
- child 默认继承父 DENY rules；
- tool/skill allowlist；
- Local rooted path policy、Remote filesystem、Docker/Kubernetes/E2B 等 sandbox；
- USER/SESSION workspace 与 AgentState namespace；
- Plan Mode 子 Agent 只读继承；
- remote ASK 默认 DENY。

但平台仍必须补：

- 服务端从认证主体生成 `userId`，绝不信任客户端自报；
- `subagentId/sessionId` 的 owner/tenant 校验；
- spec schema、prompt 长度、model/provider 白名单、最大 steps/timeout/并发/预算；
- remote URL allowlist、DNS/IP/redirect 防 SSRF，禁止用户把任意 `Authorization` header 注入任意域名；
- tool/MCP catalog 只能引用平台批准的 capability，不允许用户任意 class/command；
- SHARED workspace、host shell、additionalRoots 默认禁用，用户代码进入 sandbox；
- 审批、版本、发布/撤回、审计、密钥引用（只存 secret ref，不把 secret 放 spec）；
- 输出/日志/trace 的敏感信息治理。

来源：

- [官方 Permission 文档：决策顺序和 non-bypassable checks](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/building-blocks/permission-system.md)
- [官方 Subagent 文档：userId 与 parent DENY 传播](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/harness/subagent.md#behavior-notes)
- [官方 Filesystem 文档：rooted path 与 shell 行为](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/harness/filesystem.md)
- [官方生产文档：sandbox 与 distributed execution guard](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/others/going-to-production.md#when-you-need-shell-pick-a-sandbox--mandatory-snapshot)

## 4. 2.0.1 动态 per-user registry 的并发风险

这是采用 USER-scoped `subagents/` 前必须解决的实现风险：

1. 官方目标是 `DynamicSubagentsMiddleware` 在每个 reasoning step 用当前 `RuntimeContext` 从 namespace filesystem 加载用户声明；
2. 但 2.0.1 实现随后调用共享字段 `agentManager.replaceAgents(merged)`；
3. `DefaultAgentManager` 用两个 `volatile Map` 保存整个工厂/声明快照；
4. `AgentSpawnTool.managerFor(runtimeContext)` 只有在 context 中存在 `CTX_AGENT_MANAGER` 时才使用 per-call manager，否则回退到共享 `agentManager`；
5. `DynamicSubagentsMiddleware` 没有像另一个 `SubagentsMiddleware` 那样把 immutable `SubagentSnapshot`/manager 放进当前 `RuntimeContext`。

因此存在如下 interleaving：Alice 的 reasoning 加载 Alice registry → Bob 的 reasoning 覆盖成 Bob registry → Alice 模型返回 `agent_spawn` → tool 从共享 manager 读取 Bob registry。结果可能是 Alice 的 agent 不存在，或错误调用同名的 Bob 定义。

这是一条**源码审计推断**；上线前应通过并发测试复现并采取一项措施：

- 升级到已明确修复并有回归测试的版本；或
- 将动态加载改为 per-call immutable `DefaultAgentManager`，写入 `RuntimeContext.CTX_AGENT_MANAGER`；或
- 为每个 user/definition 建独立 runtime 实例；或
- 暂时只允许平台级静态声明，per-user spec 由应用层解析后在请求级 factory 中构建。

来源：

- [官方 `DynamicSubagentsMiddleware`：共享 manager replace](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/DynamicSubagentsMiddleware.java#L147-L221)
- [官方 `DefaultAgentManager`：volatile registry snapshot](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-harness/src/main/java/io/agentscope/harness/agent/subagent/DefaultAgentManager.java#L46-L119)
- [官方 `AgentSpawnTool`：per-call manager key 与 fallback](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-harness/src/main/java/io/agentscope/harness/agent/tool/AgentSpawnTool.java#L131-L137)
- [官方 `SubagentsMiddleware`：安全的 per-call snapshot 安装方式](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/SubagentsMiddleware.java#L600-L630)

## 5. 对 Harness Agent 产品形态的建议

建议分三层，不把“通用 worker”“用户模板”“运行实例”混成一个概念：

### 第一层：平台内置 Worker Catalog

- 平台维护 `general-purpose`、researcher、reviewer、planner 等已审核声明；
- 版本随发布或走平台管理后台；
- 所有用户可见，但每次 spawn 都是用户/session 隔离的实例；
- 默认 tool allowlist、sandbox、预算和 permission policy 由平台锁定。

### 第二层：User Agent Definition

- 用户保存自己的 Markdown/结构化定义，状态建议为 `DRAFT/VALIDATED/PUBLISHED/REVOKED`；
- definition 有不可变 version，session 固定引用一个 version，避免运行中 silent drift；
- 允许配置 prompt、平台批准的 model profile、tools/skills allowlist、steps、workspace mode；
- remote endpoint、MCP、持久 session 等高风险字段走更高权限或暂不开放；
- AgentScope Markdown 只是导入/导出格式，数据库中的受控 Definition 才是产品真相。

### 第三层：Agent Runtime / Session / Task

- runtime 可共享或按 definition 缓存，不需要等于“每用户常驻实例”；
- session 以 `(tenantId, userId, agentDefinitionVersion, sessionId)` 做应用层授权和寻址；
- task 独立建模 sync/background、queue、attempt、lease、budget、cancel；
- AgentScope `task_id` 可作为执行适配层 id，但不能替代平台级可靠任务模型。

推荐落地顺序：

1. 先提供平台内置通用 Subagent catalog，延用现有会话内协作者能力；
2. 增加 background task/queue/限流和跨节点 session 串行化；
3. 再开放受限的用户 Markdown spec（仅 prompt + model profile + tools/skills allowlist + steps）；
4. 加版本/审批/审计后，再开放用户发布和复用；
5. 最后评估远程 Agent/MCP、自定义代码和跨用户分享市场。

## 6. 直接回答原问题

- **能给所有用户内置一些 subagent 吗？** 能。框架原生支持内置 `general-purpose`、程序化声明、自定义 factory；产品层做 catalog 和治理即可。
- **使用时需要排队吗？** 不一定。同步 spawn 会让父 Agent 的当前 step 等待；同一 turn 多个 spawn 默认可并行；后台 spawn 不等。相同 `(userId, sessionId)` 的并发请求在同一 Agent/JVM 内 FIFO 排队，不同 session 并行。全局容量排队需 Harness 自己实现。
- **会阻塞会话吗？在同一个线程中执行吗？** 同步模式会阻塞父 Agent 的逻辑推进，但 Reactor 通常在异步 scheduler 上执行，不能认为始终占用同一 Java 线程。child 使用独立 session；stream 可把 child event 转发进父 stream。是否占住 HTTP/SSE 连接取决于 Harness 的适配实现。
- **要给每个用户内置一个 subagent 实例吗？** 通常不要。共享 stateless agent + `RuntimeContext.userId/sessionId` + USER/SESSION namespace 更合适。只有构造期配置真正不同，才用 definition-based factory/cache。
- **用户能按 AgentScope Java 规范创建专属 Agent 吗？** 能，但建议先支持受限 Markdown spec，并由平台做校验、审批、版本和 capability allowlist；不要直接开放任意 Java factory、host shell、MCP/remote URL/header。
- **当前 2.0.1 可直接放心用于并发 per-user 动态 spec 吗？** 不能直接下结论。官方源码显示 shared dynamic registry 的覆盖风险，必须先修复/升级验证并做并发隔离测试。
