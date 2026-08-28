# h-agent MinIO 资源对象存储实施计划

> **面向 AI 代理的工作者：** 按任务顺序实施，使用复选框（`- [ ]`）跟踪进度。每项任务先写失败测试，再写最小实现；未通过真实 MinIO contract test，不得把 MinIO 接入标记为完成。
>
> **2026-08-28 后续决定：** 本计划任务 6 的进程内 `ResourceStorageMetrics` LongAdder/snapshot 方案已被 [ADR-0005](../../adr/0005-separate-agent-traces-from-resource-metrics.md) 取代，后续实现使用 Micrometer → Prometheus；Langfuse 只承担 Agent Trace。其余 MinIO Interface、事务补偿、内容安全和存储生命周期决定保持有效。

- 日期：2026-08-24
- 设计确认：2026-08-26，经 grilling 逐项确认
- 状态：待实施
- 本期部署范围：开发环境
- 代码目标：具备生产质量，但生产启用受独立基础设施门槛约束

**目标：** 把聊天图片、视频、音频、附件及 Agent 交付文件的字节存储从节点本地目录切换到私有 MinIO。解决容器重建丢失、多实例不共享、大文件 Range 读取低效和本地磁盘容量问题，同时保持现有资源 ID、owner 鉴权以及 `/api/chat/resources/{id}/...` URL 不变。

**架构：** `ResourceStorage` 是资源字节能力的外部 seam，`MinioResourceStorage` 是唯一生产 Adapter。业务写入统一经过 `ResourceWriteCoordinator`，由它协调“对象先写、PostgreSQL 后挂接”和事务回滚时的 best-effort `discard`。PostgreSQL 继续保存资源身份、owner、MIME、文件名和对象 key；MinIO 只保存文件字节与少量技术 metadata。

**技术栈：** Java 26、Spring Boot 4.0.6、MyBatis-Plus、PostgreSQL、MinIO Java SDK `io.minio:minio:9.0.1`、JUnit 5、Mockito、AssertJ。

---

## 1. 实施范围

### 1.1 本期实施

| 资源来源 | 当前状态 | 本期目标 |
| --- | --- | --- |
| 用户聊天上传 | Controller 将整个文件读入 byte array，再写本地 | 流式写 MinIO，数据库保存 `OBJECT_STORAGE + objectKey` |
| MiniMax 图片生成 | 写本地 | 写 MinIO |
| 异步视频生成 | provider stream 写本地 | provider stream 直接写 MinIO |
| Voice TTS / 通话音频 | 通过 `ResourceStorage` 写本地 | 写 MinIO |
| Agent `send_file_to_chat` | 工作文件复制到本地资源目录 | 工作文件流式写 MinIO |
| 历史 `LOCAL_FILE` | 当前库仅一条且物理文件缺失 | 环境限定地清理数据库记录，不迁移文件 |

### 1.2 本期明确不实施

- 不归档 `chat_session_messages` 或 `agent_runs`。
- 不建设 Skill Catalog、Skill 发布包、Skill 缓存或 Subagent Skill 绑定。
- 不迁移 Harness `workspace_files`、Memory、Plan、Task 或工作区文件。
- 不替换 `PostgresBaseStore`、`PostgresAgentStateStore` 或 `RemoteFilesystemSpec`。
- 不改变 Subagent Definition Catalog，也不恢复 `subagents/*.md` 动态扫描。
- 不实现 `LOCAL_FILE` 双读、路由 Adapter、历史文件迁移器或迁移状态表。
- 不实现 presigned URL、`public-endpoint`、浏览器直传或浏览器直读。
- 不实现 orphan 表、后台对象清理、消息删除联动物理删除或未绑定资源 TTL。
- 不实现 MinIO health indicator、readiness、liveness 或可用性治理。
- 不保存永久公开 URL，不开放匿名 Bucket。
- 不由应用创建 Bucket、账号、Policy、KMS Key 或 MinIO 集群。

## 2. 已核实的代码与数据基线

### 2.1 当前资源模块

现有 seam：

```text
backend/src/main/java/com/h/backend/chat/infrastructure/storage/ResourceStorage.java
```

现有生产实现：

```text
backend/src/main/java/com/h/backend/chat/infrastructure/storage/LocalFileResourceStorage.java
```

当前资源根目录来自 `image-generation.storage.base-dir`，仓库默认值为 `/tmp/h-agent`。持久资源使用四个前缀：

```text
generated-images/
generated-videos/
generated-files/
call-audio/
```

`assistant-files/`、`call-turns/` 和 Harness workspace 是工作或临时目录，不是本计划要替换的资源主存储。

