# Subagent Definition Catalog 详细设计

- 日期：2026-08-21
- 状态：已确认，待实施
- 实施目标：平台内置 Subagent + 用户 Markdown Subagent 的管理、发布、启停与 Harness 委托
- 依据：
  - [Agent Definition Markdown 设计](./2026-08-13-agent-definition-markdown-design.md)
  - [AgentScope Java 2.0.1 Subagent 能力调研](./2026-08-13-agentscope-java-subagent-capability-research.md)
  - [Harness 协作 Agent 后端设计](./2026-08-11-harness-subagent-backend-design.md)

本文是上述两份 2026-08-13 原稿的统一落地设计。原稿中的背景调研仍有效；当实现范围、状态模型或 AgentScope 适配建议与本文冲突时，以本文为准。

## 1. 结果与范围

本期交付一个双来源 Subagent Catalog：

1. 平台从代码库 Markdown 发布 `researcher`、`reviewer`、`planner`，并展示框架内置的 `general-purpose`。
2. 顶级 Harness Agent 能像调用 `general-purpose` 一样，通过 `agent_spawn(agent_id=...)` 委托这些内置 Subagent。
3. `/me/agents` 展示系统 Subagent，并允许用户使用原始 Markdown 创建自己的 Subagent。
4. 用户定义经历可变草稿、不可变发布版本和独立启用状态；只有已发布且已启用的定义参与委托。
5. 父 Agent 每个 turn 固定一个不可变 Catalog 快照；定义在 turn 中途发布或停用，不改变已经开始的执行。
6. 每个协作 Agent Session 固定具体 Definition Version；后续直接对话继续使用原版本。
7. 用户定义、运行状态、Workspace、工具和 Skill 都按认证用户及实际 Agent Session 隔离。

### 1.1 本期不做

- 不把 Subagent 作为独立顶级聊天入口。
- 不允许用户定义继续 `agent_spawn`，所有 Subagent 都是 leaf worker。
- 不开放远程 URL、HTTP headers、任意 MCP、自定义 Java factory、任意模型 provider、host shell 或共享工作区。
- 不开放 `persistSession`；产品已有独立子 Session 和后续对话入口，不复用 SDK 的 label 持久会话语义。
- 不做版本 diff、一键回滚、永久删除、跨用户分享或市场。
- 不把 `workspace/subagents/*.md` 作为生产定义来源。
- 不重构现有父子消息、SSE、运行记录和协作状态模型；只为其补充 Definition Version 绑定。

## 2. 代码基线与必须修正的事实

### 2.1 当前有两个互不相通的 Registry

产品层 `AgentRegistry` 位于：

```text
backend/src/main/java/com/h/backend/chat/domain/agent/AgentRegistry.java
```

它收集 Spring `AgentDefinition`，服务顶级 Agent 列表、会话选择和拓扑页面。`car-rental-assistant` 等顶级 Agent 进入该 Registry，并不意味着它们能被 Harness 的 `agent_spawn` 调用。

Harness 的 `agent_spawn` 使用 AgentScope `DefaultAgentManager`。当前 `HarnessAgentConfig` 没有调用 `.subagent(...)`、`.subagents(...)` 或 `.subagentFactory(...)`，因此真正可 spawn 的类型只有 SDK 自动加入的 `general-purpose`。

本设计不合并这两个 Registry。顶级 Agent 继续使用现有 `AgentRegistry`；新的 `SubagentDefinitionCatalog` 专门负责可复用 Subagent Definition。

### 2.2 当前实际启用了不安全的动态路径

`HarnessAgentConfig` 配置了 USER scope 的 `RemoteFilesystemSpec`，并且没有调用 `.disableDynamicSubagents()`。AgentScope 2.0.1 因此安装 `DynamicSubagentsMiddleware`：每次 reasoning 从当前用户的 `subagents/*.md` 加载声明，然后调用共享 `DefaultAgentManager.replaceAgents(...)`。

当前父 Agent 暴露 `write_file`，用户已经有能力让模型写入 `subagents/*.md`。所以共享 registry 风险不是只有前端 CRUD 上线后才可达；它在当前运行能力中已经可达。

### 2.3 shared registry 不是唯一并发问题

AgentScope 2.0.1 的 `AgentSpawnTool` 是 Toolkit 上的共享实例，并持有实例级：

```java
ConcurrentHashMap<String, SpawnedAgent> agentsByKey;
ConcurrentHashMap<String, String> labelToKey;
```

这些 Map 没有 `(userId, parentSessionId)` 分桶：

- 常见 label 会跨用户冲突；
- `agent_list` 会枚举共享实例中的条目；
- `agent_send` 会优先查询共享 key/label Map；
- 仅把 definition manager 改成 per-call，不能修复这些状态。

截至 2026-08-21，AgentScope Java 官方 `main`（2.0.3-SNAPSHOT）仍保留这些共享 Map，也仍保留 `DynamicSubagentsMiddleware.replaceAgents(...)`。本期不能把“以后升级”当成已经存在的修复。

### 2.4 declared child 没有正确继承当前 Remote Filesystem

当前父 Agent 通过 `RemoteFilesystemSpec` 使用 PostgreSQL-backed Workspace。2.0.1 的 declared-child factory 没有把该 resolved Remote filesystem 传播给默认 `ISOLATED` child；child 会退化为节点本地 filesystem。默认 USER namespace 通常仍能避免用户间直接交叉，但会丢失跨节点恢复和 PostgreSQL 持久语义。

本期的用户 Subagent 必须通过应用自己的 runtime materializer 显式构造 SESSION-isolated Remote filesystem。内置 Subagent 必须显式复用父 Agent 当前 USER-scoped Remote filesystem。

### 2.5 可直接复用的现有能力

以下链路已经实施，不重新设计：

- `agent_spawn` 产生独立 `sub-*` child session；
- Gateway exposure 产生 `SubagentExposedEvent`；
- `HarnessAgentExecutor` 投影父子事件和 SSE；
- `agent_sessions` 保存父子拓扑和实际 `agent_id`；
- `harness_subagents` 保存展示信息、委托和运行状态；
- 子消息、run、并发锁都按实际 child session 工作；
- 用户通过现有聊天入口继续子会话；
- `AgentScopeHarnessRuntime.streamSubagent` 负责重新物化 child 并恢复 `(userId, childSessionId)` 状态。

