# MinIO 资源对象存储运行手册

> 对应计划：`docs/superpowers/plans/2026-08-24-minio-object-storage-implementation.md`
> 部署范围：**开发环境**。生产启用受 §7 前置条件约束，本期完成不等于生产启用。
> 最后更新：2026-08-26（新计划任务 5）

## 1. 概述

- 本期把聊天图片、视频、音频、附件及 Agent 交付文件的字节存储从节点本地目录切换到私有 MinIO，只覆盖**开发环境**。
- 架构一句话：`ResourceStorage` 是资源字节能力的外部 seam，`MinioResourceStorage` 是唯一生产 Adapter；业务写入统一经 `ResourceWriteCoordinator` 协调"对象先写、PostgreSQL 后挂接"，事务回滚时 best-effort `discard` 补偿删除。
- PostgreSQL 继续保存资源身份、owner、MIME、文件名与对象 key；MinIO 只保存文件字节与少量技术 metadata。
- 资源 URL 保持 `/api/chat/resources/{id}/content|download` 不变，owner 鉴权不依赖 object key。
- 生产运行态**没有**本地回退、双写或双读（本地文件存储实现已于任务 5 删除）。
- `chat.filesystem`（`AssistantFileStorage`，`/tmp/h-agent/assistant-files`）是 Agent 工作文件目录，**不属于资源存储**，本切换不涉及。

## 2. 配置说明

### 2.1 `resource-storage.*` 字段表

| 字段 | 必填 | 默认值 | 格式要求 / 说明 |
| --- | --- | --- | --- |
| `resource-storage.absolute-max-bytes` | 否 | `524288000`（500 MiB） | 正数。存储层绝对上限；生效上限恒为 `min(本属性, 调用方业务上限)`，配置更大**不会放大**代码侧 500 MiB 硬上限 |
| `resource-storage.minio.endpoint` | **是** | 无 | 合法 `http://` 或 `https://` URL（S3 API 地址，不是 Console 地址） |
| `resource-storage.minio.access-key` | **是** | 无 | 非空文本。应用专用账号 access key |
| `resource-storage.minio.secret-key` | **是** | 无 | 非空文本。只经环境变量 / Secret 注入 |
| `resource-storage.minio.bucket` | **是** | 无 | 非空文本。私有 Bucket 名（开发环境 `huajiang`） |
| `resource-storage.minio.object-prefix` | 否 | `resources/` | 不以 `/` 开头；尾部斜杠自动规范（`resources` 与 `resources//` 都得到 `resources/`） |
| `resource-storage.minio.region` | 否 | `us-east-1` | MinIO Region |
| `resource-storage.minio.connect-timeout` | 否 | `3s` | 正时长（如 `3s`、`500ms`） |
| `resource-storage.minio.read-timeout` | 否 | `60s` | 正时长。大文件 Range 读取按此超时 |
| `resource-storage.minio.part-size-bytes` | 否 | `10485760`（10 MiB） | [5 MiB, 5 GiB]（MinIO SDK 硬约束）；对象大小未知时按此走 multipart |

不提供 `minio.enabled`、`write-provider`、`public-endpoint`、presign、migration 或 orphan cleanup 配置——MinIO Adapter 是唯一实现，没有开关。

### 2.2 环境变量注入方式

`application.yml` 通过占位符把下列环境变量桥接到 `resource-storage.minio.*`：

| 环境变量 | 映射到 | 示例（开发环境，非敏感） |
| --- | --- | --- |
| `MINIO_ENDPOINT` | `minio.endpoint` | `http://169.254.140.78:9000`（S3 API；Console `:9001` 不是应用 Endpoint） |
| `MINIO_ACCESS_KEY` | `minio.access-key` | （专用账号，见 §4） |
| `MINIO_SECRET_KEY` | `minio.secret-key` | （专用账号，见 §4） |
| `MINIO_RESOURCES_BUCKET` | `minio.bucket` | `huajiang` |
| `MINIO_RESOURCES_PREFIX` | `minio.object-prefix` | `resources/` |
| `MINIO_REGION` | `minio.region` | `us-east-1` |

启动示例：

```bash
export MINIO_ENDPOINT=http://169.254.140.78:9000
export MINIO_RESOURCES_BUCKET=huajiang
export MINIO_RESOURCES_PREFIX=resources/
export MINIO_REGION=us-east-1
export MINIO_ACCESS_KEY=<专用账号 access key>
export MINIO_SECRET_KEY=<专用账号 secret key>
java -jar backend-*.jar
```

凭证只经环境变量或 Secret 管理系统注入；**不得**写进仓库、镜像、启动脚本、文档或日志。

### 2.3 启动纯配置校验语义

