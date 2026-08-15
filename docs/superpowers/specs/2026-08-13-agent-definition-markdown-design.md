# Agent Definition Markdown 设计

- 日期：2026-08-13
- 状态：设计稿，未实施
- 依据：[Harness Agent PRD](../../prd/2026-08-08-harness-agent-prd.md)、[Harness 协作 Agent 后端设计](./2026-08-11-harness-subagent-backend-design.md)、[AgentScope Java 2.0.1 Subagent 能力调研](./2026-08-13-agentscope-java-subagent-capability-research.md)

## 1. 目标与范围

本设计统一系统内置 Agent 与用户自定义 Agent 的描述格式：两者都使用 AgentScope Harness 兼容的 Markdown 文档，由同一套解析、校验和运行模型处理。

本设计解决四件事：

1. 系统通过代码库 Markdown 提供所有用户可用的内置 Agent。
2. 用户可以在前端新建、编辑、校验、启用、停用和删除自己的 Agent Markdown。
3. 用户直接选择 Agent 时创建独立的顶级 Agent Session；父 Agent 委托时创建独立的协作 Agent Session。
4. 每次运行固定一个 Agent Definition Version，定义更新不会改变已经开始的会话。

本设计不把当前会话中的临时协作 Agent 变成长期 Agent 资产。长期可复用的 Markdown 定义与一次运行产生的 Agent Session 是两个不同概念。

## 2. 领域模型

### 2.1 Agent Definition

Agent Definition 是可复用的 Markdown Agent 定义，描述 Agent 的身份、用途、模型档位、步骤上限、工具/Skill 白名单、工作区模式和系统指令。

Agent Definition 有两个来源：

| 来源 | 存储 | 可见性 | 编辑权限 |
| --- | --- | --- | --- |
| `BUILTIN` | 代码库 `backend/src/main/resources/agents/*.md` | 所有用户 | 平台开发者/发布流程 |
| `USER` | 用户级定义存储或 USER namespace | 所属用户 | 定义所属用户 |

### 2.2 Agent Definition Version

Agent Definition Version 是一次不可变的 Markdown 内容和编译结果。每次发布、启用或内容变更都产生新版本；运行实例只引用版本，不直接引用“当前内容”。

### 2.3 Agent Session

Agent Session 是一次具体运行的会话身份，保存实际对话、运行、消息顺序和并发归属。它必须记录：

- `agent_definition_id`
- `agent_definition_version`
- `user_id`
- `parent_session_id`（顶级会话为空，协作 Agent 指向直接父会话）

用户修改 Agent Markdown 后：已有 Session 继续使用旧版本；新建 Session 使用最新启用版本。

### 2.4 协作 Agent

协作 Agent 是父 Agent 为当前任务创建的 Agent Session 实例。它可以引用系统内置或用户自定义的 Agent Definition，但本身不自动进入长期 Agent Catalog。

## 3. 统一 Markdown 规范

系统和用户使用同一种文档格式。第一期只开放经过平台校验的安全字段：

```md
---
description: 审查代码中的安全性、正确性和可维护性问题
mode: all
model: standard-thinking
steps: 8
tools: read_file,grep_files
skills: secure-review
workspace:
  mode: isolated
---

你是一名代码审查 Agent。

只报告能够从代码中直接验证的问题，并说明影响和修改建议。
```

字段约束：

| 字段 | 规则 |
| --- | --- |
| 文件名 | 作为 `agentId`，使用 kebab-case，长度受限且不可重复 |
| `description` | 必填；用于用户展示和父 Agent 判断是否委托 |
| `mode` | `primary`、`subagent` 或 `all` |
| `model` | 只能引用平台批准的模型档位，不接受任意 provider 字符串 |
| `steps` | 平台设置上下限，建议第一期限制为 1–20 |
| `tools` | 只能引用平台工具 Catalog 中允许继承的工具 |
| `skills` | 只能引用当前用户可访问的 Skill |
| `workspace.mode` | 用户 Agent 强制 `isolated`；内置 Agent 可由平台配置 |
| Markdown 正文 | Agent 的系统指令；限制长度并拒绝非法控制内容 |

第一期不开放任意 Java factory、host shell、任意 MCP 地址、remote URL/headers、`workspace.path`、共享工作区或用户 API Key。未知字段默认校验失败，避免出现“保存成功但运行时被忽略”的配置。

