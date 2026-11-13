# Skill 源码与版本接入 Nacos

## 职责与用户行为

新 Skill 的创建、草稿保存与正式源码版本由 Nacos 3.2.4 管理；PostgreSQL 保存用户所有权、草稿 revision、校验证据、产品 vN、Active/Enabled/Revoked/Archived、发布操作及运行 Binding；MinIO 保存原有不可变 TAR 制品。服务注册模块仍只负责 Naming。

`/api/me/skills` 的路由和用户动作保持：编辑 → 校验 → 发布 → 查看 Release → 单独设为生效 → 单独启用。发布不会修改 Active/Enabled。撤销仍永久阻止重新绑定；归档不删除源码历史或运行制品。

Nacos 版本号是来源身份，业务 vN 独立递增，不读取 latest 选择运行内容。Nacos SDK 的订阅/热更新不进入 Runtime。Nacos 故障或凭据缺失不影响已登记 Release 的运行路径；MinIO 的精确 digest cache hit/miss 规则保持不变。

## 内容保真与控制台操作

Nacos 会规范化 SKILL.md frontmatter。为保留原始文件并兼容服务端安全扫描，adapter 使用可扫描的原生资源与文本清单：

- references/assets 等文件保留原始目录；合法 UTF-8 文本以明文保存，图片使用 Nacos 的原生 Base64 资源编码；
- 原始 SKILL.md 明文保存在 `h-agent-source/original-skill.md`，不经过正文 frontmatter 规范化；根 SKILL.md 是内部名字的可读镜像；
- `h-agent-source/snapshot.json` 保存 schema、source revision、parent revision、逐文件路径、大小、SHA-256 与编码；读取时核对所有文件，再重建原始确定性 TAR 并校验整体 digest；
- 源文件身份和 MinIO TAR digest 不随表示格式改变。空文件使用明确文本标记表示并还原零字节，避免配置存储将空内容视为缺失；现有 Validator 仍阻止空文件发布；
- `h-agent-source/**` 是保留路径，用户文件不能占用；读取时拒绝缺失、篡改或清单外的资源；
- 配置存储写入后的短暂旧缓存只允许在 3 秒内重新校验，必须读到预期的完整目标 digest 才确认成功，不能把旧版本当成功；
- Nacos ZIP 的主 SKILL.md 含规范化元信息，同时包含原文和清单，因此不是逐字节等同原始目录；现有应用文件树、编辑与比较从清单还原原文件。本次未新增产品包导入导出功能。

初版二进制 TAR 分块表示在目标环境被 skill-scanner 以 `LOW_ANALYZABILITY` 拒绝，已替换为以上原生资源格式；没有关闭或绕过扫描器。

运维可以查看 Nacos 版本；请通过应用修改托管 Skill。手工修改镜像正文/原始资源会导致 `SOURCE_DRIFTED`，不能把控制台当作第二个编辑入口。禁止删除托管 Skill 或把它切成 PUBLIC；namespace 和 service account 的权限仍应在部署时验证。

远端名为 `h-agent-skill-<skillId>`，不会因两个用户拥有相同 skillKey 而冲突。业务用户不对应 Nacos owner；所有 `/me` 操作仍先通过应用 owner 校验。浏览器和模型不获得 Nacos 凭据。

## 配置

`skill-platform.nacos` 映射以下变量；未单独配置时回退到已有 `NACOS_*` 连接信息：

| 变量 | 用途 |
| --- | --- |
| `NACOS_SKILLS_SERVER_ADDR` | 服务地址，回退 `NACOS_SERVER_ADDR` |
| `NACOS_SKILLS_NAMESPACE` | Skill namespace ID，回退 `NACOS_NAMESPACE`；推荐独立命名空间 |
| `NACOS_SKILLS_USERNAME` / `NACOS_SKILLS_PASSWORD` | Skill 管理账号，回退 `NACOS_USERNAME` / `NACOS_PASSWORD` |

应用在第一次需要源码时才初始化管理 SDK，不在进程启动时连接 Nacos。源码操作要求地址、namespace、用户名和密码非空，账号必须有目标 namespace 的 AI Skill 管理权限；普通 Naming 权限不代表具备这些权限。缺失返回 `CREDENTIAL_UNAVAILABLE`，不回退到 Gitee 写入。

管理 SDK 的 HTTP 默认连接超时 2 秒、读取超时 5 秒、重试 3 次；这是 SDK 的 JVM 全局设置。目标环境 10 MiB 原生资源测试使用 15 秒读取超时；部署时可通过 JVM 参数 `-DMAINTAINER.CLIENT.CONNECT.TIMEOUT=3000 -DMAINTAINER.CLIENT.READ.TIMEOUT=15000` 配置，不提供无效的 per-client timeout 属性。