本期只替换“可用定义从哪里来、某个 child 应由哪个版本物化”这两个事实来源。

## 3. 领域语言与不变量

### 3.1 术语

| 术语 | 定义 |
| --- | --- |
| Definition | 稳定身份；由 `source + owner + agent_id` 确定，不直接包含运行状态 |
| Draft | 用户正在编辑的唯一可变 Markdown；允许校验失败 |
| Published Version | 一次发布产生的不可变 Markdown、hash 和编译结果 |
| Enabled | Definition 是否允许进入新父 turn 的可用 Catalog；与是否有草稿、是否已发布是独立事实 |
| Runtime Binding | `definition_id + version`；一次 child session 永久绑定该值 |
| Turn Snapshot | 父 turn 开始时生成的不可变 `agent_id → Runtime Binding` 映射 |
| Subagent Session | 一次实际协作身份；保存消息、状态、运行和父子关系，不等于 Definition |

### 3.2 状态不是单一枚举

用户定义同时具有三组状态：

```text
草稿：       无 / 有（revision=N，可合法或不合法）
发布版本：   无 / V1 / V2 / ...（不可变）
运行开关：   DISABLED / ENABLED
删除标记：   ACTIVE / DELETED
```

核心不变量：

1. `ENABLED` 必须存在当前发布版本。
2. 新建用户定义默认是“有草稿、无发布版本、DISABLED”。
3. 保存草稿不改变当前发布版本，也不改变启用状态。
4. 发布成功原子地产生新版本并切换当前版本；原来已启用则继续启用。
5. 发布失败不改变当前版本、启用状态或旧 Session。
6. 停用只影响后续父 turn；不取消当前 turn、运行中任务或已有 child session。
7. 已启用定义不能删除；删除只做软删除。
8. 被删除 Definition 的 `agent_id` 永久保留；可以恢复，不允许复用为新身份。
9. Definition Version 固定配置意图；最新平台安全政策始终拥有更高优先级。

### 3.3 身份与名称

- `agent_id`：kebab-case，长度 1–63；创建后不可修改。
- 用户的 `agent_id` 只需在所属账号内唯一。
- `general-purpose` 以及全部代码库内置 ID 是全局保留名称，用户不得创建同名定义。
- `display_name` 是 Markdown front matter 中的用户可见名称，不参与运行寻址。
- Session 继续保存逻辑 `agent_id`；版本身份由新增字段保存，不能编码进 `agent_id`。

## 4. 深模块与 Seam

### 4.1 `SubagentDefinitionCatalog`

`SubagentDefinitionCatalog` 是本期核心深模块。调用方只学习一套接口；内置来源、用户来源、草稿、发布事务、授权、quota、版本、编译和审计都留在实现内部。

建议接口形状：

```java
public interface SubagentDefinitionCatalog {
    SubagentCatalogView listForManagement(long userId);
    SubagentDefinitionDetail requireVisible(long userId, String agentId);

    DraftResult createDraft(long userId, CreateSubagentDraft command);
    DraftResult saveDraft(long userId, String agentId, SaveSubagentDraft command);
    ValidationResult validate(long userId, ValidateSubagentDraft command);
    PublishResult publish(long userId, String agentId, long expectedRevision);
    SubagentDefinitionDetail setEnabled(long userId, String agentId, boolean enabled);
    void softDelete(long userId, String agentId);
    SubagentDefinitionDetail restore(long userId, String agentId);

    SubagentTurnSnapshot snapshotForTurn(long userId);
    ResolvedSubagentDefinition resolvePinned(long userId, DefinitionBinding binding);
}
```

接口的不变量和错误模式是接口的一部分：

- 所有 USER Definition 操作都从认证 `userId` 推导 owner；调用方不能提交 owner。
- `requireVisible` 只返回系统内置或当前用户拥有的定义。
- `saveDraft` 使用 expected revision；过期 revision 返回冲突，不覆盖。
- `publish` 只发布数据库中该 revision 的草稿，不接受请求内另一份 Markdown，避免校验与提交之间的 TOCTOU。
- `snapshotForTurn` 只返回系统启用定义和当前用户已发布且已启用的定义。
- `resolvePinned` 可以解析已停用或软删除定义的历史版本，但仍执行 owner 和 Session 归属校验。

内部真实 Adapter：

- `ClasspathBuiltinDefinitionAdapter`：读取 `classpath*:agents/*.md`；代码库是内置内容发布真相。
- `PostgresUserDefinitionAdapter`：保存用户草稿、不可变版本、状态和审计。

内部实现还包含：

- `SubagentMarkdownCompiler`：严格解析、规范化和编译；
- `SubagentCapabilityPolicy`：计算模型、tools、skills、workspace 和预算的有效交集；
- `SubagentQuotaPolicy`：定义数、启用数、长度和操作频率；
- `BuiltinVersionSynchronizer`：把代码库版本登记为可被 Session 引用的不可变版本。

这些是 Catalog 的内部 seam，不泄漏给 controller 或 runtime。

### 4.2 `SubagentRuntimeFactory`

`SubagentRuntimeFactory` 隔离应用与 AgentScope child builder 的版本差异：

```java
public interface SubagentRuntimeFactory {
    List<SubagentEntry> entriesFor(SubagentTurnSnapshot snapshot);

    ReActAgent materialize(
            ResolvedSubagentDefinition definition,
            RuntimeContext parentContext
    );
}
```

它隐藏以下复杂度：

- Definition Version → AgentScope declaration/factory 的转换；
- 精确 toolkit 过滤，包括“空数组表示无能力”；
- 内置共享 Remote workspace 与用户 SESSION-isolated Remote workspace；
- 父模型继承、steps、Skill filter、permission DENY 传播；
- leaf worker、关闭 shell、关闭 subagent/task/Agent 生成、关闭 Memory/Plan/Skill 管理；
- state store、middleware、execution config 和事件转发配置；
- AgentScope 升级差异。

测试通过该接口验证可观察行为，不直接断言 builder 内部字段。

### 4.3 现有 `HarnessRuntime` seam 的调整

父调用必须同时持有本 turn 的 snapshot；子调用必须携带 pinned binding：

```java
Flux<AgentEvent> streamParent(
        Object agentBean,
        String message,
        RuntimeContext context,
        SubagentTurnSnapshot snapshot
);

Flux<AgentEvent> streamSubagent(
        Object agentBean,
        HarnessSubagentContext context, // 新增 DefinitionBinding
        String message
);
```

