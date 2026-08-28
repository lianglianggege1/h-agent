# Skill Git 版本平台设计

日期：2026-08-25；2026-08-26 完成产品决策确认；2026-08-28 按现有 MinIO 实现补全运行制品设计

状态：设计已确认；通用资源 MinIO 已实施，Skill Catalog 与 Skill Artifact 尚未实施

业内依据与兼容性核对见 [`2026-08-28-skill-runtime-artifact-minio-research.md`](./2026-08-28-skill-runtime-artifact-minio-research.md)，其中只使用 MinIO、Amazon S3、OCI 与 SLSA 一手资料。

## 1. 设计结论

H Agent 的 Skill 分为两个互不混用的来源：

1. **系统内置 Skill**：由应用配置声明，内容以不可变制品保存在 MinIO 的 Skill 专用 Bucket；配置固定版本和制品描述符，用户只读，不进入 Gitee 版本平台。
2. **用户个人 Skill**：每个登录用户都可以创建自己的 Skill；可编辑源码和发布历史保存在固定 Gitee 私有仓库，产品状态保存在 PostgreSQL；发布时生成不可变 MinIO 运行制品。

用户个人 Skill 使用以下生命周期：

```text
创建或编辑 Proposal
  -> 保存与校验
  -> 用户发布不可变 Release vN
  -> 自动进入 Release 详情页查看文件、差异和校验结果
  -> 用户单独设为生效版本
  -> 用户单独启用 Skill
```

核心结论：

- 不建设角色、审核员、审批流、市场、分享或组织协作。
- 权限来自资源所有权：认证用户只能操作 `owner_user_id` 等于自己的 Skill。
- Agent 和页面编辑器可以创建或更新 Proposal；发布、生效、启停、撤销和归档只能由用户在页面执行。
- 发布、设为生效版本和启用是三个独立动作；不存在“发布并设为生效”或“发布并启用”。
- Release 一经发布不可修改。升级通过新 Proposal 发布下一个 `vN`，回滚通过把生效指针切回历史 Release 完成。
- 每次顶层 Agent 请求开始时固定 Skill 快照；本次执行期间不随发布、回滚或停用变化。
- Gitee 是 User Skill 源码与 Release 历史的权威来源，MinIO 是运行制品分发层；Agent 请求路径只读取已固定的 MinIO 制品或已校验本地缓存，不读取 Gitee。
- 所有顶层 Agent 使用“系统内置 Skill + 当前用户已启用的个人 Skill”；Subagent 第一期不自动加载 Skill。
- Legacy Skill 可以完全放弃；新版允许从空用户 Catalog 启动，不迁移、不兼容、不双写。

这份设计取代初版 PRD 中“创建后正式、默认启用”的快速启动妥协。

## 2. 范围

### 2.1 第一期包含

1. 用户或 Agent 创建用户个人 Skill Proposal。
2. 页面轻量编辑 `SKILL.md`、`references/**`，上传或删除允许的 `assets/**`。
3. Proposal 保存、校验、放弃和并发冲突处理。
4. 不可变 `vN` Release、版本说明、历史、diff、撤销和直接回滚。
5. 生效版本、启停状态和归档相互独立。
6. 固定接入一个 Gitee 私有仓库，并由平台管理 `master`、Proposal branch 和 Release tag。
7. Git 与 PostgreSQL 之间可恢复、可幂等的发布流程。
8. 顶层 Agent 请求级 Skill 快照和实际 Release Binding 记录。
9. 配置化文件类型、数量、容量和敏感信息校验。
10. System/User Skill 的 MinIO 不可变运行制品、制品描述符、缓存、故障降级、恢复和验收边界。

### 2.2 第一期不包含

- 角色、权限配置后台、审核员、多人审批和四眼原则。
- Skill 市场、公开、分享、复制给其他用户或协作编辑。
- Skill ZIP/目录导入、导出或外部 Git 仓库导入。
- 系统内置 Skill 管理后台、动态刷新或用户启停。
- 第二个用户 Skill Git 仓库或其他 Git provider。
- SemVer、Skill 依赖范围和传递依赖求解。
- Git LFS、大型数据集、模型权重、音视频素材和任意二进制文件。
- 用户 Skill 脚本、可执行文件、容器或构建步骤。
- Subagent 自动继承 Skill。
- 已发布 Skill 的普通永久删除和 Git history rewrite。
- Legacy Skill 迁移、兼容读取、双写、回退开关或旧版本恢复。
- 用户注销时已发布 Skill 的数据擦除；该问题保留为独立 TODO。
- MinIO Object Lock、跨站复制、KMS、分布式缓存和 CDN；这些属于生产基础设施增强，不阻塞第一期的内容寻址制品方案。

## 3. 已确认的外部约束

| 配置 | 确认值 |
| --- | --- |
| Git provider | Gitee |
| 用户 Skill 仓库 | `https://gitee.com/huajiangliangliang/hj-skill-repo.git` |
| 可见性 | 私有 |
| 集成分支 | `master` |
| 辅助分支 | 平台创建的短生命周期 Proposal/staging branch |
| Release ref | 每个 Release 对应不可复用的 tag |
| 凭据 | Gitee Personal Access Token |
| Token 来源 | 根目录 `.env` 的 `GITEE_TOKEN` |
| 仓库写入者 | 平台是受管路径与 Release tag 的唯一日常写入者 |

第一期不接受请求传入 clone URL，不允许用户连接自己的仓库，也不向前端返回服务端 Token、完整仓库地址或 Proposal branch。

## 4. Clean-slate 边界

当前代码库中的以下内容都不是新版产品事实：

1. `backend/src/main/resources/skills` 中的旧 classpath Skill。
2. Harness USER workspace 中的旧 Skill 文件及对应数据库记录。
3. `skills-lock.json` 和 `.agents/.codex` 中的开发代理技能安装信息。

实施阶段采用 clean-slate cutover：

- 旧 classpath Skill 不迁移为系统内置 Skill 或用户 Release。
- 旧 USER workspace Skill 不导入 Proposal 或 `v1`。
- 不建设 Legacy Adapter、映射表、双读、双写或 fallback。
- 可以删除旧 Skill 文件和旧 Skill 数据记录，但不能删除被 Memory、Plan、Task 等其他能力共用的 workspace 表。
- 现有 workspace Skill repository、动态发现中间件和 `skill_manage` 写入路径必须显式停用或替换，避免形成旁路。
- `.agents/.codex` 开发代理技能是否保留由开发工具需求决定，不进入产品 Catalog。
- 新版首次上线时，用户 Skill Catalog 允许为空。

“历史完全抛弃”只指切换前的 Legacy Skill。新版发布的 Release 永久保留。

## 5. 领域语言

| 术语 | 定义 |
| --- | --- |
| System Skill | 配置声明、MinIO 保存、平台只读展示的系统内置能力 |
| User Skill | 由一个认证用户拥有、仅该用户可管理和使用的个人能力 |
| Skill Proposal | 一个 User Skill 当前唯一的可变候选内容；不参与运行 |
| Skill Release | 用户显式发布形成的不可变内容快照 |
| Active Release | User Skill 当前选定的生效 Release；不等于启用状态 |
| Enabled | User Skill 是否允许后续顶层 Agent 请求绑定 Active Release |
| Runtime Snapshot | 一次顶层 Agent 请求开始时固定的可用 Skill 集合 |
| Runtime Binding | 本次执行对某个具体 Skill Release 和 digest 的不可变引用 |
| Skill Artifact | 由已校验 Skill 文件构建、供 Runtime 消费的确定性只读 bundle |
| Artifact Descriptor | 由 `mediaType + digest + size + objectKey + objectVersionId` 组成的不可变制品引用；逻辑形态借鉴 OCI Content Descriptor |
| Archived | User Skill 不再进入普通列表或运行选择，但 Release 历史仍保留 |
| Revoked Release | 永久保留但禁止再次设为生效的错误或不安全 Release |

