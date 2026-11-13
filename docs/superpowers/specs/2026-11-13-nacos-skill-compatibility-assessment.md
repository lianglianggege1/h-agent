# Skill 迁移 Nacos：现有能力与业务匹配评估

> 本文保留评估阶段结论；后续实现、验证结果及部署迁移步骤见 [Nacos Skill 接入手册](../../runbooks/nacos-skill-sources.md)。

- 日期：2026-11-13
- 代码基线：`a894e7c6b0860895f6ec3440cc08129484f26744`
- 分支：`codex/nacos-skill-assessment`
- 约束：用户 Skill 管理功能保持不变；本次只评估，不切换线上读写，不修改业务代码。
- 官方事实、源码出处及版本边界见[配套官方调研](2026-11-13-nacos-skill-official-research.md)。本文中的架构和迁移步骤属于建议，尚未完成真实 Skill 写入契约验证。

## 1. 结论

**可以有条件适配，建议让 Nacos 替代 Gitee 的 Skill 源码与版本后端，保留 PostgreSQL 的业务控制和 MinIO 的不可变运行制品。不能把整个现有 Skill 模块直接替换成 Nacos API。**

最需要提前解决的是内容保真：3.2.4 会规范化 `SKILL.md` 的 name/description/version。若要求已确认内容逐字节不变，不能直接把 Nacos 导出当原始源码；必须先证明可保留/还原原内容，或保留独立的原始内容记录。该门槛未通过前，不应切换内容权威源。

Nacos 已覆盖 Skill 文件集合、草稿、版本、发布、可见性和分发，业务能力的重合度较高。剩余工作主要集中在身份映射、并发一致性、版本映射、发布补偿，以及现有 Git 专用字段的改造。没有足够依据给出一个有意义的“百分比匹配度”。

当前环境已具备原生 Skill 能力的版本基础：仓库使用 `nacos-client 3.2.4`；对项目配置的服务端执行只读 `GET /nacos/v3/admin/core/state`，实际返回 `version=3.2.4`、`ai_enabled=true`、`auth_enabled=true`、`startup_mode=standalone`。这只证明版本和模块开关，不证明当前服务账号具有 Skill 权限、Pipeline 已启用或生产容灾可用。响应中的配置中心容量参数不能作为 Skill 配额结论。

