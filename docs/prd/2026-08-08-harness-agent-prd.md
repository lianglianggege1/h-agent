# harness-agent 产品 PRD 与原型说明

## 1. 产品定位

`harness-agent` 是一个移动优先的 Agent 协作聊天工作台。用户通过父 Agent 对话提出目标，父 Agent 根据目标动态创建协作 Agent 分工执行，用户可以查看协作进度、进入某个协作 Agent 的对话流并追加要求，然后返回父 Agent 获取汇总结果。

本产品不是 Agent 管理平台，不要求用户提前创建、维护或长期保存一批子 Agent。协作 Agent 服务于当前会话，执行结束后不作为长期 Agent 资产保存；当前会话需要保留其对话上下文和结果，供用户继续追问。

在现有系统中，Harness 不作为通用助手的自动模式，也不与领域 Agent 混为同一种实现。用户创建新会话时明确选择“通用助手”“领域 Agent”或“协作 Agent”，会话从创建开始绑定对应的后端 Agent 实例和运行时，后续不在三类之间自动切换。

## 2. 设计范围

### 本期原型覆盖

- 移动优先聊天页，沿用当前前端窄屏布局与交互。
- 新会话三类入口：通用助手、领域 Agent、协作 Agent。
- 领域 Agent 的二级搜索与选择。
- 父 Agent 富文本聊天流。
- 最近会话滚动列表，点击后继续聊天。
- 协作 Agent 数量与具体状态列表。
- 协作 Agent 头像入口。
- 协作 Agent 独立对话流。
- 在协作 Agent 对话流中追加要求，并返回父 Agent。
- 用户级长期记忆独立工作区。
- 长期记忆以 Markdown 文档呈现，由用户与 Agent 共同维护。
- `/me` 能力管理中心，保留 SystemPrompt、知识库和领域 Agent 管理，并新增 Harness Agent 能力分组。
- 独立“我的 Skills”工作区，管理当前用户自己创建或由 Agent 辅助创建的个人 Skill。
- 系统内置 Skill 只读展示，由配置文件固定 revision 和 Artifact Descriptor，内容保存在 MinIO System Skill 专用 Bucket。
- 用户 Skill 的源码与历史在 Gitee，发布时生成 MinIO 不可变运行制品；在线 Agent 不回源 Gitee。
- Skill Proposal、不可变 Release、生效版本和启用状态分离；未发布 Proposal 不参与任何 Agent 运行。
- 通用助手、领域 Agent 和 Harness 父 Agent 共用当前用户的 Skill 快照；Subagent 本期不自动加载 Skill。

### 明确不进入本期界面设计

- Team 或固定工作组管理。
- Agent 资产库、子 Agent 永久保存与复用。
- 会话台账页面。
- 完整会话记录页面。
- 上下文窗口快照页面。
- 角色、审核员、多人发布审批、组织权限和 Gitee Pull Request 审核流程。
- Skill 市场、分享、协作、导入导出和外部 Git 仓库连接。
- 自动重试、自动改派和复杂调度配置。

这些内容可以作为后续产品路线，但不改变本期工作台的核心体验。

## 3. 核心概念