本版不定义 Review、Reviewer、Role、Approval 或 Purge 领域对象。

## 6. 核心不变量

1. User Skill 的 owner 永远从认证身份推导，不能由请求体指定。
2. 用户只能读取和操作自己的 User Skill；不同用户的相同 `skill_key` 不冲突。
3. System Skill 的 key 是全局保留名称，User Skill 不能使用相同 key。
4. Skill ID、owner、`skill_key` 和 Git 路径首次发布后不可改变；显示名称和说明可以在新 Release 中改变。
5. 一个 User Skill 同时最多有一个 OPEN Proposal。
6. Proposal 可以存在格式错误，但不能进入运行时；放弃 Proposal 会删除分支、文件和数据库草稿记录。
7. 每次 Proposal 保存都在临时分支形成可恢复 commit；这些 commit 不是 Release。
8. 发布必须绑定当前 proposal head、有效校验结果和用户确认后的发布说明。
9. Release 使用每个 Skill 独立递增的 `v1`、`v2`、`v3`，版本号不能由用户指定。
10. Release 的文件、manifest、版本说明、校验摘要、commit、tree 和 digest 发布后不可修改。
11. 发布只产生 Release，不改变 Active Release 或 Enabled。
12. 发布成功后产品必须进入 Release 详情页；生效按钮只能出现在 Release 详情或历史详情上下文中。
13. 本版不保存“用户看过详情”的凭证；独立页面、接口和按钮是流程边界。
14. 设为生效只移动 Active Release 指针，不改变 Enabled。
15. Enabled 必须存在可用的 Active Release；Disabled 可以保留 Active Release。设为生效或启用前必须验证 Artifact 状态和当前可取得性，单个实例的偶然 cache hit 不能代表多实例已可安全 rollout。
16. Skill 已启用时切换 Active Release，会从下一次顶层 Agent 请求开始使用新指针。
17. 回滚直接把 Active Release 指向历史可用 Release，不产生新版本、不移动 tag。
18. 基于任意历史 Release 都可以创建升级 Proposal；发布仍使用新的最大 `vN`，并必须展示历史基线与当前 Active Release 的差异。
19. Revoked Release 不得再次生效；当前 Active Release 必须先切换，或先停用并在撤销时原子清空 Active Release 指针。
20. 归档自动停用；恢复归档后仍保持停用。
21. 只有从未发布的草稿 Skill 可以彻底删除；存在 Release 后只能归档。
22. 已开始的 Agent 请求始终使用创建时的 Runtime Snapshot。
23. Runtime 不读取 Proposal、`master` 或 Gitee tag；只按快照中的 Artifact Descriptor 读取 MinIO 或 digest 缓存。
24. Artifact 的内容身份是规范化 bundle 字节的 `sha256`，不是 Git commit/tree、MinIO ETag、对象 key 或 `versionId`。
25. 已登记 Release 的 Artifact 不覆盖、不普通删除；Bucket Versioning 是额外恢复保护，不能替代内容寻址和消费端 digest 校验。
26. 任一快照要求的 Artifact 无法取得或校验失败时，本次顶层请求在启动前明确失败，不能静默少加载一个 Skill。

## 7. 状态模型

### 7.1 新建、发布与生效

```text
创建 User Skill
      |
      v
OPEN Proposal
      |
保存 -> 校验失败 -> 继续编辑或放弃
      |
校验通过 + 填写版本说明
      |
      v
发布 Release vN（不可变、未自动生效）
      |
跳转 Release 详情，展示完整内容、diff、校验与 digest
      |
用户单独“设为生效版本”
      |
      v
Active Release（Enabled 状态保持不变）
      |
用户单独启用
      |
      v
下一次顶层 Agent 请求可以绑定
```

禁止任何 `publishAndActivate`、`publishAndEnable` 或等价组合命令。

### 7.2 升级

```text
选择当前或历史 Release
  -> 基于此版本创建 Proposal
  -> 编辑、保存、校验
  -> 发布新的最大 vN
  -> 查看新 Release
  -> 选择是否设为生效
```

原 Release 始终不变。即使升级内容只是修复一个字，也必须发布新 Release。

### 7.3 回滚

```text
查看历史 Release
  -> 确认差异
  -> 设为生效版本
  -> 保持原 Enabled 状态
  -> 下一次顶层请求使用历史 Release
```

回滚不产生新版本；从回滚版本继续升级时再产生新的最大 `vN`。

### 7.4 撤销与归档

- 错误 Release 可以标记 `REVOKED`，内容与历史仍永久保留。
- Active Release 不允许在 Enabled 状态下撤销；必须先切换 Active Release，或先停用并在撤销事务中清空 Active Release 指针。
- 归档 Skill 自动停用，并从普通列表和运行选择中移除。
- 恢复归档只恢复可见性，不自动启用。

## 8. 总体结构

```text
                         +-----------------------------+
应用配置 ----------------> System Skill Registry       |
                         |        |                    |
                         |        v                    |
                         | MinIO Skill 专用 Bucket    |
                         +-------------+---------------+
                                       |
认证用户 -> /me/skills -> SkillCatalog +--------------> Runtime Snapshot
                              |        |                （顶层 Agent）
                              |        |
                              v        v
                       Gitee 私有仓库  PostgreSQL
                       用户源码历史    产品状态/索引/Binding
                              |             |
                              +--发布构建---+
                                     |
                                     v
                              MinIO 不可变 Artifact
```

`SkillCatalog` 是用户 Skill 管理和运行时选择的深模块边界。Controller、Agent 工具和 Runtime 不直接操作 Git client、branch、tag 或数据库表。

### 8.1 事实归属

| 事实 | 权威来源 |
| --- | --- |
| System Skill 声明与启用 | 应用配置 |
| System Skill 版本与 Artifact Descriptor | 应用配置 |
| System Skill Artifact 字节 | MinIO Skill 专用 Bucket |
| User Skill Proposal 文件 | Gitee Proposal branch |
| User Skill Release 源码、commit、tree、tag | Gitee |
| User Skill Release 与 Artifact Descriptor 的对应关系 | PostgreSQL 不可变登记 |
| User Skill Runtime Artifact 字节 | MinIO Skill 专用 Bucket；可由准确 Git tag 重建但正常运行不回源 Git |
| owner、Skill 身份、Active Release、Enabled、Archived | PostgreSQL |
| Proposal/Release 查询索引 | PostgreSQL，可从 Git 重建 |
| Agent 实际使用的 User Skill Release | PostgreSQL Runtime Binding |

Git、PostgreSQL 和 MinIO 不保存三份可独立编辑的 Release 正文：Git 保存源码，MinIO 保存由源码确定性生成的运行 bundle，PostgreSQL 只保存描述符和状态。发现 tag、descriptor 或实际字节不一致时停止发布/运行并对账，不能静默选择一方覆盖另一方。

### 8.2 现有 MinIO 实现基线与复用边界

仓库当前已经实现聊天/生成资源的 MinIO 存储：`ResourceStorage -> MinioResourceStorage` 负责私有对象、流式 multipart、Range、大小上限、稳定错误映射和 `ResourceWriteCoordinator` 的数据库回滚补偿。真实 MinIO contract suite 已覆盖 multipart 往返、Range、补偿删除、匿名拒绝和账号权限矩阵；运行手册记录的首次真实执行为管理员豁免模式 7/7，通过并不等于前缀受限生产账号验收完成。本次复核的 58 个相关单元/架构测试全部通过。