也可以把 snapshot 放入 `RuntimeContext` typed key，但 `HarnessAgentExecutor.Execution` 仍必须持有同一个 snapshot，用它把 exposure event 精确映射到版本。禁止在 exposure 时重新查询“当前发布版本”。

## 5. Markdown Contract

### 5.1 内置定义示例

```md
---
display_name: 代码审查员
description: 审查代码中的正确性、安全性和可维护性问题
mode: subagent
model: inherit
steps: 8
tools: [read_file, grep_files, glob_files, list_files]
skills: []
workspace:
  mode: shared
---

你是一名代码审查 Subagent。

只报告能够从代码中直接验证的问题，按影响排序，并给出最小修复建议。
```

### 5.2 用户定义示例

```md
---
display_name: 我的资料整理员
description: 阅读当前任务提供的资料并整理带出处的结论
mode: subagent
model: inherit
steps: 10
tools: [read_file, grep_files, glob_files, list_files]
skills: []
workspace:
  mode: isolated
---

你是一名资料整理 Subagent。

围绕父 Agent 的委托工作，不扩展任务范围；结论与证据分开陈述。
```

### 5.3 允许字段

| 字段 | 用户定义规则 | 内置定义规则 |
| --- | --- | --- |
| `display_name` | 必填，1–80 字符 | 必填 |
| `description` | 必填，1–500 字符 | 必填 |
| `mode` | 省略时编译为 `subagent`；填写时只能是 `subagent` | 本期只能是 `subagent` |
| `model` | 省略或 `inherit` | 省略或 `inherit` |
| `steps` | 1–20，默认 10 | 1–20 |
| `tools` | 省略、空数组或受控列表 | 受平台工具 Catalog 校验 |
| `skills` | 省略、空数组或当前用户可访问列表 | 受平台 Skill Catalog 校验 |
| `workspace.mode` | 省略时为 `isolated`；只能是 `isolated` | 本期必须是 `shared` |
| 正文 | 必填，作为 child system prompt | 必填 |

未知字段发布时失败。第一期明确拒绝 `temperature`、`top_p`、`variant`、`url`、`headers`、`mcp`、`persistSession`、`inheritParentPermissions`、`expose_to_user`、`workspace.path` 和任何 provider 配置。

### 5.4 tools / skills 的平台语义

不能直接沿用 AgentScope 2.0.1 的“空列表表示继承全部”语义：

- 字段省略：使用平台安全默认集合；
- `tools: []` / `skills: []`：明确为空；
- 显式填写：取 `声明集合 ∩ 平台允许集合 ∩ 当前用户可访问集合 ∩ 父 DENY`。

第一期具体默认值：省略 `tools` 等价于四个只读文件工具；省略 `skills` 等价于空集合。Skill 只有显式填写且同时通过平台安全 Catalog 与用户可见性校验时才可用。

用户工具 Catalog：

- 默认只读：`read_file`、`grep_files`、`glob_files`、`list_files`；
- 可显式申请：`write_file`、`edit_file`；
- 始终排除：shell、`agent_spawn`、`agent_send`、`agent_list`、task 管理、Agent 生成、Memory、Plan、Skill 管理和 MCP。

`SubagentRuntimeFactory` 必须构造精确 toolkit。当有效集合为空时，不能把空 List 直接传给 SDK allowlist 并意外继承全部。

### 5.5 校验阶段

```text
UTF-8 / 大小校验
  → front matter 与正文分离
  → YAML 语法与未知字段校验
  → 字段类型、长度、枚举、ID 校验
  → 来源规则（BUILTIN / USER）
  → capability、ownership、quota 校验
  → 规范化 CompiledDefinition
  → 生成 SHA-256 content hash
```

草稿保存执行语法检查并返回 issues，但 issues 不阻止保存。发布要求 issues 中没有 ERROR。

```java
record ValidationIssue(
        String code,
        Severity severity,
        String field,
        Integer line,
        Integer column,
        String message
) {}
```

发布错误必须能定位字段；解析器能确定位置时同时给出行列。日志不得打印完整用户 Markdown。

## 6. 持久化设计

新增 Flyway migration，建议：

```text
backend/src/main/resources/db/migration/
V20260821_01__create_subagent_definition_catalog.sql
```

### 6.1 `agent_definitions`

| 字段 | 语义 |
| --- | --- |
| `id BIGSERIAL` | 内部稳定身份 |
| `source VARCHAR(16)` | `BUILTIN` / `USER` |
| `owner_user_id BIGINT NULL` | USER 必填，BUILTIN 为空 |
| `agent_id VARCHAR(63)` | 稳定逻辑 ID |
| `current_published_version INTEGER NULL` | USER 当前发布版本；无版本时为空 |
| `enabled BOOLEAN` | 是否进入新 turn Catalog |
| `deleted_at TIMESTAMP NULL` | USER 软删除；BUILTIN 不删除 |
| `created_at/updated_at` | 审计时间 |

约束：

- CHECK `source/owner_user_id` shape；
- USER 唯一 `(owner_user_id, agent_id)`，唯一约束包含已删除行，保证 ID 不复用；
- BUILTIN 全局唯一 `agent_id`；
- 用户与保留内置 ID 的冲突由 Catalog 在同一事务中拒绝；
- `enabled=true` 时应用层保证 `current_published_version` 非空；数据库增加对应 CHECK。

### 6.2 `agent_definition_drafts`

| 字段 | 语义 |
| --- | --- |
| `definition_id BIGINT PK` | 每个 USER Definition 最多一个草稿 |
| `markdown_content TEXT` | 当前草稿原文 |
| `revision BIGINT` | 每次成功保存递增 |
| `validation_json JSONB` | 最近一次保存时的结构化校验结果 |
| `updated_by_user_id BIGINT` | 最后编辑者 |
| `created_at/updated_at` | 时间 |

内置 Definition 不创建 draft 行。

### 6.3 `agent_definition_versions`

| 字段 | 语义 |
| --- | --- |
| `definition_id BIGINT` | 所属 Definition |
| `version INTEGER` | 从 1 单调递增 |
| `content_hash CHAR(64)` | 规范化原文 SHA-256 |
| `markdown_content TEXT` | 发布原文，不可变 |
| `compiled_metadata_json JSONB` | 经平台校验后的执行配置 |
| `published_by_user_id BIGINT NULL` | USER 发布人；BUILTIN 为空 |
| `builtin_release_id VARCHAR(128) NULL` | 内置版本对应构建/提交身份 |
| `created_at` | 发布时间 |