| 概念 | 产品含义 |
| --- | --- |
| 父 Agent | 当前用户正在对话的主 Agent，负责理解目标、拆分委托和汇总结果。 |
| 协作 Agent | 父 Agent 根据当前目标动态创建的协作者，承担一项具体工作；服务当前会话，不进入长期 Agent 资产库。 |
| 通用助手会话 | 绑定 `standard-chat`，使用 SystemPrompt、知识库与通用工具的流式聊天会话。 |
| 领域 Agent 会话 | 绑定用户选择的具体领域 `agentId`，使用该 Agent 预定义的专业编排和能力。 |
| 协作 Agent 会话 | 绑定 `harness-agent`，使用 Harness 工作区、Skill、长期记忆、子 Agent 与任务编排能力。 |
| 协作进度 | 当前会话中协作 Agent 的数量、头像、名称和状态列表。 |
| 最近会话 | 用户最近打开过的会话名称列表，点击即可继续聊天，不展示执行状态。 |
| 用户长期记忆 | 用户维度的 Markdown 文档，由用户和 Agent 共同维护，可直接编辑。 |
| Skill | 可被后续会话匹配和调用的一组可复用能力说明、步骤、约束和静态资源；具有稳定身份，不等于草稿或某次发布内容。 |
| 系统内置 Skill | 由配置文件声明、内容保存在 MinIO System Skill 专用 Bucket、用户只读使用的系统能力；不进入个人 Skill 版本平台。 |
| 用户个人 Skill | 由当前认证用户拥有，只能由该用户管理和使用的 Skill；不同用户之间默认完全隔离。 |
| Skill Proposal | 用户或 Agent 正在编辑的可变候选内容；允许校验失败，不参与运行。 |
| Skill Release | 用户在页面显式发布产生的不可变 Skill 内容版本；发布后不能修改。 |
| 生效 Skill Release | Skill 当前选择的运行版本；只有 Skill 同时启用时才参与后续会话。 |
| 用户专属 Skill | 用户或 Agent 根据用户会话记录沉淀的、面向该用户使用习惯和工作方式的 Skill。界面中归入“Agent 创建”。 |
| Agent 创建 Skill | Agent 根据会话总结或任务执行沉淀的 Skill Proposal；Agent 不能发布、设为生效或启用。 |

## 4. 信息架构

```text
harness-agent
├── 新建会话
│   ├── 通用助手 → 直接创建
│   ├── 领域 Agent → 搜索 / 筛选 → 选择具体 Agent
│   └── 协作 Agent → 使用默认 Harness 配置直接创建
├── 父 Agent 聊天首页
│   ├── 最近会话滚动列表
│   ├── 父 Agent 对话流
│   └── 协作进度
│       └── 协作 Agent 头像入口
└── 我的（能力管理中心）
    ├── 基础能力
    │   ├── SystemPrompt 管理
    │   ├── 知识库管理
    │   ├── 领域 Agent 管理
    │   └── 我的 Skills
    │       ├── 用户创建
    │       └── Agent 创建
    └── Harness Agent 能力
        └── 用户长期记忆
            └── Markdown 记忆工作区
```

最近会话不单独作为菜单。会话台账、完整会话记录和上下文窗口快照不出现在本期导航或主页面中。

## 5. 主工作台

### 左侧栏

- 产品名称 `harness-agent`。
- “新建会话”按钮。
- “我的”入口，进入现有能力管理中心。
- “最近会话”滚动列表。

最近会话只展示会话名称，例如“竞品研究”“旅行规划”“产品 PRD”。不显示“运行中”“已完成”等状态，因为这些会话都可以点击进入并继续聊天。

### 新建会话

点击“新建会话”后显示三类一级入口：

- 通用助手：说明文案为“自由对话，使用提示词、知识库和常用工具”。点击后直接创建 `standard-chat` 会话。
- 领域 Agent：说明文案为“选择专业 Agent 处理特定领域问题”。点击后进入二级选择，支持按名称、领域、说明和标签模糊搜索，并按领域筛选；选择具体 Agent 后创建会话。
- 协作 Agent：说明文案为“拆分复杂任务，组织多个 Agent 并行处理”。点击后以默认 Harness 配置直接创建 `harness-agent` 会话，不在创建前要求选择工作区或协作模板。

关闭选择器不会创建空会话。创建新会话时归档或删除当前空会话的规则沿用现有实现。会话创建后固定绑定所选 Agent；切换类型意味着创建另一会话，不修改当前会话的 `agentId`。

### 中间对话区

父 Agent 对话沿用现有聊天体验，支持：

- 用户消息与 Agent 消息。
- Markdown 与富文本内容。
- 表格、代码块、链接和引用。
- 图片、视频、音频和文件卡片。
- 文件上传入口。
- 富文本/Markdown 编辑输入区。
- 发送、追加消息和流式回复。

父 Agent 的消息可以自然说明当前已经拆分的工作，例如“我把目标拆成资料收集、功能对比和结论汇总三个委托”。不要求在主聊天区展示内部事件明细。