该实现适合“每次保存产生 UUID/date key 的业务附件”，不适合直接充当 Skill Artifact Store：

- `ResourceStorage.save` 不计算 SHA-256，`StoredResource` 不返回 checksum 或 MinIO `versionId`；
- object key 是 `resources/v1/{type}/{yyyy}/{MM}/{uuid}`，不是内容寻址；
- `discard` 是未挂接数据库对象的补偿删除，而已发布 Skill Artifact 必须长期不可变；
- `open` 提供浏览器 Range/MIME 语义，Skill Runtime 需要的是完整 bundle 的 size/digest 校验；
- 当前配置和受限账号只面向 resources Bucket/prefix，不能为了 Skill 直接扩大原账号权限半径。

现有应用只校验 MinIO 连接字段格式，明确不在启动期创建/探测 Bucket，也不配置 Versioning、SSE、lifecycle、replication 或 readiness；当前运行手册仍把 HTTPS、专用生产 Bucket/账号、备份复制与恢复演练列为生产前置条件。Skill 方案必须把这些继续视为外部基础设施职责，并增加自己的部署 preflight/contract，而不是误判为“已有 MinIO Client 即已生产就绪”。

因此不把 `FILE` 类型 Skill bundle 塞进 `ResourceStorage`，也不抽取一个暴露通用 S3 CRUD 的浅接口。实施时新增独立的 `UserSkillArtifactPublisher + SkillArtifactResolver` 深模块；可以复用 MinIO Java SDK、HTTP client 配置、异常脱敏和真实 contract-test 模式，但使用独立配置、命名 Bean、Bucket/prefix 与最小权限账号。`docs/adr/0003-business-artifacts-remain-in-minio.md` 约束图片/视频/音频/文件不复制到 Observation，不把 User Skill 的 Git 源码变成第二份可编辑 MinIO 正文；两者不冲突。

## 9. Gitee 仓库模型

### 9.1 目录结构

```text
users/
  <immutable-user-id>/
    skills/
      <skill-key>/
        SKILL.md
        skill.yaml
        references/
        assets/
```

- 目录只使用不可变内部 user ID，不使用昵称、用户名或邮箱。
- `skill_key` 只需在 owner 内唯一。
- 不维护全局 `catalog.yaml`，避免所有用户发布都竞争同一个索引文件。
- `skill.yaml` 只保存可从 Release 内容重建的声明，不保存 owner、启停状态、Active Release、版本号、仓库地址或凭据。

`skill.yaml` 的逻辑结构：

```yaml
schemaVersion: 1
key: customer-feedback
displayName: 客户反馈归纳
capabilities:
  scripts: false
```

### 9.2 分支与 tag

- `refs/heads/master`：唯一集成分支，只保存正式发布内容；不是运行时生效指针。
- `refs/heads/proposal/<user-id>/<skill-id>/<proposal-id>`：一个 Proposal 的可变保存分支，发布或放弃后删除。
- `refs/heads/platform/staging/<operation-id>`：发布过程的临时验证分支，补偿结束后删除。
- `refs/tags/users/<user-id>/skills/<skill-id>/v<n>`：不可复用的 Release tag。

不依赖 Gitee 支持 custom refs、protected tag 或 atomic push。平台每次操作后必须重新读取 tag、commit、tree 和 digest 进行验证。

### 9.3 Proposal commit

每次保存 Proposal 都在 Proposal branch 新增 commit，以支持页面/Agent 写入失败后的恢复。发布时根据已校验 head 构造一个干净的 publication commit，只有该 commit 进入 `master` 和 Release tag；草稿 commit 不进入 Release 历史。

Proposal 保存允许业务格式错误，但在推送远端前仍必须拒绝路径穿越、符号链接、高置信度私钥/Token 和其他会永久污染 Git 历史的内容。

Proposal 写入同样跨越 Git 与 PostgreSQL，必须带幂等键和 expected head：平台先记录轻量 write operation，再以 expected head 为 lease 推送新 commit，最后更新数据库 head/revision。Git push 失败时数据库 head 不前进；Git 已写而数据库更新失败时，reconciler 只能根据已记录 operation 和准确 parent/head 补登记，不能把 branch 上未知 commit 自动认领为用户草稿。

### 9.4 平台唯一写入

平台是 `users/**`、Proposal/staging branch 和 User Skill Release tag 的唯一日常写入者。

用户不能通过产品获取 Gitee Token，也不能在产品中导入 Gitee 的直接修改。发现未登记 push、tag 移动或 tag 删除时：

1. 仓库连接进入 `SOURCE_DRIFTED`。
2. 暂停依赖 Git 的创建、保存、校验、发布和源码 diff；已登记 `AVAILABLE` Artifact 的运行身份不被远端变更静默改写。
3. 对账任务重新读取远端事实并定位差异。
4. 由维护者恢复到平台已登记状态后才能解除阻断。

停用、撤销和归档是降低风险的 PostgreSQL 控制面操作，任何 Gitee 故障或 drift 都不得阻断。设为生效、启用和回滚只依赖已登记且再次验证为 `AVAILABLE` 的 Artifact；不创建 Git 写入，也不从 Gitee 重建内容，因此单独的 Gitee 不可用不阻断这些动作。若 Artifact 自身被标记异常，则仍然 fail closed。

WebHook 只作为唤醒/脏标记信号；正确性依赖后续 Git/API 重读和周期对账，不假设 WebHook 恰好一次送达。

### 9.5 Token 边界

1. Token 只从进程环境 `GITEE_TOKEN` 读取；根目录 `.env` 只负责本地注入。
2. `.env` 必须保持未跟踪并被 `.gitignore` 忽略。
3. Token 不得进入 clone URL、query、命令参数、`.git/config`、commit、tag、manifest、数据库普通字段、前端、日志或错误响应。
4. Git 凭据通过进程内 credential provider 或一次性 `GIT_ASKPASS` 提供，操作结束立即释放。
5. WebHook secret 与 Gitee Token 分离。
6. 权限按 Gitee 实际提供的最小可用范围配置；不在领域模型硬编码 scope 名称。
7. 若 Personal Access Token 无法限制到单仓库，生产化前建议改用只加入该仓库的独立机器人账号；原型阶段明确接受个人账号 Token 的权限半径。

应用配置只保存 locator，不保存值：

```yaml
skillRepository:
  provider: gitee
  cloneUrl: https://gitee.com/huajiangliangliang/hj-skill-repo.git
  branch: master
  credentialSource: env:GITEE_TOKEN
```

## 10. System Skill 边界

System Skill 不进入 User Skill 的 Proposal/Release 状态机：

- 配置文件声明系统 key、显示信息、启用状态、不可变 revision 和完整 Artifact Descriptor。
- 内容以确定性 bundle 存放在 MinIO System Skill Artifact Bucket。
- 应用启动时加载配置；修改配置后通过正常重启或重新部署生效。
- 用户可以在“当前可用 Skill”中只读看到 System Skill 和来源标识。
- System Skill 不出现在“我的 Skills”管理列表，用户不能编辑、发布、启停或覆盖。
- System Skill key 构成 User Skill 的保留名称集合。

### 10.1 Bucket、账号与对象布局

Skill Artifact 不复用 `MINIO_RESOURCES_BUCKET` 或 `resources/*`：

```text
开发：可在一个 Skill 专用 Bucket 中以 system/ 与 users/ 前缀隔离
生产：h-agent-<env>-system-skills 与 h-agent-<env>-user-skills 两个独立私有 Bucket

System Bucket: v1/blobs/sha256/<digest前2位>/<digest>.skill.tar
User Bucket:   v1/users/<immutable-owner-user-id>/blobs/sha256/<digest前2位>/<digest>.skill.tar
```

