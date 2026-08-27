# MinIO 资源对象存储运行手册

> 对应计划：`docs/superpowers/plans/2026-08-24-minio-object-storage-implementation.md`
> 部署范围：**开发环境**。生产启用受 §8 前置条件约束，本期完成不等于生产启用。
> 最后更新：2026-08-27（审查修复轮：HTTP 错误映射接入、四写入点签名校验、m4a 互认）

## 0. 部署前置环境变量（置顶清单）

部署任何 MinIO-only 构建（含开发环境）前，以下四个环境变量**必须**显式设置，缺失即启动 fail fast（§2.3）：

| 环境变量 | 必填 | 说明 |
| --- | --- | --- |
| `MINIO_ENDPOINT` | **是** | MinIO S3 API 地址（如 `http://169.254.140.78:9000`）；Console `:9001` 不是应用 Endpoint |
| `MINIO_ACCESS_KEY` | **是** | 应用专用账号 access key（§4） |
| `MINIO_SECRET_KEY` | **是** | 应用专用账号 secret key（§4） |
| `MINIO_RESOURCES_BUCKET` | **是** | 私有 Bucket 名（开发环境 `huajiang`） |

**常见误区（`.env` 变量不被应用读取）**：

- 工作树根目录 `.env` 中的 `MINIO_DEFAULT_BUCKET`、`MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD` 是 **docker-compose 启动 MinIO 服务本身**的变量（服务端管理员账号与默认 bucket），**后端应用不读取它们**。
- 后端应用只认 `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_RESOURCES_BUCKET`（及可选 `MINIO_RESOURCES_PREFIX` / `MINIO_REGION`，见 §2.2）。
- `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` 是管理员凭证：仅限 §6.2 所述开发联调豁免（`-Dcontract.account=admin`）临时使用，**不得**作为长期应用凭证（§4）。

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
- 错误信息只含**属性名、对应环境变量名（`MINIO_*`）与格式要求，不含任何属性值**（尤其 secret）。审查修复轮后必填缺失的消息形如：`resource-storage.minio.endpoint 不能为空（对应环境变量 MINIO_ENDPOINT）`。
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
- **开放流量后**：只允许 **roll-forward**——修复配置、MinIO、权限或代码并恢复资源能力。**禁止**恢复切换前的 PostgreSQL 快照（会丢失切换后的聊天、运行和资源 metadata）。

### 5.4 存储可观测性（任务 6：进程内计数与告警）

- `ResourceStorageMetrics`（Spring Bean）对 save/open/discard 成功/失败做进程内 LongAdder 计数（失败按四类错误细分），另有 Coordinator 事务回滚补偿 discard 的成功/失败计数。不引入 micrometer/actuator/health（计划 §1.2 明确不实施）；计数仅供排障与测试断言，不对外暴露端点。
- **补偿删除失败告警**（ERROR 级，需人工关注孤儿对象）：

  ```text
  资源补偿删除失败，需人工关注孤儿对象 operation=discard errorKind=UNAVAILABLE resourceId=<uuid> storageKeySuffix=<uuid.ext>
  ```

  出现该日志说明数据库事务回滚后的 best-effort 补偿删除失败，可能残留未挂接数据库的孤儿对象。处理：按 resourceId 在 MinIO Console 检索 `resources/` 前缀下对应对象人工确认并删除（本期无 orphan 自动清理，计划 §1.2）。
- 日志纪律（计划不变量 17）：该告警只含 resourceId 与 key 尾段（uuid.ext），不含完整 object key、secret、endpoint 或 SDK 异常全文；成功操作不产生日志，避免刷屏。

### 5.5 内容签名校验与 m4a 互认（审查修复轮行为变化）

审查修复轮后，**全部资源写入路径在保存前必须通过文件签名校验**（无服务端自产豁免）：用户上传（`ChatResourceController`）、Agent 模型文件（`FileDeliveryTool`）、图片生成（`ImageGenerationServiceImpl`）、TTS（`VoiceTtsService`）、通话音频（`CallTurnService`，合并后字节是用户输入）与生成视频（`ResourceStorageGeneratedArtifactAdapter`）。用户/文件名/HTTP Client/Agent 模型/provider 元数据声明的 MIME 都只是提示，签名冲突即拒绝：