主键或唯一键为 `(definition_id, version)`。`agent_definitions` 的 current pointer 使用复合外键指回同一 Definition 的 version，避免指向其他定义的版本。

内置同步还应有部分唯一键 `(definition_id, builtin_release_id) WHERE builtin_release_id IS NOT NULL`。同步事务锁定 Definition：同一 release 的多个节点复用同一 version；发现同一 release ID 对应不同 hash 时启动失败；新 release（包括正式回滚）创建更大的 version。旧 release 节点重启只能复用旧行，不能把 current pointer 从新版本改回旧版本。

相同用户草稿与当前版本 content hash 一致时，`publish` 幂等返回当前版本，不创建空版本。

### 6.4 `agent_definition_audit_logs`

记录 `CREATE_DRAFT`、`SAVE_DRAFT`、`PUBLISH`、`ENABLE`、`DISABLE`、`SOFT_DELETE`、`RESTORE` 和内置同步失败。保存 actor、definition、version、revision、request id、时间和不含正文的 metadata。

### 6.5 `agent_sessions` 版本绑定

新增：

```text
agent_definition_id BIGINT NULL
agent_definition_version INTEGER NULL
```

并使用复合外键关联 `agent_definition_versions(definition_id, version)`。

规则：

- 新创建的 Harness child session 两列必须同时非空；
- 顶级和历史非 Catalog Session 可以为空；
- `agent_id` 继续保存逻辑类型，用于展示和兼容，但不是重新物化版本的真相；
- exposure 写 `agent_sessions` 时必须在同一事务写入 binding；
- `HarnessExecutionSession` 和 `HarnessSubagentContext` 增加 binding；
- child follow-up 禁止用当前发布版本替代缺失的 pinned version。

迁移时把可确认的历史 `general-purpose` child 绑定到启动同步产生的 synthetic builtin version；无法可靠判断的历史行保持 legacy fallback，并记录告警。feature 开启后新行不允许 fallback。

### 6.6 `general-purpose` 的特殊来源

`general-purpose` 的 runtime factory 仍由 AgentScope 自动提供，不能再注册同名 declaration。Catalog 将它登记为 synthetic BUILTIN Definition：

- 页面展示 framework-managed 的只读 Markdown 预览；
- 编译结果标记 `runtimeKind=SDK_GENERAL_PURPOSE`；
- 父 turn manager 复用 SDK 原 factory；
- Session 仍绑定 synthetic Definition Version；
- 其他三个内置定义使用 `runtimeKind=CATALOG_DECLARATION`。

## 7. 运行时设计

### 7.1 父 turn

```text
ChatService / HarnessAgentExecutor
  → catalog.snapshotForTurn(authenticatedUserId)
  → 得到 immutable SubagentTurnSnapshot
  → snapshot 放入 RuntimeContext，并由 Execution 持有
  → HarnessAgent.streamEvents
  → CatalogSubagentsMiddleware 安装 per-call DefaultAgentManager
  → 父模型只看到 agent_id + description
  → agent_spawn 使用 CTX_AGENT_MANAGER 物化 exact version child
  → SubagentExposedEvent
  → Execution 用同一 snapshot 将 agent_id 映射为 DefinitionBinding
  → agent_sessions 原子保存 child + binding
```

`SubagentTurnSnapshot` 至少包含：

```java
record SubagentTurnSnapshot(
        String snapshotId,
        long userId,
        Instant createdAt,
        Map<String, ResolvedSubagentDefinition> byAgentId
) {}
```

Map 必须不可变。用户 A 与 B 即使都定义 `my-reviewer`，也得到不同 snapshot 和 factory closure。

### 7.2 Catalog middleware 与 SDK middleware 的分工

构建父 Agent 时：

1. 启动期严格加载内置 Markdown并调用 `.subagents(builtinDeclarations)`；
2. 调用 `.disableDynamicSubagents()`，切换到会安装 `CTX_AGENT_MANAGER` 的 `SubagentsMiddleware`；
3. 安装 `CatalogSubagentsMiddleware`，其 order 必须保证 SDK `onAgent` 完成后再覆盖为本 turn 的 combined manager；
4. combined manager = SDK `general-purpose` + 内置静态 factory + 当前 snapshot 的用户 factory；
5. SDK middleware 继续负责 task repository、`agent_spawn`、Gateway bridge、exposure 和 background result；
6. Catalog middleware 收到 SDK 已改写的 `ReasoningInput` 后，移除 SDK 生成的 Subagents 说明段，写入一份平台拥有的完整说明段；该段只列 combined snapshot 的 `agent_id + description`，只指导 `agent_spawn` 和仍开放的 task 工具，不出现已被 deny 的 `agent_send/agent_list`，也不包含任何 Definition 正文。

替换 SDK prompt 段是一个版本敏感 Adapter，必须有 golden test 固定 2.0.1 输入和平台输出。实现还必须用真实 middleware-chain 集成测试验证顺序，不能只用 mock 断言某个 context key 被 put。

### 7.3 禁止 Workspace 动态声明旁路

父 Workspace 的 `subagents/` 是保留路径：

- filesystem `glob/read` 对 SDK scanner 返回无定义；
- `write_file/edit_file` 写该目录时返回明确的保留路径错误；
- 已存在的 USER `subagents/*.md` 不删除，但不参与 Catalog；
- 唯一用户定义入口是 `/api/me/subagents` 的草稿/发布流程。

建议在现有 Remote filesystem 外增加 `ReservedWorkspacePathAdapter`，而不是只靠 system prompt 禁止写入。

### 7.4 child materialization

`SubagentRuntimeFactory` 根据 pinned version 构造 child：

共同规则：

- 继承父 Harness model；第一期只支持 `model: inherit`；
- 最大 reasoning steps 取发布版本；
- 使用发布版本正文作为 child system prompt，并追加 SDK leaf context；
- 显式关闭 child subagents、shell、Memory、Plan、Skill 管理、Agent 生成和 MCP；
- Toolkit 使用平台计算后的精确 snapshot；
- 传播父 DENY 和当前平台强制 DENY；
- 使用分布式 `AgentStateStore`；
- 继承现有 `ParentAssignmentSystemPromptMiddleware` 与 lifecycle middleware；
- 不在共享 Toolkit、Prompt 或 Middleware 中写入用户配置。