Agent 更新长期记忆或创建/更新 Skill Proposal 后，在聊天流中显示轻量结果卡片。Skill 卡片只描述“草稿已创建”“草稿已更新”或“校验结果”，不能把 Proposal 描述成已经发布或可用。发布、生效和启停必须在 `/me/skills` 页面完成；卡片只展示操作结果、资产名称和“查看详情”入口。

### 移动端协作进度

移动端在父 Agent 消息流上方展示“协作进度”，用单行横向列表展示：

- 协作 Agent 数量，例如 `3`。
- 按创建/执行顺序从左到右排列的圆形头像。
- 每个协作 Agent 的圆形头像、名称和状态。
- 状态使用明确的文案和视觉标记：等待中、执行中、等待追加要求、已完成、失败、已取消。
- 协作 Agent 数量超过屏幕可见范围时，列表保持单行，支持手指左右拖动查看，不压缩头像、不自动换行。

不使用展开面板，不展示过程日志、来源数量、工具步骤或内部诊断信息。点击圆形头像直接进入对应协作 Agent 的对话流；原型中点击三个头像会切换到不同的实际页面状态，分别展示不同名称、状态、当前委托和已有消息。

协作进度只出现在 `harness-agent` 会话。通用助手和领域 Agent 沿用现有聊天展示，不渲染 Harness 子 Agent 状态区域。

## 6. 协作 Agent 对话流

协作 Agent 页面沿用父 Agent 的聊天流样式，保持统一的消息、文件和富文本能力。页面顶部展示协作 Agent 头像和名称，并提供“返回父 Agent”入口。

页面内容包括：

- 父 Agent 下发的当前委托。
- 协作 Agent 的回复。
- 用户追加的要求。
- 文件、图片、表格、代码块等产出。
- “追加要求”输入区。
- 文件附件操作。
- 页面顶部展示当前协作 Agent 的圆形头像、名称和状态；“返回父 Agent”返回原父 Agent 对话流。

原型交互示例：

- 资料收集协作 Agent：显示“执行中”、官方资料收集委托和来源整理消息。
- 功能对比协作 Agent：显示“已完成”、产品 A/B 对比委托和表格/结论消息。
- 结论汇总协作 Agent：显示“等待输入”、汇总委托和可追加判断标准的消息。

追加要求不会覆盖原始委托，而是作为当前协作 Agent 上下文中的新消息。提交后自动继续执行，并将新的状态与结果同步回父 Agent。协作 Agent 完成后，当前会话内仍可继续追加要求；追加后回到执行状态。

## 7. 用户长期记忆工作区

用户长期记忆位于 `/me` 的“Harness Agent 能力”分组，不作为聊天抽屉的独立入口，也不在主聊天页面直接展开某个文件路径。

进入工作区后：

- 以 Markdown 文档视图展示用户级长期记忆。
- 支持 Markdown 编辑、保存和取消修改。
- 可以按章节组织工作偏好、个人信息、项目知识等内容。
- 展示最近修改来源，区分用户编辑和 Agent 生成。
- Agent 可以在聊天中根据用户指令生成记忆内容并写入文档。
- 用户和 Agent 共同维护同一份长期记忆，不把 Agent 生成的内容隐藏成不可编辑的系统状态。
- 工作区展示当前 Markdown 正文、章节导航、编辑按钮、保存/取消操作和最近修改来源。
- 原型页面提供“共同记忆”标题、Markdown 编辑区、分类切换、保存/取消按钮，以及 Agent 已自动写入文档的最近更新来源。
- 长期记忆页和协作 Agent 对话页与父聊天页使用同一移动窄版容器，最大宽度 448px，在桌面预览中居中显示，在手机屏幕中自适应铺满。

Agent 生成的记忆内容直接写入共同维护的 Markdown 文档，不设置人工审核或“暂不处理”流程；用户仍可在工作区直接编辑和保存文档。

## 8. 我的与 Skills 工作区

### 我的

`/me` 延续现有能力管理中心的职责，页面分为两组：

- 基础能力：SystemPrompt 管理、知识库管理、领域 Agent 管理、我的 Skills。
- Harness Agent 能力：用户长期记忆。