`skill-platform.repository` 仅供旧 Proposal 的只读 Gitee 导入。新写入固定使用 Nacos；`provider=gitee` 不是回滚开关。`GITEE_TOKEN` 只在导入旧草稿固定 commit 时需要，迁移完成后可移除。

## 并发、发布与恢复

同一 Skill 的源码读取/保存/校验/发布/放弃及迁移持有 PostgreSQL 定义行锁；多个 backend 实例共用此协调机制。事务包含远端调用，不可把锁改为单机 synchronized。大规模迁移要安排维护窗口，避免长时间阻塞该 Skill 的管理动作。

- 保存前检查 expectedHead，写后核对原始文件 revision，再推进 PG 并使旧校验失效。
- 若 Nacos 保存成功但 PG 回滚，Nacos envelope 记录 parent revision。下一次刷新草稿仅允许其直接子 revision 推进 PG；不同父版本视为漂移，不覆盖。客户端收到冲突后刷新草稿。
- 发布始终固定 `source_version + source_revision`。Nacos 发布成功而 MinIO/PG 失败时，原 PG Proposal 仍保留；重试同一发布请求复用该远端版本，不重新建版、不改变 Active/Enabled。
- PG 成功登记 Release、完成 operation 并删除 Proposal 是同一事务。发布完成后重复相同 `Idempotency-Key + head + validatedHead + releaseNote` 返回同一 Release；换 payload 拒绝。
- 放弃草稿也校验远端 revision；只删除准确的 draft/reviewed 工作版本，不删除审核执行中的版本或已发布版本。远端删除成功而 PG 回滚时可重试。
- 恢复是请求驱动：刷新草稿或重试发布完成对账。本次没有宣称后台定时 reconciler 已实现。Nacos 3.2.4 内部对多个源文件并发写入，并非跨文件事务；若远端存储只写成部分内容，快照校验会拒绝继续，需从 Nacos 备份/配置历史或旧固定 Git commit 恢复。上述自动恢复覆盖整次远端保存成功而 PG 回滚，不承诺恢复任意远端存储损坏；本次未进行 Nacos 内部存储的部分写入故障注入。

先执行应用原有 Validator。Nacos Pipeline 是额外流程：draft 调用 submit，reviewed 调用普通 publish，online 版本核对后幂等成功。reviewing 或被拒绝的状态返回明确冲突（同时检查 Pipeline 结果；即使版本显示 reviewed，REJECTED 也不能发布），自动审核通过后重试原发布请求；审核结束的 reviewed 版本可在保存修改时自动 redraft，历史拒绝结果不阻断新一轮审核；adapter 不使用 force-publish。无 Pipeline 时 submit 会自动 online 并更新 latest，但这不驱动应用的 Active 指针。3.2.4 的 Pipeline 启动失败存在直接发布路径，因此不能用它替代应用校验器或视为 fail-closed 安全门禁。

## 数据迁移与部署

1. 备份 PostgreSQL、Gitee 仓库与 Skill MinIO bucket。先在目标环境运行隔离契约验证，并配置 Nacos Skill 账号权限。
2. 部署代码时由 Flyway 执行 `V20261113_01__nacos_skill_sources.sql`：保留旧列与数据，新增来源坐标，扩展草稿 revision 长度，允许新 Release 没有 Git commit/tag。
3. 新 Skill 直接使用 Nacos。旧 Skill 首次创建/读取可编辑 Proposal 时，在该 Skill 锁内迁移其正式历史，然后读取旧草稿的**固定 Git commit** 导入 Nacos；不读取可变 branch head、不写 Git。迁移后旧校验失效，需要重新校验。
4. 完整迁移（包括归档、停用和已撤销版本）可显式启用 `--skill-platform.migration.enabled=true`。Runner 在应用 ready 前逐个 Skill 迁移；每个 Skill 独立提交，失败中止启动，修复权限/内容问题后可重试。不默认执行全量迁移。
5. 正式历史从已验证 MinIO 制品恢复原始文件集，按 vN 顺序导入 Nacos。以固定迁移坐标 `vN` 复用已有内容一致的发布版（精确查询，不受 Nacos 元信息仅列出最近 200 版影响），并把现有 Release 关联到准确 Nacos 版本；不重新生成产品 Release ID、vN、Artifact Descriptor，不改变 Active/Enabled/Revoked/Archived。原 Git commit/tag 继续保留作追溯。
6. 当前 builder 必须能重建相同 digest，否则该历史版本停止迁移，不静默接受新 hash。旧草稿含凭据/非法路径或超过现有配额时也会阻断，需处理后重试。旧 `PUBLISHING` 流程必须先恢复到明确状态，否则迁移拒绝继续，不丢弃未完成发布。
7. 核对 `source_provider`、Nacos PRIVATE、文件 hash、Release 数量、Active/Enabled 与已归档/撤销状态，再退出维护窗口。完整迁移后关闭一次性迁移开关。