Workspace：

- BUILTIN：复用父 Agent resolved USER-scoped Remote filesystem，语义与 `general-purpose` 一致；
- USER：使用同一 PostgreSQL BaseStore 创建 SESSION-isolated Remote filesystem；运行时 child `sessionId` 自动形成隔离 bucket；
- 禁止退化为默认 Local filesystem；构造后测试必须能证明 backend 类型与 isolation scope。

### 7.5 exposure 与版本固定

AgentScope event 只有逻辑 `agentId`，没有 Definition Version。必须从当前 `Execution` 持有的 turn snapshot 解析：

```text
event.agentId
  → execution.snapshot.byAgentId[event.agentId]
  → definitionId + version
  → exposeSubagent(..., binding)
```

找不到 binding 时不创建一个“当前版本”替代行；本次 exposure 失败、记录安全告警并使 run 明确失败。这样发布发生在 model call 与 tool call 之间时也不会串版本。

### 7.6 child follow-up

```text
HTTP 请求只提交 child sessionId
  → 现有 owner/root 校验
  → agent_sessions 读取 pinned binding
  → catalog.resolvePinned(userId, binding)
  → 当前安全政策重新求交集
  → runtimeFactory.materialize(exactVersion, parentContext)
  → 使用原 userId + child sessionId streamEvents
```

停用、软删除或发布新版本都不改变此流程。若版本声明的某项能力后来被平台撤销，child 以缩减后的能力继续；若核心能力已不可安全运行，则本轮返回明确的 policy failure，不偷偷切换版本。

### 7.7 启停和发布的生效点

- 父 turn 开始后，snapshot 固定到 turn 结束；
- turn 中途启用的定义从下一 turn 可见；
- turn 中途停用的定义不能阻止 snapshot 内已经获准的 spawn；
- 已经 spawn 的同步或后台 child 固定 factory closure 和 version；
- 发布 V2 后，新 turn 使用 V2，旧 child 使用 V1。

## 8. AgentScope 2.0.1 兼容策略

### 8.1 Definition registry

禁止继续使用 `DynamicSubagentsMiddleware` 的共享 `replaceAgents` 路径。`.disableDynamicSubagents()` 是本期构建要求，不是可选优化。

每个 parent invocation 的 combined manager 必须写入：

```java
AgentSpawnTool.CTX_AGENT_MANAGER
```

禁止按用户缓存可变 `DefaultAgentManager`；可以缓存不可变的 compiled version 或 factory template，但 turn manager 必须独立。

### 8.2 `AgentSpawnTool` 共享状态收敛

第一期不依赖 SDK 的 label/key 会话管理：

1. 父 Toolkit deny `agent_send` 和 `agent_list`；产品已有 child Session 继续对话和协作树列表。
2. `agent_spawn` 的 `label` 必须为空；`SubagentSpawnGuardMiddleware` 对非空 label 返回可见工具错误，system prompt 同时要求省略 label。
3. 所有 Catalog declaration 强制 `persistSession=false`。
4. `agent_key` 仅作为 SDK 本次内部执行信息，不进入 HTTP、数据库身份或授权逻辑。
5. 后台任务继续通过 `task_id`、task repository 和现有 wait/cancel 工具管理。

这样共享 `labelToKey` 不产生条目，`agentsByKey` 的随机 key 不再有跨用户查询入口。实现仍需监控其生命周期和内存占用。

未来只有在依赖版本通过以下测试后才能重新开放 `agent_send` / `agent_list`：

- key、label、list 都按 `(userId, parentSessionId)` 分桶；
- 跨用户 key 即使泄露也不能调用；
- 同 label 可在不同父 Session 重复使用；
- restore 不把 A 的 entry 写入 B 的 fast-path Map。

### 8.3 升级不是默认修复

实施 Agent 可以先验证更高 AgentScope 版本，但必须以源码和回归测试为依据。当前官方主线仍保留共享 `agentsByKey/labelToKey` 和 dynamic `replaceAgents`；仅修改 Maven 版本号不能满足验收。

## 9. 管理接口

新增认证接口前缀：

```text
GET    /api/me/subagents
GET    /api/me/subagents/{agentId}
POST   /api/me/subagents
PUT    /api/me/subagents/{agentId}/draft
POST   /api/me/subagents/validate
POST   /api/me/subagents/{agentId}/publish
PUT    /api/me/subagents/{agentId}/enabled
DELETE /api/me/subagents/{agentId}
POST   /api/me/subagents/{agentId}/restore
GET    /api/me/subagents/{agentId}/versions
GET    /api/me/subagents/{agentId}/versions/{version}
```

### 9.1 Catalog 响应

`GET /api/me/subagents` 一次返回页面所需事实：

```json
{
  "system": [
    {
      "agentId": "reviewer",
      "displayName": "代码审查员",
      "description": "审查代码中的正确性、安全性和可维护性问题",
      "source": "BUILTIN",
      "enabled": true,
      "currentVersion": 1,
      "editable": false
    }
  ],
  "mine": [
    {
      "agentId": "my-reviewer",
      "displayName": "我的审查员",
      "description": "按团队规则审查代码",
      "source": "USER",
      "draftRevision": 4,
      "draftValid": true,
      "currentVersion": 2,
      "enabled": true,
      "deleted": false,
      "updatedAt": "2026-08-21T12:00:00"
    }
  ],
  "limits": {
    "maxDefinitions": 100,
    "maxEnabled": 20,
    "usedDefinitions": 7,
    "usedEnabled": 3
  },
  "capabilities": {
    "models": ["inherit"],
    "defaultTools": ["read_file", "grep_files", "glob_files", "list_files"],
    "requestableTools": ["write_file", "edit_file"]
  }
}
```

### 9.2 草稿与发布

创建：

```json
{
  "agentId": "my-reviewer",
  "markdown": "---\n..."
}
```

保存：

```json
{
  "expectedRevision": 4,
  "markdown": "---\n..."
}
```

发布只提交：

```json
{ "expectedRevision": 5 }
```

保存结果始终带新 revision 与 validation issues。发布结果带 version、hash、enabled 和 compiled capability summary。

### 9.3 错误语义

至少区分：