新增能力使用与现有页面一致的米白背景、白色圆角卡片、黑色主文字和橙色强调色，不建立第二套视觉体系。长期记忆卡片展示最近更新时间；我的 Skills 入口展示待处理 Proposal、已发布和已启用数量。

### Skills

“我的 Skills”从 `/me` 的基础能力分组进入，不与长期记忆混在同一页面。页面只管理当前认证用户拥有的个人 Skill，并按来源区分“用户创建”和“Agent 创建”。第一版不支持 Skill 包上传导入、导出、分享、公开或协作；`assets/**` 中允许类型的资源文件仍可在 Proposal 编辑页上传。

用户和 Agent 创建 Skill 都先形成 Skill Proposal。Agent 只能创建、修改和校验 Proposal；发布、设为生效、启停、撤销和归档只能由用户在页面中操作。页面提供轻量文本编辑器处理 `SKILL.md` 和 `references/**`，并支持允许类型的静态资源上传/删除，不建设完整在线 IDE。

保存会在短生命周期 Proposal branch 上形成新的草稿 commit，不产生 Release，也不改变当前生效版本或启用状态。普通结构错误的 Proposal 可以继续编辑；高置信度凭据、路径穿越和危险文件不能写入远端 Git 历史。每个 Skill 同时最多保留一个 OPEN Proposal，放弃后删除分支、草稿文件和数据库 Proposal 记录。

用户发布时必须使用当前已校验的 Proposal head，并填写简短版本说明。发布只生成不可变、按 Skill 独立递增的 `vN` Release，不自动设为生效，也不自动启用。发布完成后页面必须跳转新 Release 详情，展示完整文件、与基线的差异、校验结果、digest 和简短 Commit ID。

“设为生效版本”是 Release 详情页上的独立动作；“启用/停用”是 Skill 级独立开关。产品不提供“发布并设为生效”“发布并启用”或任何等价组合操作，也不建立审核员、审核状态或查看凭证。用户在独立详情页自行检查后确认即可。

Release 发布后不能修改。用户可以基于当前或任意历史 Release 创建升级 Proposal，修改后发布新的最大版本号；若基线不是当前版本，页面必须同时展示历史基线与当前生效版本的差异。回滚不发布新版本，而是把历史 Release 直接重新设为生效版本。

错误 Release 可以标记为已撤销，内容和记录永久保留且不能再次生效；当前生效 Release 必须先切换，或先停用并在撤销时清空生效指针。只有从未发布的草稿 Skill 可以彻底删除；存在 Release 后只能归档。归档自动停用，恢复后仍保持停用。

Skill 卡片展示名称、来源、Proposal 状态、当前生效版本、启用状态和最近更新时间。Agent 创建的卡片还展示具体来源，例如“来自会话‘竞品研究’”或“来自任务执行过程”。不同用户的 Skill 完全隔离，`skill_key` 只在用户内唯一；系统内置 Skill 的 key 是保留名称，个人 Skill 不能覆盖。

系统内置 Skill 由配置文件声明，内容以确定性 TAR 制品保存在 MinIO System Skill 专用 Bucket。配置固定 `revision + mediaType + size + sha256 + objectKey`，用户可在“当前可用 Skill”中只读查看并识别“系统内置”来源，但不能在“我的 Skills”中编辑、发布或启停。配置变更第一版通过重新生成不可变制品、更新 Descriptor 并重启或重新部署生效，禁止覆盖可变 `latest` key。

用户 Skill 的 Proposal/Release 源码和历史保存在 Gitee；发布器从准确 Git tree 生成同一种确定性 TAR，以 SHA-256 内容寻址写入 MinIO User Skill 专用 Bucket，读回校验通过后 Release 才可用。Artifact 的身份是 `mediaType + size + sha256`，不能使用 multipart ETag、Git SHA 或 MinIO version ID 代替 digest。现有聊天 `ResourceStorage` 的随机 key/补偿删除语义不用于 Skill Artifact。