### 2.2 当前结构化引用

只有两张表保存资源存储位置：

- `chat_message_resources.storage_type + storage_key`
- `generation_tasks.artifact_storage_type + artifact_storage_key`

`view_url` 和 `download_url` 已是稳定的受鉴权应用 URL，不需要改成 MinIO URL。

### 2.3 当前开发数据

2026-08-26 只读核验结果：

- `chat_message_resources` 有 1 条 `LOCAL_FILE` 记录，数据库大小为 3,996 字节。
- 对应物理文件在当前主机默认目录、`/tmp` 和工作区中均不存在。
- `generation_tasks` 为 0 条。
- 当前已知资源目录中没有可迁移文件。

结论：本期不为这条已缺失的开发数据建设迁移模块；使用环境限定的人工清理步骤将 `LOCAL_FILE` 清零。

### 2.4 当前必须修正的问题

1. 用户上传调用 `MultipartFile.getBytes()`，大文件会完整进入堆内存。
2. 当前 Range 在完整本地流上执行 `skipNBytes`，suffix Range 语义也不正确。
3. `ResourceStorage` 同时承担 URL 构造，混入了与字节存储无关的职责。
4. 资源对象写入和 PostgreSQL metadata 写入不是同一事务，需要集中补偿规则。
5. `FileDeliveryTool` 允许模型提供 MIME，不能把该值作为可信响应类型。
6. 数据库级联删除资源 metadata 时不会删除物理文件；该生命周期问题本期保持现状并明确延后。

## 3. 不变量

1. Bucket 始终私有，匿名访问失败。
2. owner 鉴权由 PostgreSQL metadata 和当前用户完成，不能依赖 object key 难以猜测。
3. 数据库只保存稳定应用 URL，不保存 MinIO URL 或临时签名 URL。
4. 数据库 `storage_type` 固定写 `OBJECT_STORAGE`；Java 使用统一常量或 enum，禁止散落裸字符串。
5. 每个环境同一时刻只有一个活动对象存储；Bucket、Endpoint 和账号由环境配置决定，不写入业务行。
6. 生产运行态只有 `MinioResourceStorage`，没有本地回退、双写或双读。
7. 新写入 MinIO 失败时明确失败，不自动写回本地磁盘。
8. 配置字段缺失或格式非法时启动失败；启动过程不联网探测 MinIO、Bucket 或权限。
9. 运行期 MinIO 故障只由相关资源操作按现有错误链路返回，不改变应用 health/readiness。
10. 上传、生成和读取全程流式处理；不得把完整视频装入 byte array，也不得落本地中间文件。
11. 所有输入都有服务端大小上限；存储层绝对上限为 500 MiB。
12. object key 不包含 owner、用户名、会话 ID、prompt、原文件名或 display name。
13. 本期不计算或保存自定义 SHA-256；不把 multipart ETag 当作内容 hash。
14. 不提供业务对象覆盖接口；一次 `save` 内生成一次资源 ID/key，SDK 重试复用同一 key。
15. `discard` 只用于尚未成功挂接数据库对象的补偿，不用于聊天或资源删除。
16. 预览只允许经过签名校验的安全图片、音视频；其他文件强制 attachment。
17. access key、secret、完整 object key、签名 query 和 SDK 敏感异常不得进入前端响应或普通日志。

## 4. 模块设计

### 4.1 `ResourceStorage` interface

调用方和测试通过这个 seam 使用资源字节能力：

```java
public interface ResourceStorage {
    StoredResource save(ResourceSaveCommand command);

    ResourceContent open(
            String storageKey,
            ResourceRange range
    );

    void discard(String storageKey);
}
```

接口约定：

- `save` 固定返回 `storageType=OBJECT_STORAGE`。
- `open` 内部执行 stat、解析实际 Range 并下推 ranged GET，不额外暴露通用 `stat`。
- `discard` 对不存在对象幂等；失败抛出统一存储异常，由 Coordinator 记录但不能覆盖原始事务错误。
- `ResourceContent` 拥有可关闭输入流，并返回 MIME、total size、response length、offset 和 partial 标记。
- Adapter 负责关闭 `ResourceSaveCommand` 提供的输入流。

不创建 `ResourceBackend`、`RoutingResourceStorage`、`ResourceLocator` 或通用 S3 CRUD interface。只有未来出现两个必须同时在线的真实存储 Adapter 时，才新增路由 seam。

### 4.2 `MinioResourceStorage`

`MinioResourceStorage` 直接实现 `ResourceStorage`，是唯一生产 Adapter 和唯一 Spring Bean：

