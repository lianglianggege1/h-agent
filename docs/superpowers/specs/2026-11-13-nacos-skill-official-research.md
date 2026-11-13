# Nacos Skill 官方能力核验（2026-11-13）

## 范围与结论

本记录仅核对 Nacos 官方文档、官方 GitHub release 与 **3.2.4 tag 源码**。没有向目标集群写入、发布或删除 Skill，也没有进行二进制往返、并发、故障恢复实测。业务匹配与迁移决策见同目录业务评估文档。

**结论：3.2.4 已有完整 Skill 管理生命周期，可作为内容与版本管理后端；它不能直接替代本项目的用户所有权、Proposal head 并发、发布与激活分离、SHA-256 固定制品和运行绑定。** 这是结合下列已核实事实作出的架构判断。

## 版本与成熟度

- 官方文档把 Skill 起始版本标为 3.2.0，但当前版本文档持续更新；本次具体行为以 3.2.4 源码为准。[Skill 管理](https://nacos.io/docs/v3.2/manual/user/ai/skill-registry/)
- 3.2.4 是 2026-08-27 的正式维护版本，release 定位为修复、安全加固与体验改善。[3.2.4 release](https://github.com/alibaba/nacos/releases/tag/3.2.4)
- 截至核验日官方最新正式版为 2026-10-29 发布的 3.3.0，包含共享 AI 搜索、可见性扩展及 Skill 元数据一致性修复。因此不能把 develop/3.3 的全部搜索及可见性能力当作 3.2.4 已有保证，也不能仅凭正式版断言满足本项目生产验收。[3.3.0 release](https://github.com/alibaba/nacos/releases/tag/3.3.0)

## 3.2.4 已实现的接口

以下路径中的管理前缀为 `/nacos/v3/admin/ai/skills`；Console 对应 `/v3/console/ai/skills`。这是源码接口清单，不代表业务前端应直接调用管理接口。[Admin Controller](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/controller/SkillAdminController.java)、[Console Controller](https://github.com/alibaba/nacos/blob/3.2.4/console/src/main/java/com/alibaba/nacos/console/controller/v3/ai/ConsoleSkillController.java)

| 能力 | 实际接口/SDK | 核实说明 |
| --- | --- | --- |
| 元信息与版本内容 | GET 根路径、GET `/version` | 元信息与完整版本内容分开 |
| 列表 | GET `/list` | 名称模糊/准确过滤、分页；实现支持 owner/scope/bizTag/orderBy |
| ZIP 导入 | POST `/upload`、`/upload/batch` | multipart；单包和多 Skill ZIP；上传生成草稿 |
| 草稿 | POST/PUT/DELETE `/draft` | 新建、从已有版 fork、全量内容更新、删除当前草稿 |
| 审核/发布 | POST `/submit`、`/publish`、`/force-publish`、`/redraft` | force-publish 为特殊管理能力，应限制调用 |
| 状态与标签 | POST `/online`、`/offline`；PUT `/labels`、`/scope`、`/biz-tags` | 技能级 enable 与版本上下线；路由标签独立更新 |
| 下载 | GET `/version/download`；Client GET `/nacos/v3/client/ai/skills` | ZIP；Client 支持 version/label 与 MD5 条件请求 |
| 删除 | DELETE 根路径 | 删除整个 Skill 及其版本/存储，不等价于业务软归档 |

Java 分工也已在 3.2.4 提供：`nacos-client` 的 `AiService` 支持默认/指定版本/label 下载及 Skill 订阅/退订；`nacos-maintainer-client` 的 `SkillMaintainerService` 提供草稿、上传、发布、列表、状态与标签管理。项目只有 nacos-client 依赖不代表已有管理 SDK 适配。[AiService](https://github.com/alibaba/nacos/blob/3.2.4/api/src/main/java/com/alibaba/nacos/api/ai/AiService.java)、[SkillMaintainerService](https://github.com/alibaba/nacos/blob/3.2.4/maintainer-client/src/main/java/com/alibaba/nacos/maintainer/client/ai/SkillMaintainerService.java)

3.2.4 Client 还提供名称关键字搜索（仅返回启用且至少有在线版的资源）；这不等价于 3.3 的向量/混合检索。[SkillOperationServiceImpl.searchSkills](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/skills/SkillOperationServiceImpl.java)

## 生命周期与现有业务的重要差异

1. 实现的状态包含 draft、reviewing、reviewed、online、offline，维护 editingVersion/reviewingVersion 指针；单 Skill 只允许一个工作版本。在线内容不能通过 updateDraft 直接覆盖。上传 `overwrite=true` 只替换当前 editing draft；没有 editing 时创建新草稿，而非覆盖线上版本。[SkillOperationServiceImpl](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/skills/SkillOperationServiceImpl.java)
2. **submit 无 Pipeline 时调用 `publish(..., true)`**，即直接上线并更新 latest；Pipeline 启动失败的实现路径也回退直接发布。审核通过后的自动发布另有开关。`publish`/`forcePublish` 可传 `updateLatestLabel=false`。所以不能直接将业务“发布仅产生不可变 release、激活另操作”映射为 submit。[同一实现](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/skills/SkillOperationServiceImpl.java)
3. 支持 SemVer 与 `vN`，createDraft 可指定 targetVersion 并拒绝重复；上传的候选版本冲突时会计算新版本，不应假设请求版本必然就是返回版本。[同一实现](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/skills/SkillOperationServiceImpl.java)
4. updateDraft 请求及服务参数没有 expectedHead/If-Match 一类调用方乐观锁；服务中虽有元数据 CAS，不能推导出“拒绝旧 Proposal head 覆盖”的外部契约。需要应用保留锁/head 校验和写入幂等日志。[SkillUpdateForm](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/form/skills/admin/SkillUpdateForm.java)、[实现](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/skills/SkillOperationServiceImpl.java)
5. Skill 全量删除会删除版本和存储；发布内容不可编辑不等于防删除的永久归档。业务 revoke/archive、运行快照与保留周期需要继续由本系统控制。[实现 deleteSkill](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/skills/SkillOperationServiceImpl.java)

## 文件、二进制和容量

**String 字段不等于不支持二进制。** 3.2.4 ZIP parser 读取原始字节，`ResourceContentEncoder` 对文本白名单外的文件做 Base64，并附 `metadata.encoding=base64`。存储保存该字符串，重载根据文件名重新识别编码，ZIP 下载使用 `SkillUtils.resolveResourceBytes` 解码。可支持 png/ttf/pdf 等资源；文本白名单文件按 UTF-8 解码，非 UTF-8 文本不能默认保证字节无损。[SkillZipParser](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/utils/SkillZipParser.java)、[ResourceContentEncoder](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/utils/ResourceContentEncoder.java)、[SkillUtils](https://github.com/alibaba/nacos/blob/3.2.4/api/src/main/java/com/alibaba/nacos/api/ai/model/skills/SkillUtils.java)、[存储重载实现](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/skills/SkillOperationServiceImpl.java)

| ZIP 限制 | 默认 | 配置 |
| --- | --- | --- |
| 压缩上传大小 | 10 MiB | `nacos.ai.skill.zip.max-upload-size-mb` |
| 解压总大小 | 50 MiB | `nacos.ai.skill.zip.max-uncompressed-size-mb` |
| ZIP entry 数 | 500 | `nacos.ai.skill.zip.max-entries` |

默认上传常量见 [Constants.Skills](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/constant/Constants.java)，其余及配置覆盖见 [SkillZipParser](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/utils/SkillZipParser.java)。这些是 ZIP 路径限制，不能用 Nacos Config 的 maxContent 推定 Skill 总容量；仍需核对部署的 multipart、网关和存储 provider 限制。本项目的 1MiB 单文件/10MiB 总量/200 文件/8 层策略应继续执行，200 文件并不保证带所有显式目录 entry 后低于 500。

**原样内容需特别验收**：Nacos `normalizeSkillFrontmatter` 会规范 SKILL.md 的 name/description/version，version 总是同步为服务端确定的版本，ZIP parser 也会处理 BOM、忽略 macOS 元数据。故源文件与 Nacos 导出文件不能预设逐字节相同，现有 contentHash/确定性 tar 必须在明确的规范化边界上重新计算，已有 release 不应重打包覆盖。[SkillRequestUtil.normalizeSkillFrontmatter](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/utils/SkillRequestUtil.java)、[parser](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/utils/SkillZipParser.java)

## Namespace、owner 与授权

- 管理 Controller 使用 READ/WRITE、AI signType 的鉴权注解；业务服务还调用 VisibilityHelper 检查可读/可写范围，列表也通过可见性条件过滤。因此不能只凭“有登录”断言所有租户已经隔离。[SkillAdminController](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/controller/SkillAdminController.java)、[VisibilityHelper](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/VisibilityHelper.java)
- Skill 业务定位键使用 namespace/name/type；owner 从 **Nacos 当前调用身份**解析，默认 scope 通过可见性插件决定（未取得插件结果时 fallback PRIVATE）。统一后台账号创建多个业务用户的 Skill，不能把该 owner 当作业务 userId。建议本系统保留所有权校验，使用稳定 skillId 定位远端对象，用户显示名称仍由本系统处理。[createDraftWithSkill/requireMeta](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/skills/SkillOperationServiceImpl.java)、[AiResourceManager](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/resource/AiResourceManager.java)
- 可见性策略是可插拔的；VisibilityHelper 在未加载服务时有放行路径，因此生产必须验收实际插件、账号/RBAC、namespace 和调用身份传递。不能把模型中存在 PRIVATE 字段视作充分授权保证。[VisibilityHelper](https://github.com/alibaba/nacos/blob/3.2.4/ai/src/main/java/com/alibaba/nacos/ai/service/VisibilityHelper.java)

## 必须通过的 PoC（尚未验证）

1. UTF-8 Markdown、多层目录、空文件、二进制、无扩展名资源往返，逐文件比对 SHA-256；记录 SKILL.md 规范化差异。
2. 用户 A/B 同名 Skill、越权枚举/读取/修改、统一后台账号和服务账号令牌场景。
3. 并发 updateDraft、重复 create/publish、超时后重试、远端成功但 PG 提交失败的恢复。
4. 发布后不改变业务 active release，激活/停用/revoke/archive 仍保持现有行为；明确是否允许管理员 Nacos 控制台旁路修改。
5. MinIO 制品发布失败时不使业务 release 可绑定；既有运行仅用固定 release+SHA-256，不受 Nacos latest、offline、删除或不可用影响。
6. 实际部署容量、鉴权插件、监听行为、数据备份恢复、生产集群可用性；静态源码检查不代替这些验收。