示例盘点 SQL（在应用连接使用的 schema 中执行）：

```sql
SELECT source_provider, count(*) FROM skill_proposals GROUP BY source_provider;
SELECT source_provider, count(*) FROM skill_releases GROUP BY source_provider;
SELECT skill_id, version_number, source_version, source_revision, artifact_digest
FROM skill_releases WHERE source_provider = 'nacos' ORDER BY skill_id, version_number;
```

历史 Git 正式版本即使尚未迁移，其详情/比较也可读取已有 MinIO 制品，不要求 Gitee 在线。迁移保留 Gitee 历史为只读；不删除旧分支、tag 或仓库。

新 Nacos 写入出现后，不能仅回滚二进制或切 provider：旧应用依赖 Git commit/tag。需要先把新增源码/版本反向导入 Git并恢复其业务坐标，或恢复联合备份；不得丢弃切换后新草稿。Nacos、PG、MinIO 应联合备份恢复，单独恢复 Nacos latest 不恢复产品状态。

## 验证方式与本次结果

使用项目 JDK 26（部分机器 Maven 默认使用另一套 JDK）：

```bash
mvn -pl backend -am test '-Dtest=Skill*Test,NacosSkillSourceRepositoryTest,GiteeApiContractTest' -Dsurefire.failIfNoSpecifiedTests=false
```

真实 Nacos 测试显式启用 `NACOS_SKILLS_CONTRACT=true`，使用环境中的 `NACOS_SERVER_ADDR/NACOS_USERNAME/NACOS_PASSWORD`；测试创建随机专用 namespace，验证 10 MiB 总量、图片/空文件/中文、固定版本、重试、fork/discard，并清理自己创建的数据。需要临时 namespace 管理权限；开启 Pipeline 时最多等待每次自动审核 60 秒，不强制发布。不要在业务 namespace 上直接试写。测试还会清理其命名空间中不再被版本清单引用的测试配置。

真实 PostgreSQL 测试显式启用 `SKILL_PG_CONTRACT=true`，使用 `DB_URL/DB_USERNAME/DB_PASSWORD`；只创建和删除随机临时 schema，验证旧表到新表迁移、真实 mapper SQL/JSONB、并发保存、远端成功/PG回滚恢复、发布幂等与无自动生效。

```bash
mvn -pl backend -am test -Dtest=NacosSkillSourceContractTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl backend -am test -Dtest=SkillNacosPersistenceContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

已根据用户提供的注册配置，将 Nacos 共用连接变量写入本工作树被 Git 忽略的 `.env`（权限 0600），Skill 配置自动读取同一组 `NACOS_*`，没有在源码中添加密码默认值。目标 Nacos 3.2.4 的鉴权、临时 namespace、10 MiB 原生文件往返、实际 skill-scanner 自动审核、发布重试、历史 vN 导入、fork/discard 和旧版本读取已验证通过；未改变目标服务器的审核配置、账号权限或业务 namespace。

本轮专项共 81 项测试通过（含目标 Nacos 和真实 PostgreSQL 契约）。PostgreSQL 隔离 schema 验证覆盖真实 mapper/JSONB、并发、发布幂等、远端成功/PG回滚恢复，以及原 Release 原位迁移并保留 Artifact Descriptor 和 Git 追溯。前端修改文件已通过 TypeScript 与 ESLint 检查。正式业务数据迁移、生产最小权限账号和联合备份恢复仍按部署步骤执行。


全量后端测试运行 1187 项，存在 4 个失败和 1 个环境错误：SuccessfulTurnCommitter、AgentMemoryPolicyCatalog、SubagentDefinitionCatalog 三项单元失败及 SystemPromptServicePersistence 缺少 DB_URL 的错误，已在原始提交复现；KnowledgeDocumentMapperPersistence 受到共享数据库存量影响。没有修改这些无关功能。前端改动仅来源信息展示，Nacos 来源显示版本，旧 Git 来源保留 Commit；保留兼容字段 headCommitSha 作为不透明 revision token。