- 用户/Agent 侧拒绝：上传 400 业务错误、Agent 工具返回 Error 文案；
- 同步生成路径（图片生成/TTS/通话）拒绝：明确业务异常，任务/请求失败可见；
- 后台生成视频拒绝：任务标记失败（存储 IO_ERROR 语义，安全文案不暴露 key）。

**m4a 互认**：Inspector 检测层只认 ISO BMFF（MP4 家族）容器——`ftyp` major brand `"M4A "` 判为 `audio/mp4`，其余合法 brand（`isom`/`iso2`/`mp42`/`mp41` 等）判为 `video/mp4`。因合法 m4a 文件的 brand 通常是 `isom` 系而非 `"M4A "`，保存侧校验中 `audio/mp4` 与 `video/mp4` **同属 MP4 容器家族，互不视为冲突**：声明 `audio/mp4` 的 m4a 文件（brand `isom`）不会被误拒；其他 MIME 与 MP4 容器冲突仍拒绝（如声明 `image/png` 而字节是 MP4）。

另外两个相关宽容度：Range 头 `bytes=` 前缀大小写不敏感且容忍前后空白；MinIO 服务端 4xx 错误归 IO_ERROR（500）而非 UNAVAILABLE（仅 5xx/连接层故障归 503）。

## 6. 真实 contract test 指引（任务 6 交付）

测试类：`backend/src/test/java/com/h/backend/chat/infrastructure/storage/MinioResourceStorageContractTest.java`（普通 `*Test` 命名，不启动 Spring 上下文，直接构造 MinioClient + MinioResourceStorage）。本套件是本期最终验收的强制门槛（计划 §11.4），可不进每次普通 CI：**无凭证时全类 SKIP，普通 `mvn test` 不受影响**。

### 6.1 环境变量清单（系统属性优先，环境变量兜底）

| 用途 | 解析顺序（取第一个非空值） | 必填 |
 | --- | --- | --- |
| endpoint | `-DTEST_MINIO_ENDPOINT` → env `TEST_MINIO_ENDPOINT` → `MINIO_ENDPOINT` | 是 |
| access key | `TEST_MINIO_ACCESS_KEY` → `MINIO_ACCESS_KEY` → `MINIO_ROOT_USER` | 是 |
| secret key | `TEST_MINIO_SECRET_KEY` → `MINIO_SECRET_KEY` → `MINIO_ROOT_PASSWORD` | 是 |
| bucket | `TEST_MINIO_BUCKET` → `MINIO_RESOURCES_BUCKET` → `MINIO_DEFAULT_BUCKET` | 是 |
| region | `TEST_MINIO_REGION` → `MINIO_REGION`（默认 `us-east-1`） | 否 |

任一必填缺失 → 全部用例 `assumeTrue` SKIP（surefire 报 `Skipped: 7`，BUILD SUCCESS）。

**注意（2026-08-27 首次真实运行发现）**：`.env` 的 `MINIO_DEFAULT_BUCKET` 值不符合 S3 bucket 命名规则，MinIO SDK 客户端会直接拒绝所有 bucket 级操作（用例以 IllegalArgument 失败；此时 listBuckets 仍可成功，可据此判别是 bucket 名问题而非凭证/endpoint 问题）。真实运行时需显式覆盖为开发 bucket（`MINIO_RESOURCES_BUCKET=huajiang`，计划 §8.2 已核验的私有 bucket）。

### 6.2 运行命令

```bash
# 无凭证（普通 CI / 本地无环境）：全 SKIP，BUILD SUCCESS
cd backend && mvn -Dtest=MinioResourceStorageContractTest test

# 真实运行（生产验收语义，需前缀受限专用账号）：
export TEST_MINIO_ENDPOINT=http://169.254.140.78:9000   # 开发实例 S3 API（Console :9001 不是）
export TEST_MINIO_ACCESS_KEY=<专用账号 access key>
export TEST_MINIO_SECRET_KEY=<专用账号 secret key>
export TEST_MINIO_BUCKET=huajiang
cd backend && mvn -Dtest=MinioResourceStorageContractTest test

# admin 模式（开发验证豁免，仅限无专用账号时的联调，不是验收终态）：
set -a && source <(grep -E '^MINIO_' ../.env) && set +a   # 凭证只经环境注入，绝不写入命令行历史/文档
export MINIO_ENDPOINT=${MINIO_ENDPOINT:-http://169.254.140.78:9000}
export MINIO_RESOURCES_BUCKET=huajiang
mvn -Dtest=MinioResourceStorageContractTest -Dcontract.account=admin test
```