- 启动只做**配置字段与格式校验**：不联网、不 `bucketExists`、不写探针对象、不验证凭证有效性。
- 必填缺失或格式非法（URL 非 http/https、前缀以 `/` 开头、超时非正数、part-size 越界等）→ 启动立即失败（`IllegalStateException` fail fast）。
- 错误信息只含**属性名与格式要求，不含任何属性值**（尤其 secret）。
- 后果：Endpoint 不可达、凭证错误或 Bucket 不存在**不会在启动期暴露**，只会在首次资源操作时按 §5 错误语义返回。
- 意图：MinIO 故障不阻止应用启动（纯文本聊天等非资源功能不受影响），资源操作失败按现有错误链路处理，不改变 health/readiness。

## 3. 开发数据清理 SQL（人工外部操作）

> **⚠️ 环境限定的人工操作，绝不自动化。**
>
> - **禁止**写成 Flyway migration 或任何随应用执行的脚本——否则其他环境应用迁移时可能误删仍有价值的历史资源。
> - **禁止**在未完成 §3.1 环境确认前执行任何写操作。
> - 盘点数量与预期不符时**立即停止**，按 §3.6 处理。
> - 只删资源行，**不删父聊天消息**（`chat_session_messages`）。

背景（2026-08-26 只读核验）：开发库 `chat_message_resources` 有 1 条 `LOCAL_FILE` 记录（约 3,996 字节的行数据），对应物理文件在主机默认目录、`/tmp` 与工作区均不存在；`generation_tasks` 为 0 条。本期不迁移文件，只清理数据库引用，使两表 `LOCAL_FILE` 清零后再部署 MinIO-only 构建。

### 3.1 目标环境确认

1. 核对当前连接串确为**开发库**（本仓库开发配置指向本机 `127.0.0.1:5432/h_agent_db`；以你实际 `spring.datasource.url` 为准），逐项确认 host、port、库名，不是生产数据库。
2. 确认操作账号对该库有 `SELECT` / `DELETE` / `CREATE` 权限。
3. 全程只读盘点未通过前，不执行任何 `DELETE`。

### 3.2 备份待删除行

```sql
-- 备份表带日期后缀，保留完整行结构便于恢复
CREATE TABLE chat_message_resources_local_file_backup_20260827 AS
SELECT * FROM chat_message_resources
WHERE storage_type = 'LOCAL_FILE';

-- 核对备份行数与 §3.3 盘点数一致
SELECT count(*) FROM chat_message_resources_local_file_backup_20260827;
```

### 3.3 盘点（只读，预期不符即停止）

```sql
-- 预期 = 1（2026-08-26 核验基线）
SELECT count(*) AS chat_message_resources_local_file
FROM chat_message_resources
WHERE storage_type = 'LOCAL_FILE';

-- 预期 = 0；若非 0 立即停止清理并重新评估（计划 §7 第 4 条）
SELECT count(*) AS generation_tasks_local_file
FROM generation_tasks
WHERE artifact_storage_type = 'LOCAL_FILE';
```

必要时抽查待删行的明细（确认确为历史开发数据）：

```sql
SELECT id, message_id, user_id, resource_type, resource_role,
       storage_type, storage_key, mime_type, file_name, file_size, created_at
FROM chat_message_resources
WHERE storage_type = 'LOCAL_FILE';
```

### 3.4 清理（只删资源行，不删父消息）

```sql
BEGIN;

DELETE FROM chat_message_resources
WHERE storage_type = 'LOCAL_FILE';

COMMIT;
```

### 3.5 验收断言（两表 LOCAL_FILE 均为 0）

```sql
SELECT
  (SELECT count(*) FROM chat_message_resources WHERE storage_type = 'LOCAL_FILE')        AS chat_message_resources_local_file,
  (SELECT count(*) FROM generation_tasks WHERE artifact_storage_type = 'LOCAL_FILE')     AS generation_tasks_local_file;
-- 两个值都必须为 0；验收通过后才可部署 MinIO-only 构建（计划 §12 Phase 3）
```

### 3.6 数量不符时的处置

- `chat_message_resources` 盘点数 ≠ 1：数据已相对核验基线发生变化。停止操作，重新核对是否为目标环境、是否有其他写入来源，向负责人确认后再继续。
- `generation_tasks` 盘点数 ≠ 0：可能存在仍有价值的历史生成产物。**立即停止**，逐行检查 `artifact_storage_key` 对应物理文件是否存在，重新评估是否迁移，不得直接删除。
- 清理后部署前如需恢复：`INSERT INTO chat_message_resources SELECT * FROM chat_message_resources_local_file_backup_20260827;`（仅限重新开放流量前的回退，见 §5.3）。

## 4. 凭证与权限

- **应用专用最小权限账号**：只允许访问 `huajiang/resources/*`，权限仅限：
  - `GetObject`（读取）
  - `PutObject`（写入；multipart 所需权限由 contract test 验证，缺什么补什么，不预授宽权限）
  - `DeleteObject`（仅由 Coordinator 事务补偿调用）