`mode` 的产品语义：

- `primary`：可被用户直接选择，不能被 `agent_spawn` 委托。
- `subagent`：只能被父 Agent 委托，不能作为顶级聊天入口。
- `all`：两种入口都允许。

## 4. 存储与加载

### 4.1 内置 Markdown

目录约定：

```text
backend/src/main/resources/agents/
├── researcher.md
├── reviewer.md
└── planner.md
```

内置文档进入 Git 版本控制，启动或构建时执行解析和校验。校验失败的内置 Agent 不应静默进入可选列表；应记录明确的启动错误或将其标记为不可用。

内置 Agent 的内容 hash 可作为版本来源，但运行时仍应把具体版本绑定到 Agent Session，确保后续部署不会改变历史会话语义。

### 4.2 用户 Markdown

用户文档可以通过前端编辑器维护，后端以用户身份作为唯一授权依据。建议逻辑路径为：

```text
agent-definitions/{agentId}.md
```

文件内容可以作为定义正文，但产品不能只依赖运行时文件扫描。保存流程应当是：

```text
Markdown
  → 解析
  → schema 校验
  → capability / ownership / quota 校验
  → 生成不可变版本
  → 更新用户 Agent Catalog
```

建议持久化的产品事实：

```text
agent_definitions
- id
- owner_user_id              -- 内置定义为空
- agent_id
- source                     -- BUILTIN / USER
- display_name
- enabled
- current_version
- deleted_at
- created_at / updated_at

agent_definition_versions
- id
- definition_id
- version
- content_hash
- markdown_content
- compiled_metadata_json
- created_at
```

系统内置定义可以在启动同步到同一 Catalog；代码库 Markdown 仍是内置内容的发布真相。用户删除建议使用软删除，历史 Session 继续能够解析原版本。

### 4.3 AgentDefinitionCatalog

新增一个深模块 `AgentDefinitionCatalog`，对上层隐藏内置来源、用户来源、版本、校验和授权细节：

```java
interface AgentDefinitionCatalog {
    List<AgentDefinitionSummary> listVisible(long userId);
    ValidationResult validate(long userId, String markdown);
    AgentDefinition saveUserDefinition(long userId, SaveDefinitionCommand command);
    AgentDefinition resolveForRun(long userId, String definitionId);
}
```

内部可以使用两个 Adapter：

- `ClasspathAgentDefinitionAdapter`：读取并校验代码库内置 Markdown。
- `UserAgentDefinitionAdapter`：读取用户定义、版本和启停状态。

运行模块只接收已解析、已授权、已固定版本的 `ResolvedAgentDefinition`，不再判断定义来自代码库还是用户。

## 5. Runtime 与并发隔离

不为每个用户常驻创建一套 Spring Agent Bean。静态定义和运行状态分离：

```text
(agent_definition_id, version, runtime_profile)
                 ↓
       immutable runtime factory/cache
                 ↓
        RuntimeContext(userId, sessionId)
```

- Agent runtime 可以由多个用户共享。
- 用户状态通过 `RuntimeContext.userId + sessionId` 隔离。
- Memory、Workspace、Skill 使用 USER/SESSION namespace。
- 不得在共享 Toolkit、Prompt 或 Middleware 中写入单个用户的运行时配置。
- 构造期配置真正不同（模型、Toolkit、MCP、middleware、权限基线）时，按定义版本创建或缓存独立 runtime。

并发规则继续按实际 Agent Session：

- 不同用户、不同 Session：并行。
- 同一用户、不同 Session：并行，受用户配额限制。
- 父、子和兄弟 Session：并行。
- 同一 Session 的重复 turn：由应用层决定是立即拒绝还是进入队列；当前实现是立即拒绝。

## 6. AgentScope 2.0.1 适配注意事项

AgentScope 2.0.1 支持动态读取 `subagents/*.md`，但其 `DynamicSubagentsMiddleware` 会把当前调用的声明替换到共享 `DefaultAgentManager`。多用户并发时可能出现用户 A 的模型调用读取到用户 B 的 Agent registry。

因此用户 Agent 上线前必须满足以下条件之一：

1. 升级到已明确修复并有并发回归测试的 AgentScope 版本。
2. 改造为每次调用创建 immutable registry，并通过当前 `RuntimeContext` 传递。
3. 按用户/定义版本使用隔离的 runtime factory/cache。
4. 在应用层先解析并授权定义，再以请求级 Agent factory 运行。