- 物理 Bucket 名由 `MINIO_SYSTEM_SKILLS_BUCKET` / `MINIO_USER_SKILLS_BUCKET` 配置，不进入 Release 业务身份；descriptor 保存逻辑 store，环境迁移后仍可解析。原型合桶时两者可指向同一物理 Bucket，但必须使用不同 prefix。
- System/User 分 Bucket 是生产默认方案，因为 Versioning、默认加密、Object Lock、复制、配额和部分生命周期策略以 Bucket 为边界；不是为了模拟文件夹。User Skill 不做跨用户物理去重，同一 owner 内相同 digest 可以幂等复用。
- 生产必须使用独立 Skill 账号，不扩权当前只允许 `resources/*` 的账号。Runtime 对两个 Bucket 只需要 `GetObject/HeadObject`；User Skill 发布器仅对 User Bucket 需要 create/read/stat，不需要普通 `DeleteObject`、`DeleteObjectVersion`、ListAllBuckets、CreateBucket 或 Policy 管理。
- System Artifact 由部署流水线的独立发布身份写 System Bucket；应用不能通过产品接口写 System Artifact。
- Bucket 始终私有，不生成 public/presigned URL，不允许前端或 Agent 获得 object key；Runtime 通过后端内部流读取。
- 生产 Bucket 启用 Versioning，并记录 PUT 返回的 `objectVersionId`；Runtime 有值时按该版本读取。Versioning 用于防误覆盖/删除恢复，Artifact 主身份仍是 digest。
- 原型没有合规 WORM 要求，不默认启用 Object Lock。未来若存在法定保留或管理员也不得删除的要求，再对独立 Bucket 启用 Governance/Compliance retention；不能把 Object Lock 当普通不可变版本功能。
- 传输必须使用 HTTPS，静态加密采用 MinIO 平台默认 SSE/KMS 策略；KMS 选型和密钥轮换属于生产基础设施门槛，不把 KMS key 写入 Artifact Descriptor。

### 10.2 Artifact Descriptor 与规范化 bundle

Descriptor 逻辑结构固定为：

```json
{
  "schemaVersion": 1,
  "mediaType": "application/vnd.h-agent.skill.bundle.v1+tar",
  "digest": "sha256:<64位小写十六进制>",
  "size": 12345,
  "store": "user-skill-artifacts",
  "objectKey": "v1/users/<owner-user-id>/blobs/sha256/ab/<digest>.skill.tar",
  "objectVersionId": "<MinIO version id；开发未启用 Versioning 时可空>"
}
```

这组字段采用 OCI Content Descriptor 的通用思想：消费者在解释内容前先验证 media type、大小和 digest。MinIO ETag 不进入 Descriptor；multipart ETag 不是完整对象 MD5，更不是 Release 内容身份。

Bundle v1 是平台内部格式，不等于产品支持 ZIP 导入/导出。确定性构建规则必须由一个版本化 builder 实现并锁定 contract test：

1. 输入只来自已校验的 Skill Git 子树；不包含 `.git`、symlink、submodule、目录项或平台状态。
2. 路径使用 UTF-8、NFC 和 `/`，按 UTF-8 byte order 排序；重复/规范化后冲突路径拒绝。
3. 使用固定 TAR 变体；普通文件 mode 固定 `0644`，uid/gid/owner/group/mtime 固定为零，禁止 PAX 中不确定扩展字段。
4. 文件内容保持 Git blob 原始字节，不自动改换行或重编码；文本合法性在发布校验阶段完成。
5. Bundle 内包含平台生成的规范化 manifest，列出 schema 和全部 Git 源文件 path 的 size/SHA-256；manifest 不列出自身，也不包含外层 Bundle digest，避免循环依赖。
6. 对最终 TAR 原始字节计算 SHA-256 和 size；`builder_version` 参与 Release 登记，但不混入 bundle 内容。

发布器优先使用 `If-None-Match: *` 对最终 digest key 做 create-only PUT，上传后必须重新 HEAD/GET 并验证 size、media type 和完整 SHA-256。若对象已存在，仅在实际字节验证一致后幂等复用；不因 key 相同就盲目信任。当前 MinIO Server + Java SDK `9.0.1` 对单次/multipart 条件写和 checksum header 的行为必须由新的真实 contract test 锁定，不能只凭 S3 兼容声明假设。

对象 metadata 只保存 `Content-Type`、schema version、SHA-256 和 builder version 等技术字段；owner、显示名、版本说明、Git URL、Active/Enabled 等业务事实仍以 PostgreSQL/配置为准，不能只写在可变 object metadata/tag 中。

### 10.3 System Skill 发布

System Skill 采用部署制品流程而不是用户 Release 流程：

1. 受控源码经过与 User Skill 相同的结构、安全和路径校验。
2. 使用固定 builder 生成 bundle、digest、size 和 manifest。
3. 部署发布身份写入 System Bucket 的内容寻址 key，再读回验证。
4. 在应用配置中提交 `key + revision + descriptor + enabled`；revision 只用于展示和审计，digest 才是内容身份。
5. 部署前置检查加载并验证全部 enabled System Artifact；检查失败时不开放 Agent 流量。

配置只引用已经存在且验证通过的 Artifact。禁止先部署可变 object key、再在原 key 上覆盖内容。

配置逻辑 schema：

```yaml
systemSkills:
  - key: builtin-example
    displayName: 内置示例
    revision: 2026.08.1
    enabled: true
    artifact:
      schemaVersion: 1
      mediaType: application/vnd.h-agent.skill.bundle.v1+tar
      digest: sha256:<64-hex>
      size: 12345
      store: system-skill-artifacts
      objectKey: v1/blobs/sha256/ab/<digest>.skill.tar
      objectVersionId: <optional>
```

配置中不保存 Endpoint、Bucket 物理名、access key 或 secret；`store` 只映射到服务端受控配置。

### 10.4 Skill Artifact 深模块

逻辑接口只暴露 Skill 需要的能力：

```text
UserSkillArtifactPublisher
  storeVerified(immutableOwnerUserId, canonicalBundle)
    -> ArtifactDescriptor

SkillArtifactResolver
  openVerified(descriptor)
    -> VerifiedSkillBundle
```

两个接口内部隐藏 Bucket、MinIO Client、multipart、Version ID、HEAD/GET、digest 流式计算、临时文件原子落缓存和 SDK 异常映射。User publisher 无法选择 System namespace，Runtime resolver 完全只读；两者都不提供任意 key 的 `put/get/delete/list`，也不向业务暴露 `discard`。System publisher 是部署工具/流水线，不注册成产品写接口。孤儿清理是独立维护任务和独立凭据，不能成为发布/运行接口的一部分。

## 11. 核心接口

逻辑 interface 如下，具体语言签名在实施设计中确定：

```text
SkillCatalog
  createProposal(authenticatedUser, command)
  saveProposal(authenticatedUser, proposalId, expectedHead, changes)
  validateProposal(authenticatedUser, proposalId, expectedHead)
  discardProposal(authenticatedUser, proposalId, expectedHead)
  publishRelease(authenticatedUser, proposalId, expectedHead, releaseNote)

  getOwnSkill(authenticatedUser, skillId)
  listOwnSkills(authenticatedUser, query)
  listReleases(authenticatedUser, skillId)
  compareReleases(authenticatedUser, skillId, from, to)
  activateRelease(authenticatedUser, skillId, releaseId, expectedRevision)
  setEnabled(authenticatedUser, skillId, enabled, expectedRevision)
  revokeRelease(authenticatedUser, skillId, releaseId, reason)
  archiveSkill(authenticatedUser, skillId, expectedRevision)
  restoreSkill(authenticatedUser, skillId, expectedRevision)

  snapshotForTopLevelRun(authenticatedUser, selection)
  resolvePinned(binding)
```