### 6.3 账号前提与 contract.account 模式

跨前缀写拒绝、管理操作（listBuckets/makeBucket）拒绝两条用例按**前缀受限专用账号**设计（计划 §5.4：只允许 `huajiang/resources/*` 的 GetObject/PutObject/DeleteObject，不预授 ListBucket/CreateBucket 等宽权限）：

| 模式 | 跨前缀/管理用例行为 | 用途 |
| --- | --- | --- |
| `restricted`（默认，不传 `contract.account` 时） | 断言**被拒绝**；失败即说明账号权限过宽——这是验收发现，应收紧 Policy 而非改测试 | 生产验收终态 |
| `-Dcontract.account=admin` | 改为"验证并告警"：断言操作成功（管理员确实能做）并输出 WARN"生产验收必须使用前缀受限专用账号" | 开发验证豁免（2026-08-27 首次真实运行即用管理员凭证，两条 WARN 已如实输出） |

匿名访问拒绝用例在两种模式下都断言被拒（私有 Bucket 硬不变量，计划不变量 1）。

### 6.4 SKIP 与 fail 的含义

| 结果 | 含义 |
| --- | --- |
| `Skipped: 7`，BUILD SUCCESS | 未提供凭证（预期：普通 CI / 本地无环境）。不把 MinIO 接入标记为完成 |
| 全部用例通过 | 真实 MinIO 满足 multipart/Range/补偿删除/权限矩阵全部验收项 |
| BUILD FAILURE：endpoint 指向公共环境 | 配置了 play.min.io / AWS 等公共端点，测试拒绝向公共端点写对象（防呆 fail，不是 bug） |
| 跨前缀/管理用例失败（restricted 模式） | 账号权限过宽（验收发现）：收紧账号 Policy 后重跑 |
| 其他用例失败 | 逐条对照 §6.5 结果解读排查（endpoint 可达性 / 凭证 / bucket / 代码 bug） |

### 6.5 用例清单与结果解读

| 用例 | 验证点 | 通过含义 |
| --- | --- | --- |
| multipart 上传往返 | 24 MiB 确定性生成流（不落盘）未知大小流式 save → fileSize、key 前缀、完整读取 SHA-256 逐字节一致；etag 含 `-`（multipart 分片特征） | 流式写入/读取链路端到端正确 |
| stat 一致 + Range | totalSize 与 stat 一致；中部闭区间/suffix/开放结尾三种 Range 的 offset/responseLength/partial 与内容区间一致 | Range 解析与 ranged GET 下推正确（拒绝方案 9） |
| Range 不下载全量 | 对 20 MiB+ 对象请求 1 KiB：responseLength=1024 且流只产出 1024 字节 | 大视频 Range 不会拉完整对象 |
| discard 补偿删除 | save → discard → open 断言 NOT_FOUND | 数据库 rollback 后补偿删除的存储侧语义（事务侧由 ResourceWriteCoordinatorTest 在真实 PG 锁定） |
| 匿名访问拒绝 | 无凭证 client 读取同一对象 → ResourceStorageException（UNAVAILABLE/IO_ERROR） | 私有 Bucket 硬不变量 |
| 跨前缀写（按模式） | 见 §6.3 | 账号前缀受限矩阵 |
| 管理操作（按模式） | 见 §6.3 | 账号无管理权限矩阵 |

### 6.6 测试对象清理

- 所有对象写入 `resources/contract-tests/{runId}/`（runId=UUID，每次运行全新）；跨前缀探针固定写 `other-prefix/contract-tests/{runId}/`。
- `@AfterAll` 先逐一幂等 discard 测试期间记录的全部 key（含跨前缀探针、admin 模式 makeBucket 后立即 removeBucket 的临时 bucket `contract-test-bucket-*`），再用 listObjects 兜底删除残留并断言前缀剩余为 0。
- 受限账号无 ListBucket 权限时（预期行为）退化按记录 key 清理，并断言全部 discard 成功。
- 运行后可独立复核（2026-08-27 首次真实运行后已复核）：`resources/contract-tests/` 与 `other-prefix/contract-tests/` 剩余对象数均为 0，无遗留临时 bucket。