```text
读取：ChatResourceService -> ResourceStorage -> MinioResourceStorage

写入：业务调用方 -> ResourceWriteCoordinator
                     ├─ ResourceStorage.save/discard
                     └─ PostgreSQL 挂接事务
```

它隐藏以下实现复杂度：

- MinIO Client 创建和配置。
- 流式 put/get 与 multipart。
- stat 与 Range offset/length 解析。
- SDK 异常脱敏和错误映射。
- object key、Content-Type 和技术 metadata。
- 绝对大小上限和输入流生命周期。

### 4.3 `ResourceWriteCoordinator`

所有业务写入点只依赖 Coordinator，不直接调用 `ResourceStorage.save()`：

```java
public interface ResourceWriteCoordinator {
    <T> T saveAndAttach(
            ResourceSaveCommand command,
            ResourceAttachment<T> attachment
    );
}
```

执行语义：

1. 调用 `ResourceStorage.save`，对象成功后获得 `StoredResource`。
2. 使用 `PROPAGATION_REQUIRED` 执行数据库挂接回调；没有事务时创建事务，已有事务时加入。
3. 在执行挂接回调前注册 `TransactionSynchronization`，以事务最终状态为准。
4. Coordinator 自己创建事务时，在 commit 后返回；加入外层事务时，返回回调结果但保留 completion hook，最终 rollback 仍会触发补偿。
5. 创建事务、注册 synchronization 或执行回调在 hook 生效前失败时，立即调用 `discard`。
6. 事务最终 rollback 时调用 `ResourceStorage.discard(storageKey)`；commit 后不删除。
7. `discard` 失败只写安全结构化日志和计数，不覆盖原始数据库异常。

包级架构测试必须禁止 Controller、Voice、Generation 和 Tool 直接调用 `ResourceStorage.save()`；读路径可以调用 `open()`。

### 4.4 URL 与存储类型

- `buildViewUrl`、`buildDownloadUrl` 从 `ResourceStorage` 移出，由应用层 `ChatResourceUrls` 生成。
- URL 继续使用 `/api/chat/resources/{id}/content|download`。
- Java 使用 `ResourceStorageType.OBJECT_STORAGE`；数据库仍保存字符串 `OBJECT_STORAGE`。
- 本期不增加数据库 CHECK constraint；读到其他类型时按内部数据错误 fail closed。

### 4.5 错误语义

第一版只保留四类稳定错误：

| 错误种类 | HTTP | 说明 |
| --- | --- | --- |
| `NOT_FOUND` | 404 | 对象不存在，或 owner 查询结果不存在 |
| `SIZE_LIMIT` | 413 | 实际读取超过业务或存储绝对上限 |
| `UNAVAILABLE` | 503 | MinIO 连接、超时、5xx、凭证或权限异常 |
| `IO_ERROR` | 500 | 其他流或协议错误 |

非法或 multiple Range 返回 400；合法但不可满足的 Range 返回 416。响应正文不得暴露 Bucket、Endpoint、object key、MinIO SDK 异常或凭证。

## 5. Bucket、对象键与权限

### 5.1 Bucket 与逻辑前缀

开发环境可暂用现有私有 Bucket `huajiang`，应用只使用：

```text
resources/*
```

生产使用独立 Bucket：

```text
h-agent-<env>-resources
```

物理 Bucket 名完全配置化，代码不拼接环境名。

### 5.2 Object key

```text
resources/v1/{resourceType}/{yyyy}/{MM}/{resourceId}.{safeExtension}
```

示例：

```text
resources/v1/images/2026/08/550e8400-e29b-41d4-a716-446655440000.webp
resources/v1/videos/2026/08/6d9b6e0a-0944-4ca0-89a3-bfc45e9d74e1.mp4
resources/v1/audio/2026/08/9cf8dd8d-67bf-41ea-89f6-d33c2cb76a13.mp3
resources/v1/files/2026/08/67147420-bb22-4d21-92ad-2cc7acde59ac.pdf
```

规则：

- `resourceId` 在一次 `save` 开始时生成，并同时作为数据库资源 ID。
- 扩展名由服务端安全映射推导，不直接信任用户或模型输入。
- 不提供按业务 ID覆盖对象的接口。
- SDK 在同一次调用内重试时复用相同 key。
- 本期不承诺跨请求严格幂等；业务层不得自动重新调用整个 `saveAndAttach`。

### 5.3 Object metadata

只保存：

```text
Content-Type
x-amz-meta-schema-version=1
x-amz-meta-created-by=h-agent
```