接口中不存在 `publishAndActivate`、`publishAndEnable`、Review/Approval、Share、Import/Export 或 Published Skill Purge。

Agent 使用受限的 Proposal interface，只能调用 create/save/validate；即使用户在聊天中要求发布，Agent 也应返回 Proposal 或引导用户进入 Release 页面，不能代替页面执行发布、生效或启停。

### 11.1 错误语义

| 错误 | 行为 |
| --- | --- |
| `SKILL_NOT_OWNED` | 返回 404 风格结果，不泄露其他用户是否存在该 Skill |
| `SKILL_INVALID` | 返回结构化错误；Proposal 可继续编辑，不能发布 |
| `PROPOSAL_HEAD_MISMATCH` | 返回 409 和 diff 基线，不强制覆盖 |
| `VALIDATION_STALE` | 当前 head 没有有效校验，要求重新校验 |
| `ACTIVE_RELEASE_MISMATCH` | 状态已变化，返回 409，不静默覆盖 |
| `RELEASE_REVOKED` | 禁止生效或新建运行 Binding |
| `SOURCE_UNAVAILABLE` | Gitee 不可用；暂停需要 Git 的 Proposal/发布操作，运行时不回源 Gitee |
| `ARTIFACT_UNAVAILABLE` | MinIO 不可用且本地无已校验缓存；本次顶层请求启动失败 |
| `ARTIFACT_CORRUPT` | descriptor、size、media type 或 SHA-256 不一致；禁止发布/Binding/运行并告警 |
| `CREDENTIAL_UNAVAILABLE` | Token 缺失、过期或权限不足，禁止 Git 写入 |
| `SOURCE_DRIFTED` | 远端源码事实漂移，阻断 Git authoring/publish 并触发对账；不阻断停用/撤销 |
| `QUOTA_EXCEEDED` | 返回具体超限项，不创建远端 commit |

## 12. 发布一致性

Git、MinIO 与 PostgreSQL 无法组成 ACID 事务，发布使用可恢复状态机：

```text
PREPARED -> GIT_STAGED -> ARTIFACT_STORED_VERIFIED
         -> MASTER_UPDATED -> TAG_VERIFIED -> RELEASE_INDEXED -> COMPLETED
```

建议流程：

1. 请求绑定 `proposalId + expectedProposalHead + validatedHead + Idempotency-Key`。
2. 校验 owner、配额、发布说明、当前 Proposal head 和有效校验证据。
3. PostgreSQL 创建 publication operation，预留 release ID 和 Skill 内下一个版本号。
4. fetch 最新 `master` 和 Release 集合。显式选择历史 base 本身不是冲突；但历史 base 确认后又出现新 Release 时必须返回冲突并重新展示“base、最新 Release、当前 Active Release”三方差异。其他用户/Skill 更新只触发 publication commit 重建。
5. 从准确 proposal head 重新执行结构、安全、敏感信息和 digest 校验。
6. 构造干净 publication commit 并推送 staging branch；重新读取并验证目标 Skill tree。
7. 从该准确 tree 构建 canonical bundle，流式计算 SHA-256/size，写入内容寻址 MinIO key；重新 HEAD/GET 验证并在 operation 中记录完整 Descriptor。Artifact 先于正式 Git ref 写入，因此 MinIO 失败不会形成缺少运行制品的 Release。
8. 以远端 `master` SHA 为 lease 更新 `master`。不假定 Gitee 支持 atomic push。
9. 为准确 publication commit 创建 annotated Release tag，再从 Gitee 重读 tag/commit/tree，并复算目标 Skill bundle descriptor；必须和步骤 7 一致。
10. PostgreSQL 事务写入不可变 Release、版本说明、Artifact Descriptor 和 `PUBLISH` 操作日志。此步骤不写 Activation 或 Enable。
11. 删除 Proposal/staging branch；失败只进入后台清理，不影响已经登记的 Release。
12. 返回 Release 详情 URL，前端必须跳转详情页。

补偿规则：

- staging 已写、master 未更新：使用同一 operation 幂等重试或清理 staging。
- Artifact 已写、master 未更新：保留为不可见孤儿；同一 operation 幂等复用，超过宽限期且确认无 Release/operation 引用后由维护任务清理。
- master 已更新、tag 未创建：reconciler 为准确 commit 补 tag；补齐前该内容不是可用 Release。
- tag 已创建、数据库未登记：reconciler 重读 Git 与 Artifact 并验证后补登记，不移动或复用 tag。
- master CAS 失败：在最新 master 上重建只包含目标 Skill 变化的 publication commit；目标 Skill 基线变化时停止并返回冲突。
- 发布失败：保留 Proposal 和 head，用户可以修复或使用同一幂等键重试。
- operation 超时：重试使用同一 release ID、版本号和远端坐标，不能重复发布。
- 无法证明 tag、tree、Artifact Descriptor 或实际 bundle 完整：operation 失败，旧 Active Release 和 Enabled 保持不变。

## 13. 数据模型

以下为逻辑模型，不代表本次已经创建 migration。

### `skill_definitions`

- `id`, `owner_user_id`, `skill_key`
- `display_name`, `description`, `source_type`
- `active_release_id`, `enabled`, `revision`
- `archived_at`, `created_at`, `updated_at`
- 唯一约束：`(owner_user_id, skill_key)`；归档后 key 不复用

### `skill_proposals`

- `id`, `skill_id`, `base_release_id`
- `branch_name`, `head_commit_sha`, `revision`
- `validation_status`, `validated_head_sha`, `validation_result_json`
- `source_type`, `source_detail_json`, `created_by`, `updated_by`
- `status`: `OPEN | PUBLISHING`
- 唯一约束：每个 Skill 最多一个活动 Proposal

Proposal 发布或放弃后删除该记录；不建设 Proposal 历史表。

### `skill_releases`

- `id`, `skill_id`, `version_number`, `tag_name`
- `commit_sha`, `tree_sha`
- `artifact_store`, `artifact_object_key`, `artifact_object_version_id`
- `artifact_media_type`, `artifact_digest`, `artifact_size`
- `builder_version`, `validation_policy_version`, `security_policy_version`
- `release_note`, `manifest_json`, `validation_summary_json`
- `status`: `AVAILABLE | REVOKED`
- `created_by`, `created_at`, `revoked_by`, `revoked_at`, `revoke_reason`
- 唯一约束：`(skill_id, version_number)`、`tag_name`
- CHECK：`artifact_digest` 符合 `sha256:[a-f0-9]{64}`、`artifact_size > 0`、media type 等于受支持版本；AVAILABLE 行的 Artifact 字段全非空

Release 行、tag 和内容都不提供更新正文或删除的普通路径。

### `skill_publication_operations`

- `id`, `idempotency_key`, `skill_id`, `proposal_id`
- `expected_proposal_head`, `reserved_release_id`, `reserved_version_number`
- `state`, `git_coordinates_json`, `artifact_descriptor_json`, `error_code`
- `created_at`, `updated_at`

### `skill_proposal_write_operations`

- `id`, `idempotency_key`, `skill_id`, `proposal_id`
- `expected_head_commit_sha`, `target_head_commit_sha`, `state`
- `error_code`, `created_at`, `updated_at`
- 仅用于 Git/数据库部分失败恢复，不作为用户可见 Proposal 历史