### 6.7 首次真实运行记录（2026-08-27）

- 凭证：来自工作树 `.env`（管理员凭证，用户选择开发验证用途；仅环境注入，未写入任何文件/日志/提交）。
- 模式：`-Dcontract.account=admin`；endpoint `http://169.254.140.78:9000`；bucket `huajiang`（因 `.env` 的 `MINIO_DEFAULT_BUCKET` 值不合 S3 命名规则，显式以 `MINIO_RESOURCES_BUCKET` 覆盖）。
- 结果：7/7 通过（含两条 admin 豁免 WARN）；清理断言通过且独立复核前缀清零。
- 遗留：生产验收前需创建前缀受限专用账号并以默认 restricted 模式重跑（见 §4、§8）。

## 7. 开发部署验收清单（计划任务 7 与 §12 Phase 3 人工项）

开发环境部署 MinIO-only 构建后、开放日常使用前，逐项勾选以下人工验收项。前置：§0 四个环境变量已设置、§3 开发数据清理已通过 §3.5 验收断言、§6 contract test 已以真实凭证跑过一次。

### 7.1 基础设施行为验收

| # | 验收项 | 操作 | 预期 |
| --- | --- | --- | --- |
| 1 | 多实例共享读取 | 同一 Bucket 前后启动两个后端实例（不同端口），实例 A 上传资源，实例 B 预览/下载同一资源 | 成功且字节一致（对象存储无节点本地状态） |
| 2 | 容器/进程重启后读取 | 上传资源 → 重启后端进程/容器 → 再次预览/下载 | 成功（资源字节在 MinIO，不在进程内状态） |

### 7.2 七项 smoke test 勾选表

| # | smoke 项 | 操作要点 | 预期 | 勾选 |
| --- | --- | --- | --- | --- |
| 1 | 上传 | 聊天上传一张真实 PNG/JPEG | 消息带资源，预览可看 | [ ] |
| 2 | 图片生成 | 触发一次图片生成（对话内或 Agent） | 生成图片入库并可在聊天中预览 | [ ] |
| 3 | 预览 | 打开已上传图片的 `/content` 预览 | inline 展示（白名单 MIME） | [ ] |
| 4 | 下载 | 点击资源下载（`/download`） | attachment 下载，文件名/字节正确 | [ ] |
| 5 | Range | 对较大视频/音频资源请求部分 Range（如 `bytes=0-1023`） | 206 + 正确切片；越界 Range 返回 416 带 `Content-Range: bytes */total` 与 nosniff | [ ] |
| 6 | Agent 文件 | 让 Agent 用 `send_file_to_chat` 发送会话文件 | 文件消息出现且可下载；伪装 MIME/主动内容被拒 | [ ] |
| 7 | Voice | 语音通话录音 finalize + 一次 TTS 合成 | 通话音频入库可回放；TTS 音频可播放 | [ ] |

### 7.3 验收结果记录

- 验收日期：____（记录人：____）
- 全部通过后本清单随部署记录归档；任一项失败按 §5.3 处置（开放流量前可回退，开放后只 roll-forward）。

## 8. 生产启用前置条件（计划 §8.3）

本期为开发环境实现，代码具备生产质量，但**以下条件未全部满足前不得生产启用**（当前单节点 HTTP link-local MinIO 明确不可用作生产主存储）：

- [ ] 稳定、可路由的 Endpoint 与 HTTPS 证书校验
- [ ] 独立生产 Bucket（`h-agent-<env>-resources`）、独立应用账号与 Secret 轮换流程
- [ ] 明确的 RPO、RTO、容量水位和数据保留要求（由正式生产运维方案给出并验收，本计划不虚构数值）
- [ ] 备份或复制方案以及经过记录的恢复演练
- [ ] MinIO Server 版本升级、安全维护和容量规划评估（当前开发实例 `2025-09-07T16:13:09Z`，生产前重新评估）
- [ ] 用户删除权、未绑定资源和数据库级联删除后的对象保留策略