不保存 prompt、消息正文、用户名、原文件名或自定义 SHA-256。业务 metadata 继续保存在 PostgreSQL。

### 5.4 应用账号最小权限

开发应用账号只允许访问 `huajiang/resources/*`：

```text
GetObject
PutObject
DeleteObject
```

`DeleteObject` 仅由 Coordinator 补偿调用。应用账号不获得 CreateBucket、Policy 修改、用户管理或常规 Bucket 列表权限。multipart 所需权限通过真实 contract test 验证，缺少时再精确补充，不预先授予宽权限。

生产使用独立 Bucket、独立应用账号和独立 Secret。共享过的管理员凭证在生产前必须轮换，任何凭证都不得写入仓库、镜像或文档。

## 6. 写入、读取与内容安全

### 6.1 流式写入契约

`ResourceSaveCommand` 增加：

```text
declaredSize  可空；已知大小时必须传
maxBytes      调用方业务上限
contentStream 单次可消费输入流，由 Adapter 关闭
```

限制规则：

- 存储层绝对上限固定为 500 MiB，可配置但不能被调用方放大。
- 实际上限是业务 `maxBytes` 与绝对上限中的较小值。
- `maxBytes<=0` 表示使用绝对上限，不表示无限。
- 实际读取超过上限时立即中止上传并映射为 `SIZE_LIMIT`。
- `declaredSize` 已知时，必须先检查上限；最终读取大小不一致按 `IO_ERROR` 处理。
- 未知大小允许 MinIO multipart 流式上传，不落本地临时文件。
- 用户上传从 `MultipartFile.getInputStream()` 读取，不调用 `getBytes()`。
- 视频 provider stream 直接写 MinIO，不先写 `/tmp`。

### 6.2 图片宽高

存储模块不使用 `ImageIO` 解码图片。上传或图片生成调用方在保存前完成内容校验并提供 width/height；不需要或无法安全获得时允许为空。

### 6.3 MIME 与主动内容

可信规则：

- 用户、文件名、HTTP Client 和 Agent 模型提供的 MIME 都只作为提示。
- JPEG、PNG、WebP、MP4、MP3、M4A、WAV、WebM Audio 必须校验基础文件签名/魔数。
- MIME 与签名冲突时拒绝。
- HTML、SVG 和 JavaScript 等主动内容明确拒绝。
- PDF、Office、Markdown、文本及未知文件只允许 attachment。
- 未知类型使用 `application/octet-stream`。
- 所有资源响应添加 `X-Content-Type-Options: nosniff`。
- 检查流只缓存并回放有上限的文件头，不读取完整视频或把输入落本地文件。
- 本期不引入 Apache Tika。

允许内联预览的白名单只有经过签名校验的图片和音视频。其他文件即使请求 `/content`，也强制 `Content-Disposition: attachment`。

### 6.4 Range

本期只支持一个 byte Range：

```text
bytes=start-end
bytes=start-
bytes=-suffix
```

流程：

1. Controller 将 header 解析为语法级 `ResourceRange`。
2. `MinioResourceStorage` stat 对象并结合 total size 解析实际 offset/length。
3. Adapter 使用 MinIO 原生 ranged GET，不下载前置字节。
4. `ResourceContent` 返回 total size、offset、response length 和 partial。
5. Controller 生成 `Accept-Ranges`、`Content-Length` 和 `Content-Range`。

状态语义：

- 无 Range：200。
- 合法可满足单 Range：206。
- malformed 或 multiple ranges：400。
- 合法但不可满足：416，并返回 `Content-Range: bytes */total`。
- 本期不实现 multipart ranges、`If-Range`、ETag 条件请求或 HEAD 专用优化。

### 6.5 删除与保留

- 数据库挂接失败：Coordinator best-effort `discard`。
- 消息或会话删除：保持现有行为，只删除数据库 metadata，不同步删除 MinIO 对象。
- 未绑定上传：保持现有行为，不增加 TTL。
- 本期不建设对象 inventory、引用计数或自动 GC。
- 用户删除权、保留期限和离线对账在正式生产前单独设计。

## 7. 当前 `LOCAL_FILE` 数据清理

本期不创建迁移类、migration table、test-scoped 源读取器或后台任务。

在开发环境部署 MinIO-only 构建前，由操作者执行环境限定的清理：