### `skill_operation_logs`

- `id`, `owner_user_id`, `skill_id`, `release_id`
- `operation`: `PUBLISH | ACTIVATE | ROLLBACK | ENABLE | DISABLE | REVOKE | ARCHIVE | RESTORE`
- `from_state_json`, `to_state_json`, `actor_user_id`, `created_at`

普通 Proposal 编辑不写业务操作日志；Proposal Git commit 已承担恢复用途。

### `agent_run_skill_bindings`

- `run_id`, `snapshot_id`, `source_type`, `skill_key`, `system_revision`, `skill_id`, `release_id`
- `artifact_store`, `artifact_object_key`, `artifact_object_version_id`
- `artifact_digest`, `artifact_size`, `artifact_media_type`
- 一个 run 对同一 User Skill 只绑定一个 Release
- 不复制 Skill 文件正文

System Skill Binding 的 `skill_id/release_id` 可空，但必须记录配置中的 `skill_key + revision + Artifact Descriptor`；User Skill Binding 必须记录 `skill_id + release_id + Artifact Descriptor`。Binding 保存逻辑身份和完整性字段，不保存物理 Bucket 名或 Skill 正文。

## 14. 运行时

### 14.1 顶层请求快照

每次顶层 Agent 请求开始时生成不可变快照：

```json
{
  "snapshotId": "01K...",
  "userId": "01K...",
  "systemSkills": [
    {
      "key": "builtin-example",
      "revision": "2026.08.1",
      "artifact": {
        "mediaType": "application/vnd.h-agent.skill.bundle.v1+tar",
        "digest": "sha256:...",
        "size": 12345,
        "store": "system-skill-artifacts",
        "objectKey": "v1/blobs/sha256/...",
        "objectVersionId": "..."
      }
    }
  ],
  "userSkills": [
    {
      "skillId": "01K...",
      "releaseId": "01K...",
      "artifact": {
        "mediaType": "application/vnd.h-agent.skill.bundle.v1+tar",
        "digest": "sha256:...",
        "size": 12345,
        "store": "user-skill-artifacts",
        "objectKey": "v1/users/<owner-user-id>/blobs/sha256/...",
        "objectVersionId": "..."
      }
    }
  ]
}
```

同一快照同时用于可用 Skill 列表、内容解析和 Binding 记录。禁止在 prompt 构造后再次查询“最新版”。

### 14.2 适用范围

- 通用助手、领域 Agent 和 Harness 父 Agent 都使用当前认证用户的快照。
- 不同用户的 User Skill 不得进入彼此快照。
- System Skill 按配置进入所有适用顶层 Agent。
- Subagent 第一期不自动继承父 Agent Skill，也不自行查询用户 Catalog。
- 发布、启停、回滚和归档只影响下一次开始的顶层 Agent 请求。

### 14.3 运行制品加载

Runtime 只加载快照固定的 Artifact Descriptor，不能读取 Proposal branch、`master` 或 Gitee tag：

1. 生成快照时只选择未归档、Enabled、Active Release 为 AVAILABLE 且 Artifact 已验证的 User Skill；System Skill 来自本次部署加载的配置快照。
2. 先按 `mediaType + digest` 查询本实例的只读本地缓存；缓存文件名只使用 digest，不使用用户输入路径。
3. cache hit 也核对 size/digest；校验失败立即隔离缓存文件并按 miss 处理，不能继续解析。
4. cache miss 时按 descriptor 的逻辑 store、object key 和可选 `objectVersionId` 从 MinIO 流式下载到临时文件；下载中限制声明 size 和平台绝对上限，完成 SHA-256 校验后原子 rename 进入缓存。
5. 解析器只接受已登记 media type 和 bundle schema，再应用当前 `security_policy_version`；解包仍执行路径、数量、大小和压缩/归档炸弹等防御，即使发布阶段已经校验。
6. 任一必需 Artifact 失败时，在 Agent/model 调用前返回明确的 `ARTIFACT_UNAVAILABLE` 或 `ARTIFACT_CORRUPT`；不能静默丢弃该 Skill 后继续运行。
7. 成功解析后持久化 Binding，再构造 prompt/tool 上下文；本次执行始终使用同一解析结果。

### 14.4 缓存与多实例

- 缓存是按 digest 寻址的派生数据，不是权威来源；可随时清空并从 MinIO 重建。
- 每实例独立缓存即可，不引入分布式 cache。容量使用配置化上限和 LRU；当前/近期 Binding 只影响回收优先级，不改变内容身份。
- 不可变 Artifact 不需要内容失效广播。发布/回滚只改变下一次快照选择；撤销和归档通过 PostgreSQL 状态阻止新 Binding，缓存字节可以延迟回收但不能仅凭缓存重新绑定。
- Gitee 不可用不影响已发布 Skill 的新 Runtime Snapshot，因为请求路径不访问 Gitee；MinIO 不可用时，已校验 cache hit 可以继续，cache miss 明确失败。
- 所有实例都以 PostgreSQL 的 Release 状态和应用配置为控制面，以 Artifact digest 为数据面一致性依据；MinIO bucket notification 只能用于预热/观测，不能承担正确性。

### 14.5 启动、预热与 readiness

现有聊天资源存储选择“启动不联网、MinIO 不影响 readiness”是合理的，因为纯文本聊天不依赖附件。System Skill 会进入所有适用顶层 Agent，不能机械继承该规则：

- 进程启动阶段仍只做配置 schema 校验，不因瞬时网络故障反复 crash-loop。
- 部署开放 Agent 流量前，必须预热并验证全部 enabled System Artifact；未完成时 Skill/Agent readiness 为 `OUT_OF_SERVICE` 或由等价部署 preflight 阻断流量。
- User Artifact 不做全量启动预热；具体用户请求遇到未缓存且 MinIO 不可用时按 §14.3 明确失败，不拖垮与 Skill 无关的管理接口。

## 15. 校验与安全

### 15.1 默认配额

配额从配置读取，第一版默认值：

- 每位用户最多 20 个未归档 User Skill；
- 单文件最大 1 MB；
- 单个 Skill 总计最大 10 MB；
- 文件数和目录深度采用配置化上限，实施时根据实际解析器压测确定默认值。

不建设配额管理后台。

### 15.2 文件策略

允许 Markdown、纯文本、JSON、YAML 和 PNG/JPEG/WebP/GIF 等常见静态图片。

禁止脚本、可执行文件、压缩包、容器或构建文件；symlink、submodule、设备文件和路径穿越；HTML、SVG 等主动内容；`.git`、凭据文件、私钥；以及超出 Skill 根目录的 Markdown 引用。

外部 URL 可以作为文本保存，但平台在保存、发布或运行时不自动下载。

### 15.3 发布校验

1. 必须存在唯一 `SKILL.md`，front matter 和 `skill.yaml` 符合严格 schema。
2. MIME、扩展名、编码、路径、容量和配额必须通过。
3. 每次内容变化都会使旧 validation result 失效。
4. 高置信度私钥、Token 或密码命中时阻断保存到远端和发布，并提示用户轮换已泄漏凭据。
5. 低置信度敏感信息作为警告展示；豁免规则来自受控配置。
6. 普通格式错误阻断发布，但 Proposal 可以继续编辑。
7. 普通警告允许发布，并在 Release 确认页明确展示。
8. 运行时仍应用最新平台安全策略；历史 Release 不能凭旧规则绕过后来新增的禁止项。

## 16. 产品交互

### 16.1 信息架构