- `INVALID_AGENT_ID`
- `RESERVED_AGENT_ID`
- `DEFINITION_NOT_FOUND`
- `DRAFT_REVISION_CONFLICT`
- `PUBLISH_VALIDATION_FAILED`
- `NO_PUBLISHED_VERSION`
- `DEFINITION_LIMIT_EXCEEDED`
- `ENABLED_LIMIT_EXCEEDED`
- `DELETE_REQUIRES_DISABLED`
- `DEFINITION_DELETED`

现有 `GlobalExceptionHandler` 只把 BusinessException 映射为 400/401/404。实施时增加显式 conflict/unprocessable/rate-limit 异常或状态映射：revision conflict 返回 409，发布校验返回 422，限流返回 429；响应体继续使用项目 `ApiResponse`。

## 10. 前端设计

### 10.1 路由

保留现有：

```text
/me/agents                  顶级 Agent 管理入口
/me/agents/[agentId]        顶级 Agent 编排拓扑
```

新增：

```text
/me/agents/subagents/new
/me/agents/subagents/[agentId]
```

Next 静态 `subagents` segment 优先于现有动态 `[agentId]`。实施前按 `frontend/AGENTS.md` 阅读当前 Next 16 本地文档，不按旧版 Next 约定猜测路由行为。

### 10.2 `/me/agents` 三个 Tab

1. **顶级 Agent**：保留当前列表、拓扑和“开始问答”。
2. **系统 Subagents**：展示 `general-purpose`、`researcher`、`reviewer`、`planner`；支持搜索和只读详情/Markdown 预览。
3. **我的 Subagents**：展示草稿状态、发布版本、启用状态、校验状态和更新时间；支持新建与进入编辑页。

系统卡片不显示编辑、删除、个人停用或“开始独立聊天”。

### 10.3 用户编辑页

页面字段和行为：

- 创建时单独填写 Agent ID；创建成功后只读；
- 原始 Markdown 编辑器，至少支持等宽字体、行号或等价定位、未保存提示；
- 内置模板；
- validation panel 按 ERROR/WARNING 展示字段、行列和消息；
- 操作：保存草稿、校验、发布、启用/停用、删除、恢复；
- 发布按钮只发布已保存的当前 revision；存在未保存修改时先提示保存；
- 启用按钮只在存在发布版本且未删除时可用；
- 删除按钮在启用时不可用，并说明需先停用；
- 版本列表展示 version、hash 前缀、发布时间和当前标记；点击可只读查看 Markdown；
- 409 revision conflict 保留本地文本，显示“服务器草稿已变化”，不自动覆盖。

第一期不引入重量级编辑器依赖作为必需条件；交互契约优先于具体编辑器库。

## 11. 并发、事务和多节点

### 11.1 Draft revision

保存使用条件更新：

```sql
UPDATE agent_definition_drafts
SET markdown_content = ?, revision = revision + 1, ...
WHERE definition_id = ? AND revision = ?
RETURNING revision;
```

未返回行即 revision conflict。

### 11.2 Publish

单事务内：

1. 锁定 Definition 和 Draft；
2. 校验 owner、deleted、expected revision；
3. 重新编译草稿，不信任保存时 validation cache；
4. 若 hash 等于当前版本，幂等返回；
5. 分配下一 version，插入 immutable row；
6. 更新 current pointer；
7. 保持原 enabled；
8. 写 audit。

### 11.3 Enable / disable / quota

Enable 事务锁定用户定义集合或使用 PostgreSQL advisory lock，重新统计当前 enabled 数，再更新状态。不能先 count 后无锁 update，否则两个并发请求可共同越过 20 个限制。

Disable 不删除版本。Soft delete 在同一事务验证 disabled 并写 audit。Restore 不自动启用。

### 11.4 Parent turn snapshot

用户发布数据从 PostgreSQL 读取；第一期不做跨节点可变 manager cache。每个 turn 查询一次最多 20 个 enabled 定义，成本可控，且发布/启停对下一 turn 立即一致。

可缓存的只有：

- 按 `(definitionId, version, policyRevision)` 索引的不可变编译结果；
- classpath builtin 解析结果；
- runtime factory template。

缓存值不得包含 user/session mutable state。

### 11.5 安全策略 revision

平台 capability policy 维护单调 `policyRevision`。Turn snapshot 和 child materialization 记录该 revision：

- 定义版本固定；
- 每次运行按当前 policy 重新求交集；
- cache key 包含 policyRevision；
- policy 收紧后旧 cache 自动失效。

## 12. 安全与治理

- controller 只使用 `AuthUserPrincipal.userId()`，请求体不接受 owner。
- 任何 definition、draft、version、session 查询都在 repository 层带 owner 条件；跨用户统一返回不存在。
- 内置 ID 在启动完成后形成不可变 reserved set。
- Markdown 最大 32 KiB；正文不能为空；日志、trace 和 audit 不保存正文副本。
- 用户最多 100 个未删除 Definition、20 个 enabled Definition。
- 保存、发布、启停接口增加服务端用户级限流。
- 用户 system prompt 只进入自己的 child；父 Agent 和其他 Definition 只看到 description。
- USER child 使用 SESSION-isolated Remote filesystem；禁止 host path、absolute path 和 additional roots。
- tool input 继续通过不可绕过的 runtime permission 检查；front matter allowlist 不是唯一防线。
- child 继承父 DENY，平台最新 DENY 始终优先。
- Secret 不进入 Markdown；第一期没有 secret reference 字段。
- 发布与运行失败记录 definition/version/snapshot id，不记录完整 prompt。

## 13. 内置 Definition 发布

目录：

```text
backend/src/main/resources/agents/
├── researcher.md
├── reviewer.md
└── planner.md
```

职责：

- `researcher`：搜集和核对事实，区分证据与推断；默认只读工具。
- `reviewer`：审查正确性、安全性、回归风险和可维护性；默认只读工具。
- `planner`：拆解目标、识别依赖、风险和验收标准；默认只读工具。

启动行为：

1. 枚举所有 classpath resource，不能假设资源是普通文件；支持打包 JAR。
2. 以文件名生成 `agent_id`，严格校验 Markdown。
3. 重复 ID、保留名冲突、未知字段或解析失败直接阻止应用启动，错误包含资源名和 issue 位置。
4. 将 hash/compiled result 登记成 immutable builtin version，用于 Session FK。
5. 代码库 classpath snapshot 是当前进程内置运行真相；数据库保存历史和 Session 引用。