1. 确认连接的是目标开发数据库，不是生产数据库。
2. 备份待删除的 `chat_message_resources` 行。
3. 查询 `chat_message_resources WHERE storage_type='LOCAL_FILE'`，数量必须与已核验预期一致。
4. 查询 `generation_tasks WHERE artifact_storage_type='LOCAL_FILE'`，必须为 0；否则停止清理并重新评估。
5. 只删除 `chat_message_resources` 中的 `LOCAL_FILE` 行，不删除父聊天消息。
6. 提交后断言两张表的 `LOCAL_FILE` 引用均为 0。
7. 再部署不支持本地存储的代码。

清理不得写成通用 Flyway；否则其他环境应用迁移时可能误删仍有价值的历史资源。实际 SQL 放入运行手册，必须带目标环境、预期数量和人工确认步骤。本计划不授权自动执行该 SQL。

## 8. 配置与部署

### 8.1 建议配置

```yaml
chat:
  resources:
    public-base-url: ""
    upload:
      max-file-size: 10485760

resource-storage:
  absolute-max-bytes: 524288000
  minio:
    endpoint: ${MINIO_ENDPOINT:}
    access-key: ${MINIO_ACCESS_KEY:}
    secret-key: ${MINIO_SECRET_KEY:}
    region: ${MINIO_REGION:us-east-1}
    bucket: ${MINIO_RESOURCES_BUCKET:}
    object-prefix: ${MINIO_RESOURCES_PREFIX:resources/}
    connect-timeout: 3s
    read-timeout: 60s
    part-size-bytes: 10485760
```

不提供 `minio.enabled`、`write-provider`、`public-endpoint`、presign、migration 或 orphan cleanup 配置。

启动只做配置字段和格式校验，不访问网络、不检查 Bucket、不写探针对象。实际 MinIO 错误由资源操作映射。

### 8.2 当前开发实例

2026-08-25 已做只读核验：

| 项目 | 当前状态 | 判断 |
| --- | --- | --- |
| Console | `http://169.254.140.78:9001` | 仅管理 UI，不能作为应用 Endpoint |
| S3 API | `http://169.254.140.78:9000` | 开发环境应用 Endpoint |
| Bucket | `huajiang`，私有 | 只允许应用使用 `resources/*` |
| Region | 默认 `us-east-1` | 与建议配置一致 |
| Versioning/SSE/Lifecycle/CORS | 未配置 | 本期开发验证不要求 |
| 拓扑 | 单节点、单磁盘、无冗余 | 只适合开发验证 |
| 可用容量 | 核验时约 207 GB | 只作为开发时点信息，不是容量承诺 |
| Server 版本 | `2025-09-07T16:13:09Z` | 生产前重新评估升级与维护状态 |
| 网络 | link-local HTTP | 不允许直接作为生产 Endpoint |

非敏感开发变量：

```text
MINIO_ENDPOINT=http://169.254.140.78:9000
MINIO_RESOURCES_BUCKET=huajiang
MINIO_RESOURCES_PREFIX=resources/
MINIO_REGION=us-east-1
```

Access Key 与 Secret Key 只通过环境变量或 Secret 管理系统注入。应用不得使用管理员账号作为长期凭证。

### 8.3 生产启用阻断条件

本期完成不等于生产启用。生产部署前必须另行满足：

- 稳定、可路由的 Endpoint 和 HTTPS 证书校验。
- 独立生产 Bucket、应用账号和 Secret 轮换流程。
- 明确的 RPO、RTO、容量水位和数据保留要求。
- 备份或复制方案以及经过记录的恢复演练。
- Server 版本升级、安全维护和容量规划评估。
- 用户删除权、未绑定资源和数据库级联删除后的对象保留策略。

本计划不虚构 RPO/RTO 数值；由正式生产运维方案给出并验收。

## 9. 预计文件结构

```text
backend/src/main/java/com/h/backend/chat/infrastructure/storage/
├── ResourceStorage.java                  # 修改：save/open/discard
├── MinioResourceStorage.java             # 创建：唯一生产 Adapter
├── ResourceWriteCoordinator.java         # 创建：写对象与事务补偿
├── TransactionalResourceWriteCoordinator.java
├── ResourceSaveCommand.java              # 修改：stream/declaredSize/maxBytes
├── ResourceContent.java                  # 修改：Range 响应 metadata
├── ResourceRange.java                    # 创建
├── ResourceRangeException.java           # 创建：400/416 语义
├── ResourceStorageType.java              # 创建：OBJECT_STORAGE
├── ResourceStorageProperties.java        # 创建
├── ResourceStorageConfiguration.java     # 创建
├── ResourceStorageException.java         # 创建
└── ResourceStorageErrorKind.java          # 创建：四类错误

backend/src/main/java/com/h/backend/chat/application/
├── ChatResourceUrls.java                 # 创建：稳定 view/download URL
└── ResourceContentPolicy.java            # 创建：MIME/预览/attachment 策略

backend/src/main/java/com/h/backend/chat/infrastructure/content/
└── ResourceContentInspector.java         # 创建：轻量签名校验

backend/src/test/...
├── MinioResourceStorageTest.java
├── ResourceWriteCoordinatorTest.java
├── ResourceRangeTest.java
├── ResourceContentPolicyTest.java
├── ResourceStorageArchitectureTest.java
└── MinioResourceStorageContractTest.java
```