- `/me/skills`：只展示当前用户的 User Skill。
- `/me/skills/:skillId`：Proposal、当前生效版本、历史和设置。
- `/me/skills/:skillId/releases/:releaseId`：不可变 Release 详情、diff、校验、digest 和生效入口。
- “当前可用 Skill”：可以只读展示 System Skill，并标记“系统内置”；不提供管理操作。

### 16.2 列表与详情

User Skill 卡片展示名称、说明、来源、Proposal、当前生效版本、启停状态、最近发布时间以及异常提示。

详情页提供轻量文本编辑器、`assets/**` 上传/删除、Proposal 文件树与校验、base diff、当前生效内容、Release 历史/比较/升级/回滚/撤销，以及启停、归档和恢复。

不建设完整在线 IDE，也不提供 Skill 包导入导出。

### 16.3 发布体验

发布前必须完成有效校验、填写简短版本说明，并展示将发布的文件和警告。

发布成功后：

1. 只生成 `vN`；
2. Active Release 和 Enabled 都不改变；
3. 前端跳转新 Release 详情；
4. 用户查看内容、diff、校验和 digest；
5. 用户可以单独点击“设为生效版本”；
6. 启用/停用仍在独立控件完成。

任何页面、聊天卡片或 API 都不得出现“发布并设为生效”“发布并启用”或等价组合语义。

### 16.4 Agent 行为

- “帮我做成一个 Skill”只创建或更新 Proposal。
- Agent 可以生成文件、修改 Proposal、触发校验并给出版本说明建议。
- Agent 不能发布、设为生效、启停、撤销、归档或删除 Proposal。
- 聊天结果卡片只显示“已创建草稿”“已更新草稿”或“校验结果”，并提供详情入口。
- 用户要求 Agent 发布时，Agent 应引导用户打开 Proposal 页面完成发布，不代替用户执行。

### 16.5 Git 信息展示

用户只看到版本号、时间、diff、digest 和截断 Commit ID。界面不展示服务端 Token、完整 Gitee 地址、Proposal branch 或内部 publication operation。

## 17. HTTP 路由草案

```text
GET    /api/me/skills
POST   /api/me/skills
GET    /api/me/skills/{skillId}
DELETE /api/me/skills/{skillId}                    # 仅从未发布的 Skill

POST   /api/me/skills/{skillId}/proposal
GET    /api/me/skills/{skillId}/proposal
PUT    /api/me/skills/{skillId}/proposal
POST   /api/me/skills/{skillId}/proposal/validate
DELETE /api/me/skills/{skillId}/proposal

POST   /api/me/skills/{skillId}/releases
GET    /api/me/skills/{skillId}/releases
GET    /api/me/skills/{skillId}/releases/{releaseId}
GET    /api/me/skills/{skillId}/compare?from=...&to=...
POST   /api/me/skills/{skillId}/releases/{releaseId}/activate
POST   /api/me/skills/{skillId}/releases/{releaseId}/revoke

PUT    /api/me/skills/{skillId}/enabled
POST   /api/me/skills/{skillId}/archive
POST   /api/me/skills/{skillId}/restore

POST   /api/internal/gitee/webhooks
```

- 创建 Proposal 和发布要求 `Idempotency-Key`。
- 保存 Proposal 使用 `expectedProposalHead`。
- 发布使用 `expectedProposalHead + validatedHead + releaseNote`。
- 生效、启停、撤销、归档和恢复使用 expected revision/`If-Match`。
- owner 只从认证身份推导。
- 发布接口只返回 Release，不接受 activation/enabled 参数。
- 用户 API 不提供仓库地址修改、连接测试、角色或审核路由。
- 删除 Skill 接口只接受不存在任何 Release 的目标；已发布 Skill 必须使用归档。

## 18. 失败语义

| 场景 | 行为 |
| --- | --- |
| Gitee 不可用 | 创建、保存、校验、发布和源码 diff 暂停；已 AVAILABLE Release 的生效、启用、回滚和运行继续，停用/撤销/归档始终可用 |
| Token 缺失/失效 | 禁止 Git 操作并告警，不尝试匿名访问私有仓库 |
| Git 写入失败 | 保留 Proposal 和旧 Active Release/Enabled |
| Git 已写、PostgreSQL 未登记 | publication operation 保留，reconciler 同时验证 Git ref 与 Artifact 后补登记 |
| tag 创建失败 | master 内容不成为 Release，reconciler 为准确 commit 补 tag |
| WebHook 丢失/延迟 | 周期 remote SHA/tag 对账发现变化 |
| 未登记 direct push | `SOURCE_DRIFTED`，阻断 Git authoring/publish，不自动导入；PostgreSQL 降险操作仍可用 |
| tag 移动/删除 | Release 标记来源漂移并告警，但不改写已登记 Artifact；修复前阻断基于该 source 的新发布/重建 |
| MinIO 不可用 + 精确 digest cache hit | PostgreSQL 状态有效且缓存重新校验通过时允许创建快照并运行 |
| MinIO 不可用 + cache miss | 返回 `ARTIFACT_UNAVAILABLE`；不回退到 Gitee、旧 Release、classpath 或同名 Skill |
| Artifact size/digest/media type 不一致 | 隔离缓存、标记完整性事件、拒绝新 Binding/运行；不自动接受新 digest |
| MinIO 已写、Git/数据库未完成 | 对象保持不可见孤儿；同 operation 幂等复用，宽限期后由独立 GC 可达性扫描清理 |
| Proposal 并发修改 | 一个成功，其他返回 409 和最新 diff 基线 |
| 不同 Skill 并发发布 | 在最新 master 上重建各自 publication commit |
| 发布失败 | 不删除草稿、不改变 Active Release、不自动启用 |

## 19. 可观测性与备份

至少记录发布成功率、状态机停留时间、Git CAS 冲突、Artifact 上传/复用/读回校验失败、孤儿数量、Proposal head 冲突、校验失败、未完成 operation、Gitee 漂移、WebHook 验签、Token 状态、Release Binding、cache hit/miss/digest mismatch/淘汰和用户配额使用量。

日志只记录内部 ID、operation ID 和截断 Commit SHA，不记录 Skill 正文、完整 prompt、凭据或敏感扫描命中正文。

### 19.1 生命周期

- 已发布、已撤销和已归档 Release 的 Artifact 都是可达对象，不设置按年龄删除、noncurrent expiration 或“只保留最近 N 版”的 ILM。
- 发布失败产生的对象只有在不被任何 Release、System Skill 配置或未完成 operation 引用，且超过配置化 grace period 后，才由独立 GC 身份删除。
- multipart 失败要主动 abort，并配置/验证当前 MinIO 版本支持的 stale multipart 清理；实现前必须以真实 contract test 锁定 MinIO Java SDK `9.0.1` 的行为。
- Runtime/发布账号无删除权限；GC 不进入在线请求路径。

### 19.2 联合备份与恢复

备份必须覆盖 PostgreSQL、Gitee bare mirror、两个 MinIO Skill Artifact Bucket 和 System Skill 配置/存储策略。复制、Versioning 和备份不是同一件事；生产必须给出环境级 RPO/RTO，并完成恢复演练。

不可变对象使恢复不需要三个系统瞬时全局事务，但 MinIO 备份集合必须是恢复数据库所引用 Artifact 的超集。恢复后开放流量前：

1. HEAD 全部 Descriptor 并核对 size；对 enabled System Skill、Active Release 做完整 GET + SHA-256，历史对象可后台分批校验。
2. 验证 User Release 的 commit/tree/tag 在 Gitee mirror 存在。
3. 验证 Bucket Versioning、TLS、SSE/KMS、IAM、复制与生命周期策略。
4. 任一 Active/System Artifact 缺失或损坏时保持 Agent readiness 关闭，不能自动切到其他版本。
5. 运行真实 Runtime Snapshot/cache smoke test 后再开放流量。