所有顶层 Agent 请求开始时固定“系统内置 Skill + 当前用户已启用个人 Skill”的不可变快照，并记录实际使用的 `skill_id + release_id + Artifact Descriptor`。在线运行只读取 MinIO 或按 digest 校验的本地缓存，不读取 Gitee；MinIO 故障且精确 cache hit 时可继续，cache miss 明确失败且不回退其他版本。运行中的请求不受发布、回滚或停用影响；Subagent 第一版不自动加载 Skill。

新版 Skills 采用 clean-slate cutover。旧 classpath Skill、旧 USER workspace Skill 文件及对应数据库记录全部放弃，不迁移为 Proposal 或 Release，也不提供兼容读取、双写和 fallback。实施时只能清理 Skill 相关记录，不能删除被 Memory、Plan、Task 等能力共用的 workspace 表。新版用户 Catalog 允许为空，但新版 `v1/v2/...` Release 永久保留。

Gitee 私有仓库固定为 `huajiangliangliang/hj-skill-repo`，使用 `master + 临时 Proposal/staging branch + Release tag`。平台是受管路径的唯一写入方，服务端从 `.env:GITEE_TOKEN` 读取凭据；用户界面不展示完整仓库地址、Proposal branch 或 Token。Gitee 不可用时暂停依赖源码的创建、保存、校验和发布；已验证 Release 的运行、生效、启用和回滚不回源 Gitee，停用、撤销和归档始终可用。

当 Skill 数量较多时，页面提供统一的模糊搜索、来源筛选和分页：

- 搜索范围包括 Skill 名称、说明、来源和关键词。
- 来源筛选包括全部、用户创建、Agent 创建。
- 搜索和筛选结果共同参与分页计算，分页后仍保留当前筛选条件。
- 移动端正式产品默认每页展示 10 个 Skill；HTML 演示原型每页展示 3 个，以便直接验证分页。通过“上一页 / 下一页”浏览，不使用无限滚动。

### 路由

```text
/me
/me/system-prompts
/me/knowledge-bases
/me/domain-agents
/me/memory
/me/skills
/me/skills/:skillId
/me/skills/:skillId/releases/:releaseId
```

## 9. 关键流程

### 创建新会话

```text
点击新会话
→ 选择通用助手 / 领域 Agent / 协作 Agent
→ 通用助手：直接创建 standard-chat 会话
→ 领域 Agent：搜索并选择具体 Agent，再用其 agentId 创建会话
→ 协作 Agent：直接创建 harness-agent 会话
→ 进入统一聊天页面，并根据会话绑定的运行时展示对应能力
```

### 父 Agent 创建协作 Agent

```text
用户提出目标
→ 父 Agent 理解并拆分工作
→ 动态创建一个或多个协作 Agent
→ 消息流上方显示数量、头像、名称和状态
→ 协作 Agent 独立执行
→ 父 Agent 汇总结果并继续对话
```

### 进入协作 Agent

```text
点击协作 Agent 圆形头像
→ 进入该协作 Agent 独立对话流
→ 查看当前委托与已有消息
→ 追加要求或上传文件
→ 协作 Agent 自动继续执行
→ 返回父 Agent
```

### 长期记忆共建

```text
用户在聊天中提出“记住……”
→ Agent 生成或修改 Markdown 记忆内容
→ 记忆写入用户长期记忆工作区
→ 聊天展示轻量结果卡片
→ 用户可从“我的”进入并编辑
```

### Skill 生成与使用

```text
用户创建 / Agent 根据会话生成个人 Skill
→ 创建或更新 Skill Proposal
→ 保存、编辑和校验，不改变当前运行版本
→ 用户在页面填写版本说明并发布不可变 vN Release
→ 自动进入 Release 详情查看内容、差异、校验和 digest
→ 用户单独设为生效版本
→ 用户按需单独启用
→ 下一次顶层 Agent 请求固定 System Skill 与个人 Skill Release 快照
→ 后续可以升级发布新版本、直接回滚历史版本、撤销、停用或归档
```

## 10. 产品验收标准