删除：

```text
backend/src/main/java/com/h/backend/chat/infrastructure/storage/LocalFileResourceStorage.java
backend/src/test/java/com/h/backend/chat/storage/LocalFileResourceStorageTest.java
```

主要修改调用点：

```text
ChatResourceController.java
ChatResourceServiceImpl.java
ChatReferenceImageResolver.java
ImageGenerationServiceImpl.java
FileDeliveryTool.java
ResourceStorageGeneratedArtifactAdapter.java
ChatGenerationProjectionAdapter.java
CallTurnService.java
VoiceTtsService.java
AssistantFileStorage.java
application.yml
backend/pom.xml
```

## 10. 分阶段实施任务

### 任务 1：锁定深接口和错误语义

**文件：** `ResourceStorage.java`、`ResourceSaveCommand.java`、`ResourceContent.java`、`ResourceRange.java`、错误类型及测试。

- [ ] 先写失败测试锁定 `save/open/discard`。
- [ ] 测试 stream 关闭责任、declared size、业务上限和 500 MiB 绝对上限。
- [ ] 测试 Range 四种语法与 200/206/400/416 结果。
- [ ] 测试四类错误不会泄露 MinIO 配置。
- [ ] 删除 `access`、presign、通用 stat/delete CRUD 和 storage locator 设计。

### 任务 2：实现唯一 MinIO Adapter

**文件：** `backend/pom.xml`、`MinioResourceStorage.java`、properties/configuration 和 contract tests。

- [ ] 添加固定版本 `io.minio:minio:9.0.1`。
- [ ] 实现 UUID key、流式 put/get、multipart、stat、ranged GET 和 discard。
- [ ] 配置缺失或格式非法时启动失败，但启动不发 MinIO 网络请求。
- [ ] 不创建 `minio.enabled` 或本地 fallback Bean。
- [ ] 单元测试覆盖 SDK 异常到四类错误的脱敏映射。

```bash
cd backend
mvn -Dtest=MinioResourceStorageTest,ResourceRangeTest,ResourceStorageConfigurationTest test
```

### 任务 3：实现写入 Coordinator 和流式调用方

**文件：** Coordinator、Controller、图片/视频/Voice/Agent 文件写入点及测试。

- [ ] 测试 commit 成功不 discard。
- [ ] 测试 callback 失败和外层事务 rollback 都触发一次 discard。
- [ ] 测试 discard 失败不覆盖原始异常。
- [ ] 所有写入点改用 Coordinator；架构测试禁止直接调用 `save`。
- [ ] `MultipartFile` 改用 `getInputStream()`，测试证明不调用 `getBytes()`。
- [ ] `AssistantFileStorage` 为 Agent 交付提供受大小约束的 stream，不再要求 `FileDeliveryTool` 复制完整 byte array。
- [ ] 视频 provider stream 直接传入，不落本地文件。
- [ ] 异步生成对象只有写入 `generation_tasks` 的 artifact type/key 后才算挂接，Coordinator 必须覆盖该持久化事务。
- [ ] Agent 文件只有 `appendResourceMessage` 事务提交后才算挂接；流事件在提交后发布。
- [ ] URL 构造移到 `ChatResourceUrls`。

### 任务 4：内容安全、预览和 Range

**文件：** content inspector/policy、Controller、ChatResourceService、reference resolver 及测试。

- [ ] 测试安全图片、音视频签名与 MIME 一致性。
- [ ] 测试 HTML、SVG、JavaScript 被拒绝。
- [ ] 测试模型声明 MIME 不能覆盖检测结果。
- [ ] 测试非白名单内容强制 attachment，所有响应带 `nosniff`。
- [ ] 测试 owner 鉴权先于对象读取。
- [ ] 测试 suffix Range、开放结尾、越界和 multiple Range。
- [ ] 证明视频 Range 使用 MinIO offset/length，不下载完整对象。

### 任务 5：清理开发数据并删除本地实现

**文件：** `docs/runbooks/minio-resource-storage.md`、环境限定清理 SQL 说明、删除本地类和配置。