User Artifact 理论上可由准确 Git tag 和相同 builder 重建，但这只是灾难恢复手段，不替代 MinIO 备份；System Artifact 没有 Gitee 权威源，必须直接备份。若恢复介质不保留原 `objectVersionId`，只可在维护模式中对 key + size + digest 完整校验后重建存储引用，不能在普通 Runtime 中静默忽略版本差异。

## 20. 分期与剩余生产化事项

### Phase 1：用户 Skill 版本平台

- clean-slate 切换；
- 用户 owner 隔离；
- 固定 Gitee 仓库、`master`、Proposal branch 和 Release tag；
- Proposal 编辑、校验、放弃；
- `vN` Release、详情确认、生效、启停、历史、回滚、撤销和归档；
- 发布状态机、对账和最小操作日志；
- 顶层 Agent Runtime Snapshot/Binding；
- System/User Skill 的确定性 bundle、Artifact Descriptor 和独立 `UserSkillArtifactPublisher + SkillArtifactResolver`；
- Skill 专用 MinIO Bucket、内容寻址、Versioning、digest cache 和 fail-closed 运行语义；
- 文件、配额和敏感信息安全边界。

### 实施前必须锁定的 MinIO contract

- Java SDK `9.0.1` 的单次/multipart `If-None-Match: *` 条件写、并发同 digest 和稳定错误映射；
- checksum header/trailer、PUT 返回 `versionId`、HEAD/GET checksum 暴露方式；
- Versioning 的显式 version GET、delete marker 和备份/复制恢复；
- 当前服务端 stale multipart 自动清理与 lifecycle 兼容行为；
- prefix/Bucket policy 对跨 namespace、DeleteObject、DeleteObjectVersion 和管理操作的拒绝；
- 本地 cache single-flight、半文件清理、原子 rename、容量淘汰和重启恢复。

这些是实现事实验证，不再是产品设计 TODO；必须由新的 Skill Artifact 真实 contract test 覆盖，不能混入现有资源存储的 7 个 contract test。

### 生产环境待定参数

- System/User 是否在原型合桶后拆成两个物理 Bucket，以及正式 Bucket 名；
- TLS 证书、SSE-KMS key/policy、复制拓扑、容量水位、RPO/RTO 和恢复演练频率；
- 孤儿 grace period、缓存容量/目录/水位、System Artifact 预热超时；
- 是否因合规或防管理员删除要求启用独立 Object Lock/WORM 区域。

### 未来版本候选

- 用户注销和个人 Skill 数据擦除；
- 分享、复制、协作和组织角色；
- Reviewer/Publisher 分权与审批策略；
- 导入导出和第二 Git source；
- 签名 Release/attestation 与完整 SLSA provenance；
- 可选 SemVer 和依赖；
- 受控脚本、构建、签名和沙箱执行。

## 21. 拒绝方案

1. **发布即生效或启用**：绕过用户查看 Release 的独立动作。
2. **组合发布按钮/API**：把三个产品事实重新耦合，无法保证发布后查看。
3. **直接修改 Release**：破坏历史、diff、回滚和 digest 身份。
4. **正式审核与角色系统**：原型没有运营和职责隔离需求，成本超过价值。
5. **用单一全局 Owner 管理所有 Skill**：用户应该拥有自己的私有 Skill，不需要管理员代管。
6. **用户 Skill 覆盖 System Skill**：使系统行为随用户 key 冲突而改变。
7. **运行时读取 Proposal 或 `master`**：来源可变，同一次执行无法复现。
8. **移动 tag 完成修改或回滚**：破坏 Release 不可变身份。
9. **把每个草稿 commit 当 Release**：格式错误和未确认内容会进入正式历史。
10. **允许 Gitee 外部修改自动导入**：绕过 owner、校验、版本说明和发布确认。
11. **已发布 Skill 普通永久删除**：共享 Git history 中无法诚实承诺单 Skill 擦除。
12. **继续读取 Legacy Skill**：形成旁路和双重事实来源。
13. **Subagent 自动继承全部 Skill**：扩大未设计的能力传播范围。
14. **Runtime 直接读取 Gitee tag**：把源码服务故障和 tag drift 放大为在线 Agent 故障，并形成第二套缓存协议。
15. **复用聊天 `ResourceStorage` 保存 Skill Artifact**：随机 key、无 digest 和补偿删除语义与不可变制品冲突。
16. **用 ETag 或 MinIO `versionId` 充当 Release digest**：multipart/加密下 ETag 不是完整内容 hash，Versioning 只是存储恢复层。
17. **System Skill 使用可变 key/`latest`**：配置无法固定可复现内容，也无法安全缓存。
18. **第一版默认 Object Lock/WORM**：当前没有监管需求，会使孤儿清理和保留策略失去必要弹性。

## 22. 设计验收标准

- [ ] System Skill 与 User Skill 的来源、管理和运行身份明确分离。
- [ ] 用户只能管理自己的 Skill，不需要角色或审核系统。
- [ ] 不同用户可以使用相同 `skill_key`，但不能占用 System Skill 保留 key。
- [ ] 一个 Skill 最多一个活动 Proposal；放弃后不保留草稿历史。
- [ ] 保存 Proposal 不产生 Release，不改变 Active Release 或 Enabled。
- [ ] 发布必须有有效校验和版本说明，只产生不可变 `vN`。
- [ ] 发布成功后进入 Release 详情；没有任何组合发布/生效/启用入口。
- [ ] 历史 Release 可以直接回滚，升级始终产生新的最大版本号。
- [ ] Revoked Release 永久保留且不能再次生效。
- [ ] 只有从未发布的 Skill 可以彻底删除；已发布 Skill 只能归档。
- [ ] 所有顶层 Agent 使用当前用户的固定 Skill 快照，Subagent 不自动加载。
- [ ] 每次执行记录实际 User Skill Release 和 digest，不复制正文。
- [ ] Gitee 与 PostgreSQL 部分失败可以幂等恢复，不静默改变运行版本。
- [ ] System/User Skill 都以确定性 TAR 生成 `mediaType + size + sha256` Descriptor，并存入 Skill 专用 MinIO Bucket。
- [ ] Runtime 不读取 Gitee；MinIO 故障时仅精确 digest 的已验证 cache hit 可继续，cache miss 明确失败。
- [ ] Gitee 故障只阻断依赖源码的 authoring/publish；停用、撤销、归档始终可用，已 AVAILABLE Release 的生效/启用/回滚不被单独阻断。
- [ ] Artifact 上传采用内容寻址和 create-only 尝试，读回 SHA-256 通过后才允许 Release AVAILABLE。
- [ ] ETag、Git SHA 和 MinIO version ID 都不替代 Artifact SHA-256；消费前核对 size/digest/media type。
- [ ] 已发布/撤销/归档 Artifact 不按年龄过期；只清理无引用孤儿和未完成 multipart。
- [ ] 生产 Bucket/账号与 resources 隔离，私有、TLS、最小权限、Versioning；Object Lock 只有明确合规需求才启用。
- [ ] PostgreSQL、Gitee、MinIO 和 System 配置纳入联合恢复验收，Active/System Artifact 缺失时不开放 Agent 流量。
- [ ] Token 不进入仓库、普通数据库字段、前端、日志或错误响应。
- [ ] 文件类型、配额、路径和敏感信息策略在写入/发布前生效。
- [ ] Legacy Skill 不迁移、不兼容、不双写，新用户 Catalog 可以为空。
- [ ] 本设计阶段没有修改 Java/TypeScript、migration、部署配置或外部仓库。