- 应用账号**不获得** CreateBucket、Policy 修改、用户管理或常规 Bucket 列表权限。
- 凭证只经环境变量 / Secret 管理系统注入（§2.2），不使用管理员账号作为长期应用凭证。
- **共享过的管理员凭证在生产前必须轮换**；任何凭证不写入仓库、镜像、文档或日志。
- Bucket 始终私有：匿名访问必须失败；owner 鉴权由 PostgreSQL metadata + 当前用户完成，不依赖 object key 难以猜测。

## 5. 运维要点

### 5.1 错误语义表

| HTTP | 错误种类 | 含义 | 排查方向 |
| --- | --- | --- | --- |
| 404 | `NOT_FOUND` | 对象不存在，或 owner 查询无结果 | 先查 `chat_message_resources` 行是否存在 / 是否当前用户所有；再查对象是否被误删（MinIO Console 查 `resources/` 前缀） |
| 413 | `SIZE_LIMIT` | 实际读取超过业务上限或存储绝对上限（500 MiB） | 核对调用方 `maxBytes` 与 `resource-storage.absolute-max-bytes`；上传走 `spring.servlet.multipart.max-file-size` |
| 503 | `UNAVAILABLE` | MinIO 连接失败、超时、5xx、凭证或权限异常 | 核对 `MINIO_ENDPOINT` 可达性（注意 S3 API `:9000` 与 Console `:9001`）、凭证有效期、账号 Policy 是否覆盖 `resources/*`；响应正文不暴露细节，需结合服务端日志（日志只含 resourceId 级别定位信息） |
| 500 | `IO_ERROR` | 其他流或协议错误 | 查看服务端异常链 cause；常见为流中断、declared size 与实际不符 |
| 400 | Range 语法错误 | malformed 或 multiple ranges | 客户端 `Range` header 语法；仅支持单个 `bytes=start-end` / `bytes=start-` / `bytes=-suffix` |
| 416 | Range 不可满足 | 合法但越界 | 合法但超出对象大小的 Range；响应带 `Content-Range: bytes */total` |

响应正文不暴露 Bucket、Endpoint、object key、MinIO SDK 异常或凭证。

### 5.2 MinIO 故障时的业务影响面

- **受影响**：图片/视频/音频/附件上传与生成、Agent `send_file_to_chat`、Voice TTS、通话音频、资源预览与下载（按上表 404/413/503/500 语义失败）。
- **不受影响**：纯文本聊天、会话管理、鉴权等非资源业务沿现有逻辑运行；应用不因 MinIO 故障改变 health/readiness（本期无 MinIO health indicator）。
- 写入失败明确失败，**不自动写回本地磁盘**（无本地回退实现）。

### 5.3 失败恢复与 roll-forward 原则

- **开放流量前**（开发数据清理或 smoke test 失败）：可恢复 §3.2 备份行并回退旧构建。
- **开放流量后**：只允许 **roll-forward**——修复配置、MinIO、权限或代码并恢复资源能力。**禁止**恢复切换前的 PostgreSQL 快照（会丢失切换后的聊天、运行与资源 metadata）。

## 6. 真实 contract test 指引（简要，任务 6 完善）

- 用**应用专用账号**（非管理员）运行 `MinioResourceStorageContractTest`（任务 6 交付），测试对象使用 `resources/contract-tests/<runId>/` 前缀，结束后删除该前缀对象。
- 覆盖项：20–32 MiB 生成流 multipart 上传、stat、完整读取、Range 读取；匿名访问、跨前缀访问与管理操作被拒绝；数据库 rollback 后补偿删除。
- 本套件是本期最终验收的强制门槛：不因缺少 Endpoint 而把 MinIO 接入标记完成；可不入每次普通 CI。
- 运行时通过 §2.2 环境变量注入真实开发 MinIO 连接信息。

## 7. 生产启用前置条件（计划 §8.3）

本期为开发环境实现，代码具备生产质量，但**以下条件未全部满足前不得生产启用**（当前单节点 HTTP link-local MinIO 明确不可用作生产主存储）：

- [ ] 稳定、可路由的 Endpoint 与 HTTPS 证书校验
- [ ] 独立生产 Bucket（`h-agent-<env>-resources`）、独立应用账号与 Secret 轮换流程
- [ ] 明确的 RPO、RTO、容量水位和数据保留要求（由正式生产运维方案给出并验收，本计划不虚构数值）
- [ ] 备份或复制方案以及经过记录的恢复演练
- [ ] MinIO Server 版本升级、安全维护和容量规划评估（当前开发实例 `2025-09-07T16:13:09Z`，生产前重新评估）
- [ ] 用户删除权、未绑定资源和数据库级联删除后的对象保留策略