- [ ] 在运行手册记录目标环境、预期 `LOCAL_FILE` 数量、备份和人工确认步骤。
- [ ] 人工执行前再次只读盘点；`generation_tasks` 出现 `LOCAL_FILE` 时停止。
- [ ] 只删除资源行，不删除父聊天消息。
- [ ] 断言两张表 `LOCAL_FILE=0` 后才部署 MinIO-only 构建。
- [ ] 删除 `LocalFileResourceStorage`、本地资源配置、Bean 和测试。
- [ ] 完整 test compilation、测试和 package 通过。

本任务中的 SQL 是人工外部动作；实施代码或文档时不得自动执行。

### 任务 6：真实 MinIO contract 与最小可观测性

**文件：** contract test、结构化日志/计数、运行手册。

- [ ] 使用应用专用账号和 `resources/contract-tests/<runId>/` 前缀。
- [ ] 使用 20–32 MiB 生成流验证 multipart、stat、完整读取和 Range。
- [ ] 验证匿名访问、跨前缀访问和管理操作被拒绝。
- [ ] 验证数据库 rollback 后补偿删除。
- [ ] contract 结束删除测试前缀对象。
- [ ] 增加 save/open/discard 成功失败计数和 discard 失败告警。
- [ ] 日志不记录 secret、完整 key、Endpoint 或 SDK 敏感异常。

### 任务 7：完整验证与开发部署

- [ ] 目标测试、完整 `mvn test` 和 package 通过。
- [ ] 两个后端实例读取同一个 MinIO 对象。
- [ ] 应用/容器重启后资源仍可读取。
- [ ] 大视频 Range 不下载完整对象。
- [ ] MinIO 故障时资源请求返回既定错误，非资源业务沿现有逻辑运行。
- [ ] 生产包只有一个 `ResourceStorage` Bean，没有本地存储代码或挂载。
- [ ] 数据库不存在 `LOCAL_FILE` 引用。

## 11. 测试策略

### 11.1 单元与 interface contract

- save 返回的 ID 与 key UUID 一致，storage type 固定为 `OBJECT_STORAGE`。
- 已知/未知 declared size、空文件、上限边界和超限语义正确。
- input stream 在成功和异常路径都关闭。
- open 返回 total size、response length、offset 和 partial。
- discard 不存在对象幂等。
- SDK timeout、NoSuchKey、AccessDenied、5xx 映射正确且脱敏。

### 11.2 事务与补偿

- 无外层事务时由 Coordinator 创建事务。
- 有外层事务时加入现有事务。
- callback 抛错、commit 失败和 outer rollback 均触发 discard。
- commit 后不 discard。
- discard 自身失败不覆盖业务异常。
- 所有生产写入点均无法绕过 Coordinator。

### 11.3 HTTP 与安全

- 用户 A 访问用户 B 资源统一返回不存在。
- 非法 MIME、签名冲突、危险主动内容和超大文件拒绝。
- `nosniff`、Content-Type、Content-Length、Content-Range、Accept-Ranges 和 Content-Disposition 正确。
- malformed/multiple Range 为 400，不可满足 Range 为 416 且带 `bytes */total`。
- object key、日志和异常不包含隐私或凭证。

### 11.4 真实 Contract

真实 MinIO contract 可以不进入每次普通 CI，但它是本期最终验收的强制门槛，不能因缺少 Endpoint 而将功能标记完成。

### 11.5 回归命令

```bash
cd backend
mvn -Dtest='*ResourceStorage*,*ResourceWriteCoordinator*,*ChatResource*,*ImageGeneration*,*GenerationArtifact*,*Voice*,*FileDelivery*' test
mvn test
mvn -DskipTests package
```

## 12. 开发切换顺序

### Phase 0：隔离实现

- 旧开发版本继续使用本地存储。
- 完成 MinIO Adapter、Coordinator、流式调用方、内容策略和单元测试。
- 不部署尚未清理 `LOCAL_FILE` 的 MinIO-only 构建。

### Phase 1：真实 Contract

- 创建前缀受限的开发应用账号。
- 使用当前开发 MinIO 运行强制 contract suite。
- 完成 multipart、Range、权限、补偿删除和多实例测试。

### Phase 2：清理开发数据

- 备份当前坏资源行。
- 人工确认目标环境和预期数量。
- 删除 `chat_message_resources` 的 `LOCAL_FILE` 行，不删除父消息。
- 断言两张引用表 `LOCAL_FILE=0`。

### Phase 3：部署 MinIO-only 开发构建