- 点击新会话可以选择通用助手、领域 Agent 或协作 Agent。
- 领域 Agent 进入二级搜索和筛选；通用助手与协作 Agent 可以直接创建。
- 会话创建后固定绑定一个 Agent 实例，不在会话中自动切换类型。
- 用户进入页面后能在一个工作台中完成父 Agent 聊天。
- 最近会话可以滚动查看，点击任意会话即可继续聊天。
- 父 Agent 可以产生多个协作 Agent，消息流上方显示简洁进度。
- 点击协作 Agent 头像可以进入独立对话流。
- 协作 Agent 对话支持现有聊天中的 Markdown、富文本和文件能力。
- 用户可以追加要求，协作 Agent 自动继续，且可以返回父 Agent。
- “我的”保留 SystemPrompt、知识库和领域 Agent 管理，并新增“Harness Agent 能力”分组。
- 用户长期记忆从“我的”进入，拥有独立 Markdown 工作区。
- 用户和 Agent 都可以修改长期记忆。
- 我的 Skills 从“我的”进入，按“用户创建”和“Agent 创建”分组，只展示当前用户拥有的个人 Skill。
- 系统内置 Skill 由配置和 MinIO 提供，只读展示，不进入个人 Skill 管理。
- System/User Skill 均以 `mediaType + size + sha256` 的 MinIO 不可变 Artifact 运行；Gitee 只承担 User Skill 源码与历史。
- Agent 创建的 Skill 可以追溯到来源会话或任务执行过程。
- 用户新建和 Agent 自动沉淀只产生 Proposal；Agent 不能发布、设为生效或启用。
- Proposal 保存不会改变已有生效版本；校验失败或过期校验不能发布。
- 发布必须填写版本说明，只生成不可变 `vN`，随后进入 Release 详情页。
- 产品不存在“发布并设为生效”“发布并启用”或正式审核状态。
- 用户可以编辑 Proposal、查看和比较 Release、升级、直接回滚、撤销、切换生效版本、停用、启用和归档 Skill。
- 同一个 Skill 最多一个活动 Proposal；放弃草稿不保留 Proposal 历史。
- 已发布 Release 不能修改或普通删除；错误版本只能撤销，已发布 Skill 只能归档。
- 不同用户的个人 Skill 完全隔离，系统内置 key 不能被个人 Skill 覆盖。
- 所有顶层 Agent 请求固定当前用户的 Skill 快照；Subagent 本期不自动加载 Skill。
- MinIO 故障时仅允许精确 digest 的已验证缓存继续；cache miss 不回退 Gitee、旧版本或 Legacy Skill。
- 旧 Skill 文件和数据库记录不迁移，新版不存在旧来源兼容读取或双写。
- 首页就是父 Agent 聊天，不再额外设置“聊天工作台”菜单入口。
- 聊天抽屉只保留新会话、最近会话、我的和退出登录。
- 主页面不出现会话台账、完整会话记录和上下文窗口快照的产品入口。

## 11. 后端运行时边界

三类入口共享现有会话表、历史记录、并发保护和外层 SSE 接口，但使用不同 Agent 实例与执行器：

```text
standard-chat
→ STANDARD_STREAMING_CHAT
→ HAssistantStreamingExecutor

具体领域 agentId
→ AGENTIC_SYNC
→ AgenticSyncExecutor

harness-agent
→ HARNESS_STREAMING（新增）
→ HarnessAgentExecutor（新增）
```

Harness 不复用 `AGENTIC_SYNC`：`HarnessAgent.streamEvents(...)` 会输出父 Agent 与子 Agent 的细粒度事件，后端需要将 `AgentEvent.type`、`source`、`metadata` 及 `SUBAGENT_EXPOSED` 等事件映射为前端可消费的 SSE 事件。`source` 为空代表父 Agent，非空的层级路径用于识别事件所属子 Agent。

前端不再只使用 `standard / domain` 二分判断会话形态。会话 DTO 应以 `runtimeType` 为事实来源，至少识别 `STANDARD_STREAMING_CHAT`、`AGENTIC_SYNC` 和 `HARNESS_STREAMING`；`agentId` 只负责确定具体 Agent，不负责让前端猜测运行时。

## 12. 原型文件

- [移动端 HTML 原型](./2026-08-08-harness-agent-mobile-prototype.html)