在此问题解决前，可以安全开放平台级静态内置 Agent，但不应直接把 USER-scoped 动态文件扫描当作生产级多租户实现。

## 7. 前端设计

现有 `/me/agents` 调整为两个分组或 Tab：

### 系统内置

- 展示名称、说明、标签、适用场景和 Markdown 只读预览。
- 支持“开始对话”。
- 不支持编辑、删除和停用。

### 我的 Agent

- 新建 Markdown。
- 编辑已有定义。
- 保存并校验。
- 启用/停用。
- 软删除。
- 开始对话。

编辑页建议包含：

- Agent ID：新建时填写，保存后不可修改。
- Markdown 编辑器：带模板、语法高亮和行号。
- 校验面板：显示字段级和行号级错误。
- 操作：`保存`、`保存并启用`、`开始对话`。
- 并发编辑：使用 revision/ETag，避免多个标签页静默覆盖。

用户保存非法文档时，不产生可运行版本；旧的已发布版本仍保持可运行，直到用户明确停用或删除。

## 8. 运行入口

同一份 Agent Definition 支持两个入口：

```text
用户选择内置/用户 Agent
  → 创建顶级 Agent Session
  → 固定 definitionId + version
  → 独立聊天

父 Agent 委托 Agent
  → 从当前用户可见 Catalog 解析 definition
  → 创建协作 Agent Session
  → 固定 definitionId + version
  → 父 Agent 汇总结果
```

直接选择入口需要新增“创建 Agent Session”的产品接口；不能把用户选择等同于共享一个 Gateway 子 Agent 实例。每个用户和每次选择都应得到自己的 `sessionId`，并通过现有 Session 所有权和实际 Session 级并发锁隔离。

## 9. 安全与产品治理

平台必须自行补齐以下规则：

- 认证主体生成 `userId`，不信任客户端自报身份。
- 所有 `definitionId`、`version`、`sessionId` 和协作句柄都做 owner 校验。
- 限制 Markdown 长度、steps、超时、并发和模型预算。
- 工具、Skill、模型只允许引用平台批准的 Catalog。
- 用户 Agent 默认使用隔离工作区，不开放 host shell 和任意路径。
- 远程 URL、DNS、重定向和 headers 做 SSRF 与密钥治理。
- Secret 只保存 secret reference，不把真实密钥写进 Markdown。
- 保存、启用、停用、删除、运行和失败都进入审计记录。
- 定义版本可回滚；历史 Session 固定旧版本。

## 10. 分阶段实施

### Phase 1：统一格式与内置 Catalog

- 定义并实现安全 Markdown schema。
- 将平台内置 Agent 放入 `resources/agents/`。
- 建立 Catalog 读取、校验、列表和版本固定能力。
- 支持用户直接选择内置 Agent 创建顶级 Agent Session。

### Phase 2：用户 Agent CRUD

- 增加用户定义和版本持久化。
- `/me/agents` 支持新建、编辑、校验、启停和软删除。
- 增加 revision/ETag 与 owner 校验。

### Phase 3：用户 Agent 参与 Harness 委托

- 父 Agent 可从当前用户可见 Catalog 选择 `subagent/all` 定义。
- 修复或绕过 AgentScope 2.0.1 动态 registry 共享问题。
- 增加并发、跨用户、跨节点回归测试。

### Phase 4：可靠后台任务与高级能力

- 独立任务队列、lease、重试、取消、通知和预算。
- 再评估远程 Agent、MCP、自定义代码和跨用户分享市场。

## 11. 验收标准

- 内置 Markdown 解析失败时不会静默出现在 Agent 列表。
- 两个用户同时运行同一个内置 Agent 时，状态、消息、Memory 和 Workspace 不交叉。
- 用户 A 无法读取、修改或运行用户 B 的 Agent Definition 和 Session。
- 修改用户 Agent 后，旧 Session 继续使用旧版本，新 Session 使用新版本。
- 同一 Session 的并发策略有明确响应；当前实现为立即拒绝，不隐式排队。
- 用户 Agent 的工具、Skill、模型和工作区权限均经过平台校验。
- AgentScope registry 不会因用户 A 的动态加载覆盖用户 B 的可用 Agent。