`general-purpose` 由 synthetic adapter 提供，不在该目录放同名 declaration。

滚动发布期间旧、新节点可短暂持有不同 classpath snapshot。feature 第一次上线必须先完成数据库 migration，再部署全部新节点，最后开启 Catalog feature；不要让旧 `DynamicSubagentsMiddleware` 节点与已开放用户发布流量长期混跑。

## 14. 可观测性

日志字段：

```text
userId
parentSessionId / childSessionId
snapshotId
agentId
definitionId / version
source
policyRevision
operation
result / errorCode
```

指标：

- draft create/save/publish/enable/disable 成功与失败计数；
- publish validation issue 按 code 计数；
- enabled definition 数分布；
- snapshot 构建耗时和大小；
- materialization 耗时与失败；
- unknown exposure binding；
- reserved workspace path 拒绝次数；
- cross-owner access 拒绝次数；
- AgentScope label guard 拒绝次数；
- pinned-version follow-up 成功/失败；
- runtime factory cache hit/miss。

告警：

- 任意 `SubagentExposedEvent` 无法映射到当前 snapshot；
- 新 child session 缺失 version binding；
- 内置定义在不同节点出现不同 hash 且超出正常滚动窗口；
- runtime 退化为 Local filesystem；
- Catalog snapshot 出现超过 20 个用户定义。

## 15. 测试策略

接口是测试面；真实 AgentScope middleware 和 PostgreSQL 约束必须有集成证据。

### 15.1 Markdown compiler

- 合法内置/用户示例；
- 每个未知字段、错误类型和枚举；
- front matter 缺失、正文为空、UTF-8/32 KiB 边界；
- ID、display name、description、steps 边界；
- 用户 `shared`、非 inherit model、高风险字段拒绝；
- tools 省略、空数组、显式列表三种不同语义；
- issue 字段和行号；
- hash 对换行规范化策略稳定。

### 15.2 Catalog 接口

- Alice 只能看到 builtin + Alice 定义；
- Alice/Bob 可创建相同用户 ID，但不能占用 builtin ID；
- ID 在软删除后不能复用，可以恢复；
- invalid draft 可保存，不能发布；
- revision conflict 不覆盖；
- 发布 V1/V2、幂等发布、启用保持；
- 无版本不能启用；
- 20 enabled / 100 definitions 并发 quota；
- disabled/deleted version 可通过正确 Session binding 解析；
- 跨 owner resolvePinned 返回不存在。

### 15.3 Runtime 集成

必须构造真实 HarnessAgent + fake model/tool，不用手工伪造 exposure event 代替：

- manager 同时包含 `general-purpose`、3 个 builtin 和当前用户 enabled 定义；
- 两用户并发、同名用户 Agent，各自 system prompt/factory 不交叉；
- Alice 发布 V2 发生在 Alice V1 turn 中途，本 turn spawn 仍绑定 V1；
- disable 发生在 turn 中途不改变 snapshot，下一 turn 不可见；
- exposure 原子写正确 definition/version；
- V1 child follow-up 在 V2 发布、停用、软删除后仍使用 V1；
- policy 收紧后 V1 child 的有效 tools 缩减；
- builtin child 使用 USER Remote filesystem；
- user child 使用 SESSION Remote filesystem，两个 child/用户互不可见；
- 没有 Local filesystem fallback；
- USER workspace `subagents/*.md` 无法写入且不会出现在 manager；
- child toolkit 不含 shell、spawn/send/list/task、Memory、Plan、Skill manage、MCP；
- `agent_send`、`agent_list` 不在父模型 tool schema；
- 平台替换后的 Subagents system 段也不再指导模型调用 `agent_send/agent_list`；
- `agent_spawn(label=...)` 被 guard 拒绝；
- background spawn 仍固定版本并完成 exposure/task 投影。

### 15.4 API 与 UI

- 未认证拒绝；owner 由 principal 决定；
- 404 不泄露其他用户定义；
- 409/422/429 状态和 ApiResponse code；
- 三个 Tab 不破坏现有顶级 Agent 列表和 topology route；
- builtin 只读；
- 草稿保存、校验、发布、启停、删除、恢复；
- 未保存内容提示；
- revision conflict 保留本地文本；
- 版本列表与只读预览；
- `npm test`、`npm run lint`、`npm run build`。

### 15.5 回归

- 现有 `HarnessAgentExecutorTest`、`AgentScopeHarnessRuntimeTest`、`ChatServiceImplTest`；
- 父/子/兄弟 session 并发模型不变；
- SSE 不把 child delta 拼进父气泡；
- assignment 仍是 child 首条 SYSTEM；
- background timeout promotion 和 wait/cancel 保持；
- 当前 JDK 要求为 26，验证命令必须使用 JDK 26，不能把 JDK 23 的编译失败误判为代码失败。

## 16. 实施顺序与完成标准

### Phase 0：AgentScope compatibility spike

工作：

- 用真实 2.0.1 builder 证明 `.disableDynamicSubagents()` 的 middleware 顺序；
- 证明自定义 middleware 能在 acting 前安装 combined `CTX_AGENT_MANAGER`；
- 证明 ToolsConfig 能移除 `agent_send/agent_list`；
- 证明平台 prompt Adapter 能移除 SDK 对已禁用工具的说明并正确列出 combined snapshot；
- 证明 label guard 和 Remote filesystem materialization；
- 固化为自动化测试。

完成标准：上述行为全部由自动化集成测试证明。任何一项无法实现时，先调整 runtime seam 或采用受控 SDK patch；不得继续堆 CRUD/UI 掩盖运行基础不成立。

### Phase 1：Schema、compiler 与 Catalog

工作：

- Flyway 表和 `agent_sessions` binding；
- entity/mapper；
- Markdown compiler、capability/quota policy；
- Catalog 深模块；
- builtin loader、3 个 Markdown、synthetic general-purpose；
- audit。

完成标准：Catalog 接口测试全部通过；内置非法资源能阻止启动；数据库约束和版本固定可观察。

### Phase 2：Harness runtime 接入

工作：

- reserved workspace path；
- `.disableDynamicSubagents()` 和静态 builtins；
- turn snapshot + Catalog middleware；
- runtime factory；
- exposure binding；
- pinned child follow-up；
- SDK shared tool surface 收敛。