- 删除本地生产类和配置。
- 部署只包含 `MinioResourceStorage` 的构建。
- 容器不挂载历史资源目录。
- 完成上传、生成、预览、下载、Range、Agent 文件和 Voice smoke test。

## 13. 失败恢复

### 13.1 开放流量前

如果开发数据清理或 smoke test 失败，可以在重新开放流量前恢复备份行和旧构建。

### 13.2 开放流量后

只允许 roll-forward：修复配置、MinIO、权限或代码并恢复资源能力。不得恢复切换前 PostgreSQL 快照，因为它会丢失切换后的聊天、运行和资源 metadata。

MinIO 运行故障时，资源操作按既定 404/413/503/500 语义失败；本期不增加 health/readiness 行为。

## 14. Skill 与日志的未来选型

本节只记录选型，不产生本期任务：

- 用户消息继续存 PostgreSQL；归档需求出现时，通过数据平台 ETL/CDC 设计，不由 Spring Boot 零散写 JSONL 对象。
- `agent_runs` 在线摘要继续存 PostgreSQL；链路追踪使用 OpenTelemetry/Langfuse，分析进入数仓或湖仓。
- 内置 Skill 源码继续存 Git/classpath。
- Harness 可编辑 Skill 继续存 PostgreSQL `workspace_files`。
- 只有未来正式发布含大型 assets 的不可变 Skill 版本包时，才考虑 PostgreSQL metadata + MinIO bytes。
- Subagent Definition、Version、Session 和运行实例仍由 PostgreSQL Catalog 管理，MinIO 不能替代。

## 15. 拒绝方案

1. 保留 `LOCAL_FILE` 双读或运行时 fallback：当前无可迁移文件，只会永久增加分支。
2. 为一条已丢失的开发资源建设迁移器、状态表或 JUnit 生产迁移入口。
3. 使用通用 Flyway 删除所有环境的 `LOCAL_FILE` 数据。
4. 增加 `ResourceBackend`、`RoutingResourceStorage` 或浅 S3 CRUD interface。
5. MinIO 失败自动写回节点本地目录。
6. 公开 Bucket或把永久 MinIO URL写入数据库。
7. 本期实现 presign、`public-endpoint` 或浏览器直传。
8. 上传使用 `getBytes()`，或视频先落本地中间文件。
9. Range 使用完整下载后 `skipNBytes`。
10. 信任用户、文件名或 Agent 模型声明的 MIME。
11. 为流式上传强行增加自定义 SHA-256 metadata，导致二次读取或临时落盘。
12. 建 orphan 表却无法在 PostgreSQL 故障时可靠登记。
13. 让业务写入点绕过 Coordinator 直接调用 `ResourceStorage.save()`。
14. 开放流量后恢复切换前数据库快照。
15. 顺便归档消息、agent_runs 或建设 Skill 版本平台。

## 16. 完成标准

### 16.1 本期代码与开发环境完成

- [ ] `MinioResourceStorage` 是唯一生产 `ResourceStorage` Bean。
- [ ] 所有资源写入都经过 `ResourceWriteCoordinator`，事务 rollback 会 best-effort discard。
- [ ] 图片、视频、音频、附件和 Agent 交付文件可以流式写 MinIO。
- [ ] 数据库保存 `OBJECT_STORAGE + objectKey` 和稳定应用 URL。
- [ ] owner 鉴权、内容安全、Range、下载和错误映射测试通过。
- [ ] 不包含 presign、orphan、migration、health/readiness 或 local fallback 模块。
- [ ] 当前开发数据库 `LOCAL_FILE=0`，本地生产实现已删除。
- [ ] 当前开发 MinIO 使用专用最小权限账号通过真实 contract suite。
- [ ] secret、隐私字段、完整 key 和敏感 SDK 异常不进入日志或响应。
- [ ] 目标测试、完整 `mvn test` 和 `mvn -DskipTests package` 全部通过。
- [ ] 运行手册覆盖开发数据清理、凭证轮换、contract、错误排查和 roll-forward。

### 16.2 生产启用前置条件

- [ ] 稳定可路由 Endpoint 和 HTTPS 已就绪。
- [ ] 生产 Bucket、账号、Secret 和权限隔离已完成。
- [ ] RPO、RTO、容量和保留制度已由生产运维方案明确。
- [ ] 备份/复制和恢复演练已通过。
- [ ] 用户删除权、未绑定资源和级联删除后的对象保留策略已确认。

生产前置条件不阻塞本期代码和开发环境任务完成，但未满足时不得把当前单节点 HTTP MinIO 用作生产主存储。