Nacos 官方当前列出 3.3 为最新版，但本次适配应先针对实际部署的 3.2.4 锁定契约，不把升级 Nacos 作为未经验证的前提。[官方版本文档](https://nacos.io/docs/v3.2/manual/user/ai/skill-registry/)

## 2. 当前系统实际做了什么

当前用户管理入口是 `/api/me/skills`，不是直接操作存储后台。相关实现：

| 层 | 当前职责 | 代码证据 |
| --- | --- | --- |
| 前端 | 列表、草稿文本及附件编辑、校验、发布、历史、比较、生效、启停、撤销、归档与恢复 | [API 类型与调用](../../../frontend/lib/skills.ts)、[详情页](../../../frontend/app/me/skills/[skillId]/page.tsx) |
| Web | owner 来自认证用户；各操作分别提供路由 | [SkillController](../../../backend/src/main/java/com/h/backend/skill/interfaces/web/SkillController.java) |
| 应用 | 编排 Proposal、验证、发布、版本与业务状态 | [SkillCatalogService](../../../backend/src/main/java/com/h/backend/skill/application/SkillCatalogService.java) |
| Gitee | Proposal 分支、文件字节、发布 PR/squash、commit 与 tag；历史详情和比较也读 Git | [GiteeSkillRepository](../../../backend/src/main/java/com/h/backend/skill/infrastructure/gitee/GiteeSkillRepository.java) |
| PostgreSQL | owner、唯一 key、Proposal head、校验绑定、vN、Active、Enabled、Revoked、Archived、操作记录、运行 Binding | [表结构](../../../backend/src/main/resources/db/migration/V20260828_02__create_skill_version_platform.sql) |
| MinIO | 确定性 TAR，SHA-256、size、media type、对象版本与校验缓存 | [MinioSkillArtifactStore](../../../backend/src/main/java/com/h/backend/skill/infrastructure/artifact/MinioSkillArtifactStore.java) |
| Runtime | 请求开始时按用户选取 Active 且 Enabled 的 Release，读取固定 Artifact；System Skill 由部署配置提供 | [SkillRuntimeService](../../../backend/src/main/java/com/h/backend/skill/application/SkillRuntimeService.java) |

必须保留的业务语义：

1. 不同用户可以有相同 `skillKey`，只能管理自己的 Skill。
2. 一个 Skill 一个活动草稿；保存不产生正式 Release。校验必须对应当前内容。
3. 发布只产生不可变 `vN`，不自动设为 Active，也不启用；用户查看详情后分别操作。
4. 回滚选择历史 Release，不改写它；撤销后不能重新生效；已发布 Skill 只能归档。
5. 启停、归档和撤销等控制由本系统执行业务校验。比如当前实现会拒绝撤销仍启用的 Active Release。
6. 一次运行固定 Release 和 digest；后续发布或回滚不改变已准备的快照。运行不依赖 Gitee。
7. 继续使用现有文件策略与校验器：Markdown/文本/JSON/YAML、静态图片；禁止脚本等主动内容。默认每用户 20 个、单文件 1 MiB、总计 10 MiB、200 个文件、8 层路径。

上述内容来自代码及配置，而非仅沿用旧设计文档。旧设计描述的完整对账恢复能力不能直接视为已落地：当前有 operation 表和写入代码，但未在 Skill 包内发现完整周期 reconciler；迁移时需要具体实现、验证恢复流程。

## 3. 匹配矩阵

| 现有业务能力 | Nacos 对应能力 | 评估与适配责任 |
| --- | --- | --- |
| `SKILL.md` + `skill.yaml` + references/assets | Skill 正文与资源文件、ZIP 导入导出 | 基础匹配；保留本系统 schema、安全策略及路径规则，不能用 Nacos 默认扫描替换 |
| PNG/JPEG 等二进制 | 3.2.4 具有 Base64 资源编码及下载还原路径 | 有源码支持；需逐字节 round-trip、混合附件和容量边界验证，不能因 DTO 是 String 就判不支持 |
| 单活动 Proposal | 单个 draft/reviewing 工作版本 | 概念匹配；head/CAS 和校验内容绑定仍需本系统保证 |
| 保存、校验、发布 | 编辑 draft、submit、Pipeline、publish | 部分匹配；本系统“校验”不能调用会自动发布的 submit |
| 用户可见 `v1/v2/...` | 3.2.4 源码支持 vN 与 SemVer | 可保留 vN 格式；仍需独立映射，不把草稿创建或导入冲突后生成的版本当产品发布序号 |
| 发布后手动生效 | online 及 latest/custom label | 语义不同；Nacos online 只表示仓库可分发，应用 Active 仍由 PG 决定，运行不读取 latest |
| 启停 | Skill enable/disable | 有同类能力，但不能建立两个生效权威；当前用户开关仍只以 PG 为准 |
| 回滚 | 固定版本读取、标签重绑定 | 保留 PG 切换 Active 的逻辑；不依赖可移动标签恢复运行内容 |
| 永久撤销 | offline 可重新 online | 不等价；保留 PG REVOKED 禁止重新绑定，不能把 offline 当永久撤销 |
| 归档/恢复、已发布禁止物理删除 | disable、版本或 Skill 删除 | 不等价；保留 PG archived 和删除约束，禁止通过业务接口调用 Nacos 删除已发布历史 |
| 私有 owner 与同名隔离 | namespace、scope、Owner、权限 | 有基础能力；业务用户和 Nacos 账号不是同一个身份，必须设计映射和越权测试 |
| Release 详情及版本比较 | 指定版本内容读取 | 可由 adapter 读版本、应用继续做文件集合比较，不依赖原生 Git diff |
| 操作审计和幂等 | 平台日志、管理 API | 不能代替业务 operation、actor、Idempotency-Key 和部分失败恢复 |
| 运行固定 digest、离线缓存 | 指定版本下载、SDK 订阅/缓存 | 不等价于现有 Artifact Descriptor 协议；本次继续使用 MinIO 和现有 Runtime |
| Agent 仅写草稿 | 管理 API 可以做更多操作 | 继续由应用的工具权限边界限制；不把通用 Nacos 管理凭据交给 Agent |
| System Skill | 可在 Nacos 管理公共/系统资源 | 技术可行，第一阶段保持部署配置 + 固定 Artifact，不混入用户迁移 |

Nacos 从 3.2.0 引入原生 Skill 管理。[官方 Skill 手册](https://nacos.io/docs/v3.2/manual/user/ai/skill-registry/) 3.2.4 的具体实现还支持 vN，且无 Pipeline 时 submit 会直接发布并更新 latest；因此以固定 tag 源码作为适配依据。[3.2.4 SkillOperationServiceImpl](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/skills/SkillOperationServiceImpl.java)

## 4. 推荐职责与接入方式

```text
现有用户页面 /api/me/skills
            |
    SkillCatalogService
      |         |                |
 PostgreSQL   Nacos Skill      Validator + TAR Builder
 owner/状态   源文件与版本              |
 vN映射/CAS   draft/published     MinIO 固定制品
 operation                         |
      +------- Runtime 固定 Release + SHA-256
```

### 4.1 Nacos 接管源码与版本后端

迁移对象是目前 Gitee 负责的内容。使用专门的 Skill 管理 adapter，对内提供“读取文件集合、保存草稿、从历史版本创建草稿、提交固定内容版本”等业务动作。不要让 Nacos adapter 假装实现 `createBranch/createPullRequest/mergePullRequest/createTag`。

应用管理使用受限的 Console/Maintainer 能力；`nacos-client` 的运行时下载与订阅能力不能直接替代管理 SDK。现有 `service-registry` 只负责 Naming，Skill 接入应在 Skill 上下文中独立装配，避免把其生命周期和权限混入服务注册。[官方 SDK 分工](https://nacos.io/docs/v3.2/manual/user/ai/skill-registry/)

### 4.2 PostgreSQL 保留产品权威

owner、Active、Enabled、Revoked、Archived、vN 以及 operation 继续由 PG 管理。Nacos 内部的 online、latest、enable 不直接改变用户运行行为。

Nacos 发布成功后，必须取回准确版本内容，验证其规范化文件集合与用户确认的 Proposal 一致，再用同一份文件构建并校验 MinIO 制品，最后 PG 登记 AVAILABLE Release。全过程固定内容 fingerprint；任何阶段不一致都失败并进入同一 operation 的恢复流程，不能重新读取“当前草稿”后悄悄接受变化。

Nacos 的上线和 PG 的可运行是两个阶段：即使 Nacos 先 online，PG 尚未登记的内容也不能被本系统 Runtime 使用。因此 Nacos 资源必须保持私有，禁止绕过应用的外部客户端把其 online 当成已获得用户授权的生效状态。

Nacos 控制台可以用于运维查看，但迁移初期应禁止其直接改写本应用托管草稿、标签和业务开关。若必须开放双入口编辑，需要额外的变更检测和冲突协议，不能只加一个 listener 就宣称一致。

### 4.3 MinIO 保留运行制品权威

继续使用现有 `SkillArtifactPublisher`、`SkillArtifactResolver`、Builder、Binding、digest 缓存和运行快照。Nacos ZIP 是交换包，不是当前业务 TAR 的内容身份；ZIP 字节 hash、版本号、Git SHA、标签都不替代 TAR digest。

这样仍能保持：Nacos 故障阻断依赖源码的管理操作，已登记 Release 的运行不依赖 Nacos；已验证的精确 MinIO 缓存命中可继续，无缓存时按现有规则失败。发布/回滚不热更新已经准备好的运行快照。

## 5. 必须解决的适配点

### 5.1 身份与同名隔离

推荐先采用应用后端代理：浏览器和模型不持有 Nacos 凭据，业务鉴权仍由 `requireOwned` 完成；环境使用独立 Skill namespace，托管资源设 PRIVATE。Nacos 资源名使用稳定内部 `skillId` 派生的名字，PG 保存其与业务 key 的映射，避免两个用户都叫 `reporting` 时碰撞。

但 Nacos 名称、SKILL.md frontmatter name 和 ZIP 根目录是否必须相同，必须用实际接口验证；不能为满足内部唯一名而改写用户 Skill 的业务 name。3.2.4 确实具有 frontmatter name/version 规范化逻辑，不能假定换一个内部名而用户内容不变；需要验证 owner/namespace 隔离，或建立可逆内容映射并保留原始来源文件。一个环境 namespace 配上同一服务账号，不会自动获得应用用户级隔离。

### 5.2 并发保存与校验

现有 API 使用 `expectedHead`，Nacos 的单 draft 约束不等于有相同 CAS 契约。需要对同 Skill 的保存、校验、发布建立跨实例串行协调；写前校验 PG revision 和内容 fingerprint，写后取回验证，再推进 PG。单机 synchronized 不足以处理多个 backend。

远端成功、PG 失败时必须恢复到明确状态：重试通过 operation 身份和内容 fingerprint 找到已经完成的写入，不能盲目再创建一版。仅在 HTTP 调用前检查一次 revision 不能阻止两个实例覆盖远端内容。

### 5.3 发布与版本映射

本系统 validate 继续由 `SkillContentValidator` 完成；Nacos Pipeline 可作为额外检查；3.2.4 的 Pipeline 启动失败路径可能直接发布，不能把它当默认 fail-closed 的安全门禁。[发布实现](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/skills/SkillOperationServiceImpl.java)Pipeline 默认关闭时 submit 可直接发布，所以不能把“点击校验”映射到 submit。Pipeline 开启后，当前一次用户发布动作可以在后端编排 submit/poll/publish，但等待超时应返回可恢复 operation，不能占用无限时长请求或绕过拒绝。

数据库独立保存 `versionNumber -> Nacos version`，PG 分配并保留产品序号；草稿放弃、发布重试不得导致用户版本错乱。每个 operation 固定远端坐标、源 fingerprint、目标 Release 与 Artifact Descriptor；故障后重试同一目标，不重复创建 Release。latest 的默认行为须实测；即使它变化，也不能驱动应用 Active。

### 5.4 内容规范化与容量

Nacos 会写入/规范化 SKILL.md 的 name、description、version，文本按 UTF-8 处理；上传前后的 SHA-256 不能默认相同。[SkillRequestUtil](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/utils/SkillRequestUtil.java)、[ResourceContentEncoder](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/utils/ResourceContentEncoder.java) 必须对用户确认的原始文件和 Nacos 规范化表示明确划界：保留原始内容与可逆映射，或在用户校验前展示规范化结果并确认其符合产品要求。禁止在发布后静默替换正文、重新生成历史 digest，或只在草稿验证原文、运行却使用另一份内容。

默认 ZIP 压缩上传上限 10 MiB、解压总量 50 MiB、500 entries，可配置；这是 ZIP 接口约束，不是所有管理 JSON 请求的统一配额。[SkillZipParser](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/utils/SkillZipParser.java)、[Constants](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/constant/Constants.java) 本系统允许 10 MiB 文件总量，在不可压缩图片及 ZIP 头开销下，上传包可能超过 10 MiB；不能仅凭数字相等就认为覆盖。配置需留足编码、归档和网关开销，再按现有业务配额拒绝超额输入。

### 5.5 Git 字段与页面兼容

不能只改 `repository.provider=nacos`：`SkillCatalogService` 直接依赖 Gitee；数据库的 `tag_name/commit_sha/branch_name/head_commit_sha` 为 Git 专用字段，部分字段 `VARCHAR(40) NOT NULL`；前端还显示 “Commit”。

新增 provider、远端 Skill/版本坐标及足够长度的 source revision/fingerprint 字段，用新增 migration 演进，保留历史 Gitee commit 的真实追溯。Proposal 乐观并发字段逐步改为后端生成的、不冒充 Git commit 的 revision token。

用户管理动作与页面流程可以不变；若完全移除 Git，新版本没有真实 Commit，页面需将来源信息展示为“来源版本/内容指纹”，或明确显示无 Git Commit。不能把 Nacos version 或随意生成的 hash 填到 Commit 字段冒充 Git 历史。若要求包括 Commit 展示语义也完全不变，就必须继续保留 Git provenance，这与彻底移除 Git 是不同目标。

## 6. 迁移与验证顺序

1. **隔离契约验证**：使用专用测试 namespace，验证下表门槛；不在真实用户 Skill 上试写。
2. **实现 adapter 与来源模型**：保留用户路由和操作语义，增加 provider-neutral 数据字段、版本映射与恢复状态机；不复用 Git 操作命名。
3. **历史数据盘点**：统计用户、草稿、Release、vN、revoked/archived、Git refs、Artifact digest。在内容保真门槛通过后，按每个 Release 的文件集合导入 Nacos，保留原 Release ID 和 MinIO Descriptor，不重新计算或替换既有运行制品身份。只迁移需要保留的正式历史和当前草稿，Git 全量 commit 历史保留备份供审计。
4. **核对再切换**：逐项核对文件路径、字节 hash、owner、产品版本与远端映射。迁移时暂停选定 Skill 的编辑与发布；以 Skill 为单位切换来源，明确每个 Skill 的单一写入口，避免永久双写。
5. **回退准备**：历史 Gitee 保持只读备份。切换前可退回旧 provider；Nacos 已产生新写入后，须先反向迁移并校验这些内容，不能简单改开关丢弃新草稿/Release。

| 契约门槛 | 验证内容与通过条件 |
| --- | --- |
| 身份隔离 | 两个业务用户同 key 正常；跨用户 ID、列表、下载、猜测远端名均不能经业务接口越权；明确 PRIVATE 在共享服务账号下的边界 |
| 完整文件保真 | 中文、换行、空文本、nested references、PNG/JPEG/GIF 等逐字节 round-trip；记录 SKILL.md 规范化差异并证明原始内容可还原，禁止静默接受差异；删除资源后不残留；manifest 与 TAR digest 可重复 |
| 容量 | 1 MiB 单文件、10 MiB 总量、200 文件、8 层均能处理；考虑 Base64 与 JSON 开销，超过上限有明确错误 |
| 草稿并发 | 两个 backend 保存/发布同草稿，只允许一个基线推进；校验失效，远端内容不被失败写覆盖 |
| 发布独立性 | 发布 v2 后 Active 仍为 v1、Enabled 不变；校验不产生线上版本；Nacos latest 更新不影响 Runtime |
| 版本可靠性 | 已发布内容不可改；断线重试不重复发版；固定版本下载不静默回退 latest；offline/deleted 的管理读取行为有明确策略 |
| 永久业务限制 | Revoked 不能再激活；归档不删除历史；已发布 Skill 无业务物理删除入口 |
| 故障恢复 | Nacos 成功/PG失败、MinIO成功/PG失败、响应丢失等都可对账；恢复不自动 Active/Enabled，不删除用户草稿 |
| 运行不变 | 先快照再发布/回滚，本次仍使用原内容；Nacos断开时已登记制品运行路径保持不变；cache miss 与坏 digest 明确失败 |
| 运维 | Skill 权限最小化、托管内容外部写入限制、Nacos/PG/MinIO 联合备份恢复；standalone 是否满足目标可用性另行确定 |

## 7. 决策建议与本次边界

建议接受 **“Nacos 源码/版本 + PG 业务状态 + MinIO Runtime”** 作为适配方向，先完成 3.2.4 真实契约验证，再实施迁移。它能保持用户管理功能，并减少现有基于 Gitee PR/tag 的发布耦合；代价是仍需维护 Nacos/PG/MinIO 的跨系统一致性，不会因此变成单系统事务。

如果目标是“删除 Gitee、PG 和 MinIO，仅用 Nacos 支撑整个 Skill 业务”，当前评估不支持：Nacos 生命周期不能直接表达永久撤销、归档保留、独立 Active，以及既有 SHA-256 运行制品契约。

本次交付为源码与官方资料评估、服务端版本只读核验和迁移建议；没有执行 Skill 创建/发布/删除，没有变更账号权限，没有进行真实容量、并发或故障注入测试。现有测试可以作为后续回归基础，但 mock Gitee 的单元测试不能证明 Nacos 契约成立。