完成标准：15.3 的两用户并发、版本切换、filesystem 和工具隔离测试全部通过；现有协作回归保持绿色。

### Phase 3：管理接口

工作：

- `/api/me/subagents` controller/DTO；
- revision、publish、enable、delete/restore transaction；
- error/status mapping；
- 用户级限流。

完成标准：认证、ownership、状态机、quota 和 HTTP 状态集成测试全部通过。

### Phase 4：前端

工作：

- `/me/agents` 三 Tab；
- system preview；
- user list/editor/validation/version UI；
- conflict 和未保存状态。

完成标准：现有顶级 Agent 页面不回归，完整“创建草稿 → 修正校验 → 发布 → 启用 → 下一父 turn 可调用 → 停用”流程通过。

### Phase 5：发布

工作：

- 先 migration，后新 backend，再 frontend；
- Catalog feature flag 默认关闭；
- 回填可确认的 general-purpose 历史 child；
- 全节点升级后开启；
- 观察 unknown binding、reserved path、runtime filesystem 和跨用户指标。

完成标准：所有新 child 都有 binding；无 Dynamic registry 调用；无 workspace 动态声明；观测窗口内无跨用户/版本错误。

## 17. 预计代码落点

后端建议新增：

```text
backend/src/main/java/com/h/backend/chat/domain/subagentdefinition/
  SubagentDefinitionCatalog.java
  SubagentRuntimeFactory.java
  SubagentMarkdownCompiler.java
  SubagentCapabilityPolicy.java
  model/...

backend/src/main/java/com/h/backend/chat/application/impl/
  SubagentDefinitionCatalogImpl.java

backend/src/main/java/com/h/backend/chat/infrastructure/persistence/
  entity/AgentDefinitionEntity.java
  entity/AgentDefinitionDraftEntity.java
  entity/AgentDefinitionVersionEntity.java
  entity/AgentDefinitionAuditLogEntity.java
  mapper/...

backend/src/main/java/com/h/backend/chat/infrastructure/subagent/
  ClasspathBuiltinDefinitionAdapter.java
  BuiltinVersionSynchronizer.java
  AgentScopeSubagentRuntimeFactory.java
  CatalogSubagentsMiddleware.java
  SubagentSpawnGuardMiddleware.java
  ReservedWorkspacePathAdapter.java

backend/src/main/java/com/h/backend/chat/interfaces/web/
  MeSubagentController.java

backend/src/main/resources/agents/
  researcher.md
  reviewer.md
  planner.md
```

前端建议新增/调整：

```text
frontend/app/me/agents/page.tsx
frontend/app/me/agents/subagents/new/page.tsx
frontend/app/me/agents/subagents/[agentId]/page.tsx
frontend/lib/subagent-definitions.ts
```

具体类可按仓库命名规范微调，但以下 seam 不得丢失：Catalog、runtime factory、turn snapshot、pinned binding、reserved path 和 AgentScope compatibility guard。

## 18. 拒绝的方案

### 18.1 给 shared registry 加全局锁

锁会把所有用户 reasoning/spawn 串行化，且无法解决 model call 与 tool call 之间的版本语义；也不解决 `AgentSpawnTool` 的共享 key/label Map。拒绝。

### 18.2 每用户常驻一个 HarnessAgent Bean

放大模型、Toolkit、middleware、cache 和连接资源，生命周期与 Definition/Session 混杂。用户状态本应由 RuntimeContext 和外部 store 隔离。拒绝。

### 18.3 把发布 Markdown 同步到 USER workspace 再让 SDK 扫描

数据库和文件会形成双重真相；发布与文件写不是同一事务；turn 中途更新无法可靠绑定 event 到 exact version；用户还能旁路管理流程写文件。拒绝。

### 18.4 exposure 时查询当前版本

发布可能发生在 parent snapshot、model call、tool call 和 exposure 之间，查询 current 会把 V1 child 错绑 V2。必须使用 turn snapshot。拒绝。

### 18.5 把版本编码进 agent_id

会污染父模型可见 ID、页面身份、历史会话和用户心智，并破坏稳定名称。版本使用独立 binding。拒绝。

### 18.6 复用顶级 `AgentRegistry`

顶级 Agent runtime bean 与可复用 Markdown Definition 是不同模型；把两者合并会让构造期 Bean、用户版本和 Session 混在一起。拒绝。

## 19. 最终验收标准

- `/me/agents` 同时保留顶级 Agent，并展示系统/用户 Subagent。
- 用户能保存非法草稿，但只有合法草稿能发布。
- 发布、启用、停用、软删除、恢复和 revision conflict 符合本文状态机。
- `researcher`、`reviewer`、`planner` 能被真实 `agent_spawn` 调用，不是 mock event。
- 父 Agent 只看到 builtin + 当前用户 enabled Definition 的 ID/description。
- Alice/Bob 并发且拥有同名用户 Definition 时，prompt、factory、版本、工具、Workspace、Memory、消息和 Session 不交叉。
- turn 中途发布/停用不改变 snapshot；新 turn 使用新状态。
- child Session 固定 version；新版本、停用、删除后 follow-up 仍解析原版本。
- 平台最新安全政策能缩减历史版本能力。
- 新 child Session 原子记录 Definition Binding。
- 用户 workspace `subagents/*.md` 不再是执行入口。
- 运行时不使用 `DynamicSubagentsMiddleware.replaceAgents`。
- 父 tool schema 不含 `agent_send/agent_list`，非空 spawn label 被拒绝。
- 用户 child 使用 SESSION-isolated PostgreSQL Remote filesystem；内置 child 使用 USER-shared Remote filesystem；不退化到 Local。
- 内置资源错误 fail-fast，并有资源名/字段/行号。
- owner、quota、audit、限流和日志脱敏均有测试。
- backend JDK 26 tests 与 frontend test/lint/build 全部通过。

## 20. AgentScope 参考

- [AgentScope Java `AgentSpawnTool` 当前主线源码](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-harness/src/main/java/io/agentscope/harness/agent/tool/AgentSpawnTool.java)
- [AgentScope Java `DynamicSubagentsMiddleware` 当前主线源码](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/DynamicSubagentsMiddleware.java)
- [AgentScope Java `SubagentsMiddleware` 当前主线源码](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/SubagentsMiddleware.java)
- [AgentScope Java Subagent 文档（v2.0.1）](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.1/docs/v2/en/docs/harness/subagent.md)
