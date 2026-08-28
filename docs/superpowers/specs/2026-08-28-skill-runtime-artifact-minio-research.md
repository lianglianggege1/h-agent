# Skill 不可变运行制品的 MinIO/S3 通用实现调研

- 日期：2026-08-28
- 范围：MinIO AIStor 官方文档、Amazon S3 官方文档、OCI Image/Distribution Specification、SLSA v1.2，以及本仓库现有 MinIO 实现
- 目的：为 System Skill 与 User Skill 的不可变运行制品设计确定业内通用方案；区分源码、发布记录、运行制品与缓存各自的权威边界
- 非目标：本调研不修改 Skill 主设计、不实现代码、不决定 MinIO 生产部署拓扑

## 结论摘要

1. **Git 与 MinIO 应承担不同职责。** Gitee 保存 User Skill 的源码、Proposal、正式提交和 tag；MinIO 保存由固定 Git commit 构建出的不可变运行 Bundle。Agent 运行时只读取 PostgreSQL 绑定的 MinIO Artifact Descriptor，不直接读取 `master`、branch、tag 或“最新对象”。这与容器生态的通用模式一致：源码仓库负责源历史，Artifact Registry 以 digest 分发构建产物。
2. **运行制品应采用内容寻址，而不是可变 key 或只依赖 MinIO `versionId`。** OCI Descriptor 用 `mediaType + digest + size` 描述内容，并要求消费者校验 digest 和长度。建议 Skill Release 同样使用 `sha256:<hex>` 作为主身份，MinIO object key 包含 digest；`versionId` 只作为存储层恢复与取证字段，不能代替 Release 版本号或内容 digest。[OCI Content Descriptor](https://github.com/opencontainers/image-spec/blob/main/descriptor.md)
3. **ETag 不能作为 Skill digest。** multipart 上传或 SSE-KMS/SSE-C 加密时，S3 ETag 不是完整对象的 MD5；实现必须由应用计算 SHA-256，并在上传和消费时校验。[Amazon S3：上传完整性与 ETag](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html)
4. **应使用 Skill 专用 Bucket，不复用聊天资源 Bucket。** System Skill 与 User Skill 至少和 `resources/` 业务资源隔离；推荐 System/User 再分成两个 Bucket，因为发布者、保留期、Object Lock、加密和故障等级不同。Bucket 数过多时，用户之间使用一个 User Skill Bucket 下的 owner 前缀即可，不必每用户一个 Bucket。AWS 明确指出 Versioning、默认加密等大量能力是 Bucket 级而不是 prefix 级；MinIO 则支持以 IAM policy 将服务账号限制到 Bucket 或 prefix。[AWS Bucket patterns](https://docs.aws.amazon.com/AmazonS3/latest/userguide/common-bucket-patterns.html)、[MinIO Multi-Tenancy](https://docs.min.io/aistor/administration/multi-tenancy/)
5. **单个 Bundle 对象是最简单可靠的发布原子单元。** 将合法目录规范化打包成一个确定性 Bundle，计算 SHA-256 后以 create-only 条件写入 digest key；写成功并完成 HEAD/读取校验后，数据库才把 Artifact 标为 `AVAILABLE`。S3 单 key 更新是原子的，并提供强 read-after-write；MinIO 在受支持的部署文件系统上承诺严格 read/list-after-write 一致性。[Amazon S3 consistency](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html)、[MinIO strict consistency](https://blog.min.io/strict-consistency-hard-requirement-for-primary-storage/)
6. **Object Versioning、内容寻址和产品 Release 是三件不同的事。** `v1/v2` 是产品发布顺序；`sha256` 是制品身份；MinIO Versioning 是防止误覆盖/误删除的恢复层。建议 Artifact Bucket 开启 Versioning，但所有正常运行读取仍按 object key + SHA-256 校验，不能读取无约束的“latest”。[MinIO Object Versioning](https://docs.min.io/aistor/administration/objects-and-versioning/versioning/)
7. **Object Lock/WORM 不是普通不可变制品的默认前提。** 它适合合规或强防篡改需求，要求 Versioning，并会阻止特定版本删除；它也会让孤儿清理和保留策略复杂化。本项目第一版更合适的组合是：内容寻址 key、create-only 写、应用运行账号无删除权限、Versioning 开启、数据库永久保留 Release。只有明确需要对管理员也防删除时，才把已发布制品放入单独的 WORM Bucket 或设置对象级 retention。[MinIO Object Lock](https://docs.min.io/aistor/administration/object-locking-and-immutability/)
8. **本地缓存必须以 digest 为 key，并在使用前校验。** MinIO 故障时，只允许继续使用已经完整下载且通过 size/SHA-256 校验的精确 Artifact；cache miss 必须失败，禁止回退到旧 Release、Git 工作树或同名 Skill。内容版本化命名也是 CDN/Artifact 分发中避免缓存失效歧义的通用做法。[CloudFront versioned file names](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/UpdatingExistingObjects.html)
9. **Presigned URL 只适合浏览器的短期下载，不适合作为 Agent 内部运行读取机制。** 它本质上是 bearer token，在到期前持有者即可执行被签名操作；运行时服务应使用只读服务账号直接读取。若未来提供 Bundle 下载，可在完成业务 owner 校验后签发短 TTL、固定 key 的 GET URL，且不得记录完整 URL；要求立即撤销时应由应用代理下载。[Amazon S3 presigned URL](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
10. **复制、Versioning 与备份不能混为一谈。** MinIO Bucket/Site Replication适合连续容灾并保留对象版本；`mc mirror` 只复制当前对象，不能保留版本历史和大部分元数据。恢复必须联合验证 PostgreSQL 中每个 Artifact Descriptor、Gitee source commit 与 MinIO 对象 digest。[MinIO Replication](https://docs.min.io/aistor/administration/replication/)、[MinIO site failure recovery](https://docs.min.io/aistor/operations/failure-and-recovery/recover-after-site-failure/)

## 1. 本仓库当前实现及其适用边界

### 1.1 已经正确建立的 MinIO 基础

现有聊天资源存储已经实现了以下可复用的基础经验：

- 私有 Bucket、环境变量注入凭证、日志不输出 secret/endpoint/完整 key；
- `ResourceStorage` 作为业务字节存储 seam，MinIO SDK 被封装在 Adapter 内；
- 未知长度流式 multipart 上传、Range GET、容量上限、稳定错误分类；
- “对象先写、PostgreSQL 后挂接”，数据库事务失败后 best-effort 删除孤儿；
- 业务身份和 owner 保存在 PostgreSQL，对象 key 本身不承担鉴权；
- 真实 MinIO contract test 覆盖 multipart、Range、匿名拒绝、前缀权限与补偿删除。

对应实现：

- [`ResourceStorage.java`](../../../backend/src/main/java/com/h/backend/chat/infrastructure/storage/ResourceStorage.java)
- [`MinioResourceStorage.java`](../../../backend/src/main/java/com/h/backend/chat/infrastructure/storage/MinioResourceStorage.java)
- [`TransactionalResourceWriteCoordinator.java`](../../../backend/src/main/java/com/h/backend/chat/infrastructure/storage/TransactionalResourceWriteCoordinator.java)
- [`minio-resource-storage.md`](../../runbooks/minio-resource-storage.md)

这些基础证明 MinIO 连接、流式 I/O 和错误语义已经可用，但现有接口是**聊天资源上下文的深模块**，并不是通用 Artifact Registry。

### 1.2 不能直接复用 `ResourceStorage` 作为 Skill Artifact Store

现有 `ResourceStorage` 与不可变 Skill 制品存在以下语义差异：

| 维度 | 现有聊天资源 | Skill 运行制品需要 |
| --- | --- | --- |
| object key | 日期 + 随机 UUID | owner namespace + SHA-256 内容寻址 |
| 完整性 | metadata 明确不写 SHA-256 | Descriptor 必须保存并消费时校验 SHA-256 |
| 删除 | `discard` 是正常补偿能力 | 已登记 Release 的制品不得由应用删除 |
| 查询 | 面向 Range 内容读取 | 面向固定 Descriptor 的完整 Bundle 解析和缓存 |
| Bucket | `MINIO_RESOURCES_BUCKET` + `resources/` | Skill 专用 Bucket 与专用 service account |
| 生命周期 | 业务资源可随业务记录处置 | 已发布 Release 永久保留；只清理未引用孤儿 |
| 权威身份 | `resourceId` | `artifact_digest`，Release 只是其业务引用者 |

因此建议新增 Skill 上下文自己的深接口，例如 `SkillArtifactRepository` / `SkillArtifactResolver`；可以复用 MinIO SDK 依赖、HTTP Client 构建、脱敏异常映射等底层设施，但**不要向 `ResourceStorage` 添加 Skill 特例，也不要让 Skill 代码调用聊天资源的 `discard` 语义**。

### 1.3 与现有 ADR 的关系

[`0003-business-artifacts-remain-in-minio.md`](../../adr/0003-business-artifacts-remain-in-minio.md) 规定业务 Artifact 只在 MinIO 保存一份、Observation 只保存引用。Skill Bundle 同样应只在 Skill Artifact Bucket 保存一份，运行记录只保存 `skill_id + release_id + artifact_digest`，不向 Langfuse 或运行表复制 Bundle 字节。这不是对 ADR 的冲突，而是对“业务模块拥有自己的 Artifact”的延伸。

## 2. 推荐的权威来源与数据流

### 2.1 四种数据的权威边界

| 数据 | 权威来源 | 说明 |
| --- | --- | --- |
| User Skill 源文件、正式历史 | Gitee commit/tree/tag | 用于编辑、diff、审计和必要时重建，不作为在线运行读取路径 |
| System Skill 启用清单 | 应用配置 | 配置必须引用固定 Artifact Descriptor，禁止 `latest` |
| 规范化运行字节 | MinIO Skill Artifact Bucket | 内容寻址、不可变、可缓存；System/User 运行时统一消费 |
| Release、Active、Enabled、撤销与绑定 | PostgreSQL | 保存业务状态和 Artifact Descriptor；不复制 Bundle 字节 |

推荐数据流：

```text
User Skill Proposal/Git commit          System Skill 发布输入
              |                                  |
              +----------> Packager/Validator <--+
                               |
                     deterministic Bundle
                     mediaType + size + sha256
                               |
                       create-only PUT MinIO
                               |
                    HEAD + read-back digest verify
                               |
                  PostgreSQL Artifact = AVAILABLE
                               |
                 Active/Enabled -> Runtime Binding
                               |
                digest-keyed local verified cache
                               |
                         top-level Agent
```

该模式对应 OCI 的“先上传 blob，最后登记 manifest/reference”思路：Blob 由 digest 标识；消费者按 digest 拉取并校验。OCI Distribution Specification 要求 Blob 完成上传时提供全对象 digest，并建议消费者验证下载内容。[OCI Distribution Specification](https://github.com/opencontainers/distribution-spec/blob/main/spec.md)

### 2.2 为什么 User Skill 也应同步为 MinIO 运行制品

如果 User Skill 运行时直接读 Gitee tag，会出现这些问题：

- 源码服务故障直接放大为在线 Agent 故障；
- Git 目录不是一个明确的、可校验长度的运行制品；
- tag 可被删除或移动，必须每次额外验证 commit/tree；
- 运行节点缓存的是目录快照，缺少统一 Artifact Descriptor；
- System Skill 和 User Skill 会出现两套解析、缓存与故障语义。

业内 Artifact Registry 模式通过“源码 → 构建制品 → 部署/运行”解耦这些问题。对本项目而言，Gitee 继续是 User Skill 的 source of record，而 MinIO 成为 System/User Skill 的统一 runtime artifact source。这样 Gitee 不可用时只阻断编辑和发布，已发布 Skill 的运行、启停、回滚不应被无条件阻断。

## 3. Bucket、Prefix 与多租户隔离

### 3.1 推荐 Bucket 划分

推荐至少三个业务 Bucket，均为私有：

```text
<resource-bucket>               # 已有聊天图片/视频/附件，不变
<system-skill-artifact-bucket>  # System Skill，不同发布者与运维权限
<user-skill-artifact-bucket>    # 所有 User Skill Artifact，共享规则
```

System/User Skill 分 Bucket 的理由不是文件夹美观，而是以下能力是 Bucket 级或主要以 Bucket 管理：Versioning、默认加密、Object Lock、复制、配额、部分生命周期和审计策略。System Skill 的写入者应是部署/运维流水线，User Skill 的写入者是平台发布服务，两者也需要不同最小权限。

若原型期只能提供一个 Skill Bucket，可使用 `system/` 与 `users/` 前缀隔离，但必须接受二者共享 Versioning、加密、Object Lock 和部分运维策略；不能把前缀误当成完整的安全边界。

### 3.2 推荐 object key

```text
# System Skill
v1/blobs/sha256/{first-2-hex}/{full-sha256}.skill.tar

# User Skill；不跨租户去重
v1/users/{immutable-owner-user-id}/blobs/sha256/{first-2-hex}/{full-sha256}.skill.tar
```

设计要点：

- key 中包含算法名，便于以后迁移 digest 算法；
- `{first-2-hex}` 只是 namespace 分片，不参与身份；
- owner 使用服务端认证身份对应的不可变内部 ID，不使用用户名、邮箱或昵称；
- 不把 `skill_key`、显示名、文件名或版本号作为唯一 object identity；这些业务字段留在 PostgreSQL；
- User Skill 不做跨用户物理去重，避免对象存在性、计费和后续删除语义跨租户耦合；同一用户内相同 digest 可以安全复用；
- 所有 key 由强类型构造器生成，MinIO Adapter 不接受 Controller/Agent 传入的任意 key。

MinIO 官方建议使用 IAM 用户/组和限制到 Bucket/prefix 的 policy 实现多租户，并为应用创建 scope 明确的 service account。[MinIO Multi-Tenancy](https://docs.min.io/aistor/administration/multi-tenancy/)、[MinIO Policy Management](https://docs.min.io/aistor/administration/iam/access/)

### 3.3 凭证分离

至少拆分以下权限主体：

| 主体 | 权限 |
| --- | --- |
| System Skill publisher | 仅 System Skill Bucket 的 create/read/stat；无 Bucket 管理权限 |
| User Skill publisher | 仅 User Skill Bucket 的 create/read/stat；无普通 DeleteObject/DeleteObjectVersion |
| Agent runtime | 两个 Skill Bucket 的 GetObject/HeadObject；完全只读 |
| Reconciler/GC | 只清理确认未引用且超过 grace period 的对象；独立凭证 |
| Storage operator | Versioning、复制、Object Lock、KMS、恢复；不供应用长期使用 |

普通用户不获得 MinIO 凭证；owner 鉴权在业务 API 和 PostgreSQL 完成。即便将来使用 presigned GET，也必须先做 owner/可见性校验。

## 4. Artifact Descriptor 与确定性 Bundle

### 4.1 Descriptor 最小字段

Release 或 System Skill 配置引用的 Descriptor 至少包含：

```text
artifact_media_type
artifact_digest_algorithm   # v1 固定 sha256
artifact_digest             # sha256:<hex>
artifact_size_bytes
artifact_bucket_locator     # 配置 key/逻辑 bucket ID，不把 endpoint 写进业务记录
artifact_object_key
artifact_storage_version_id # nullable；恢复/取证辅助，不是主身份
artifact_schema_version
packager_version
validator_version
source_commit_sha           # User Skill 必填
source_tree_sha             # User Skill 建议保存
created_at
```

OCI Descriptor 将 `mediaType`、`digest`、`size`定义为核心字段；SLSA 也要求校验 provenance 的 subject digest 与实际 artifact 匹配。第一版不必宣称满足某个 SLSA Level，但应保留 `source commit/tree + packager/validator version + artifact digest` 的最小来源链。[OCI Descriptor](https://github.com/opencontainers/image-spec/blob/main/descriptor.md)、[SLSA verifying artifacts](https://slsa.dev/spec/v1.2/verifying-artifacts)

### 4.2 Bundle 格式

第一版建议一个确定性、未压缩 tar Bundle，媒体类型例如：

```text
application/vnd.h-agent.skill.bundle.v1+tar
```

未压缩 tar 对当前单 Skill 10 MiB 上限足够，且避免 gzip 时间戳、压缩器版本与参数导致同一内容产生不同 digest。若以后改用 zstd/zip，必须升级媒体类型/Bundle schema，旧 Release 仍按旧解析器读取。

确定性打包规则至少要规定：

- 路径使用 `/`，按 UTF-8 字节序排序；
- 禁止绝对路径、`..`、空路径段、反斜杠歧义、符号链接、硬链接、设备文件；
- 文件 mode 使用固定 allowlist；uid/gid、用户名、组名、mtime 归零；
- 不写入本机临时路径、扩展属性或 tar 实现私有字段；
- 在 Bundle 内保存规范化 manifest，列出 schema、每个 path、size、SHA-256；manifest 不包含外层 Bundle digest，避免循环依赖；
- 解包前先校验外层 size/digest，再校验 entry 数量、单文件/总解包大小和每个文件 digest，防止 archive bomb 与路径穿越。

Digest 必须针对**最终上传的 Bundle 原始字节**计算，而不是对目录名列表、Git commit SHA、ETag 或解包后的模糊内容计算。

### 4.3 对象 metadata 与 tag

MinIO 对象只保存少量技术 metadata：

```text
Content-Type: application/vnd.h-agent.skill.bundle.v1+tar
x-amz-meta-schema-version: 1
x-amz-meta-sha256: <hex>
x-amz-meta-packager-version: <version>
```

PostgreSQL 仍是业务 metadata 的权威来源。不要把 owner 名称、Skill 显示名、发布说明、Git URL 或鉴权状态只放在 object metadata；metadata 不是 owner 校验边界，修改 metadata 通常也意味着复制/产生新对象版本。Object tags 最多 10 个且可变，更适合生命周期或运维分类，不适合保存 Artifact 身份。[Amazon S3 object metadata](https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingMetadata.html)、[MinIO objects and versioning](https://docs.min.io/aistor/administration/objects-and-versioning/)

## 5. 发布原子性、幂等与完整性

### 5.1 推荐发布协议

1. 锁定 User Skill 的 source commit/tree；System Skill 则锁定一次明确的发布输入。
2. 执行 schema、文件类型、敏感信息和安全规则校验。
3. 按固定 `packager_version` 生成确定性 Bundle，计算 `size + SHA-256`。
4. 以 digest key 写入 MinIO，并使用 `If-None-Match: *` 的 create-only 条件，防止同 key 被覆盖。AWS S3 官方将该条件用于阻止同 key 覆盖，MinIO S3 compatibility 表也声明 PutObject 支持 `If-None-Match`；仍需对当前 MinIO Server + Java SDK 的单次与 multipart 路径做真实 contract test。[Amazon S3 conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)、[MinIO S3 API compatibility](https://docs.min.io/aistor/developers/s3-api-compatibility/)
5. 若返回“已存在”，HEAD 后按 Descriptor 校验 size；由于当前 Skill 很小，建议再完整 GET 并重算 SHA-256后复用，不能只相信 object key 或 ETag。
6. 若写成功，执行 HEAD，并完整 read-back 校验 size/SHA-256。若服务端/SDK支持 SHA-256 checksum header，可同时提交供传输层校验，但不能取代应用保存的 Artifact digest。
7. 只有上述步骤成功后，PostgreSQL 才把 Publication Operation/Artifact 状态推进为 `AVAILABLE`，保存完整 Descriptor。
8. 只有 `AVAILABLE` 且未撤销的 Artifact 才允许成为 Active Release 或 System Skill 运行配置。

Amazon S3 接收带 checksum 的上传时会独立计算并比较，multipart 也支持对象级 checksum；但 ETag 在 multipart 和部分加密方式下不是完整对象摘要。[Amazon S3 upload checksums](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html)

### 5.2 为什么不需要临时 key 再 rename

S3/MinIO 没有文件系统 rename。把对象写到 staging key 后再 COPY 到 final key，会增加一次完整对象操作和新的失败点。对于单 Bundle：

- 未完成 multipart 在 Complete 之前不会成为对象；
- 完成的单 key PUT 对读取者是原子的；
- final key 已由 digest 唯一确定；
- Release 在数据库登记 `AVAILABLE` 前不可被运行时发现。

因此可以直接写 final digest key。若未来一个 Release 包含多个独立 blob，则采用 OCI 的做法：先写所有 digest blob，最后写/登记一个引用它们的 manifest；manifest 是原子可见性边界。

### 5.3 跨 Gitee、MinIO、PostgreSQL 不是分布式事务

发布应继续使用可恢复 Saga/状态机，而不是假设三者原子提交：

```text
SOURCE_FIXED
  -> ARTIFACT_BUILDING
  -> ARTIFACT_UPLOADED
  -> ARTIFACT_VERIFIED
  -> AVAILABLE
  -> RELEASE_RECORDED
```

每一步以 `publication_operation_id + source_tree_sha + artifact_digest` 幂等。典型补偿/恢复：

- MinIO 成功、数据库失败：留下不可运行的孤儿 blob；重试按 digest 复用，或 grace period 后由 GC 删除；
- 数据库 `AVAILABLE`、对象丢失：Reconciler 将其标为 `ARTIFACT_MISSING` 并阻断新绑定，不静默从其他 Release 替代；
- Git 已发布、Artifact 失败：Release 保持不可生效的失败状态，重试同一 source commit 构建；不能修改已发布 Release 内容；
- 重试得到不同 digest：说明打包不确定、packager/输入变化或损坏，必须停止并告警，不能覆盖原 Descriptor。

Bucket Notification 只能用于唤醒 Reconciler/可观测性，不能当作发布提交确认；发布方应以同步 PUT/HEAD/GET 结果推进状态。

## 6. Versioning、Object Lock 与生命周期

### 6.1 推荐默认：Versioning 开启

MinIO Versioning 使同 key 的写入产生新 version，并允许按 version ID 读取历史内容；普通 DELETE 会写入 delete marker，显式删除 version ID 才是不可逆硬删除。它适合作为误操作恢复层。[MinIO Versioning](https://docs.min.io/aistor/administration/objects-and-versioning/versioning/)

本设计仍应遵守：

- product Release `vN` 不映射成 MinIO version ID；
- object key 已包含 SHA-256，正常流程不应覆盖；
- Descriptor 的主身份是 key + size + digest，`storage_version_id` 只作可选恢复/取证；
- 运行时读取后重算 digest，即使“latest”被异常覆盖也会 fail closed；
- 管理员修复异常覆盖时，可从历史 version 找回匹配 digest 的对象，再恢复 digest key。

### 6.2 Object Lock 的采用条件

Object Lock/WORM 需要 Versioning，按对象版本阻止在 retention 到期前被删除。它非常适合监管保留、审计日志或“连存储管理员也不能提前删”的要求，但不是所有 Artifact Registry 的默认必要条件。[MinIO Object Lock](https://docs.min.io/aistor/administration/object-locking-and-immutability/)

本项目已经决定 Release 历史在产品层永久保留，但尚未提出监管 WORM 需求。因此推荐第一版不启用强制 Object Lock，理由是：

- 数据库挂接失败可能产生孤儿 digest blob，WORM 会使其无法清理；
- retention 时长与“永久”产品语义不同，legal hold 又需要额外运维流程；
- delete marker 仍可能隐藏对象，运行时依然要校验 Descriptor；
- Versioning + create-only + 应用账号无 DeleteObjectVersion 已能覆盖主要误操作风险。

若未来要求强防篡改，建议把已发布制品写入单独 WORM Bucket，或只对确认发布的对象版本设置 retention；staging/孤儿必须留在非 WORM 区域，并记录 retention mode/until 供审计。

### 6.3 生命周期策略

由于当前产品约束是 Release 永久保留：

- **禁止**对 released blob prefix 设置按年龄删除、noncurrent expiration 或“只保留最近 N 版”；
- 已撤销、已归档 Release 仍引用原 Artifact，不能触发生命周期删除；
- 只允许清理数据库中从未被任何 Release/System Skill 配置引用、且超过 grace period 的 orphan；
- multipart 失败必须主动 abort，并配置当前 MinIO 版本支持的 stale multipart 清理机制；MinIO 官方说明服务端有独立的 stale upload expiry/cleanup interval，S3 标准也建议用 `AbortIncompleteMultipartUpload`，但 MinIO compatibility 文档提示 `PutBucketLifecycle` 对该 action 存在兼容差异，所以必须通过本环境 contract test 锁定行为。[MinIO stale multipart cleanup](https://docs.min.io/aistor/operations/troubleshoot-system-path-growth/)、[Amazon S3 incomplete multipart lifecycle](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpu-abort-incomplete-mpu-lifecycle-config.html)、[MinIO S3 compatibility](https://docs.min.io/aistor/developers/s3-api-compatibility/)

若未来改变“永久 Release”政策，应先在 PostgreSQL 做引用可达性分析，再由受控 GC 删除；不能让纯时间 ILM 猜测业务引用。

## 7. 运行读取、本地缓存与故障语义

### 7.1 缓存格式

运行缓存是 MinIO Artifact 的可丢弃派生物，不是新的权威副本。推荐：

```text
cache/
  sha256/{full-digest}.bundle
  extracted/sha256/{full-digest}/...
```

下载流程：

1. 以 Descriptor digest 获得进程内/跨进程 single-flight 锁；
2. 下载到同一文件系统的随机 `.part` 文件；
3. 边下载边限制 size 并计算 SHA-256；
4. size/digest 不匹配立即删除 `.part`、告警并将 Artifact 视为不可用；
5. 校验通过后 fsync（按可靠性要求）并原子 rename 到 digest cache key；
6. 解包到随机临时目录，完成 entry 级校验后写 `READY` 标记并原子切换；
7. LRU/容量淘汰只能删除没有被当前 Runtime Snapshot pin 的 digest。

同一个 digest 的字节永远不改变，因此可使用长 TTL；更新通过新 digest key 实现，不需要“刷新同名缓存”。这与 OCI blob/CDN 使用内容版本化名称的通用方式一致。

### 7.2 故障矩阵

| 故障 | 推荐行为 |
| --- | --- |
| Gitee 不可用 | 禁止 Proposal 保存/发布/需要重读 source 的校验；已 `AVAILABLE` 的 MinIO Artifact 继续运行。停用、撤销始终允许；已存在 Release 的启用/回滚不应因 Gitee 单独故障而阻断 |
| MinIO 不可用，精确 digest cache hit | 允许新请求绑定该已验证 Artifact；仍需 PostgreSQL 确认 Release 未撤销、Skill 已启用 |
| MinIO 不可用，cache miss | 该 Skill 绑定失败；不回退旧 Release、Git tag、classpath 或同 key System Skill |
| PostgreSQL 不可用 | 无法确认 owner、Enabled、Active、撤销状态，禁止创建新 Runtime Snapshot；已开始 run 按固定快照策略完成或由更高层安全策略终止 |
| Artifact digest/size 不匹配 | 视为完整性事件，隔离缓存、标记 Artifact 异常、阻断运行并告警；禁止自动接受新的 digest |
| System Skill 配置引用缺失对象 | 精确 cache hit 可运行；否则该 System Skill 不可用。配置不得静默改读“最新”或旧 digest |

激活新 Release 的业内 rollout 思路是“先确认制品可取得，再移动运行指针”。因此建议 `set-active` 前至少完成 Artifact `AVAILABLE` 校验；多节点部署还应预热所需节点或确认共享 MinIO 可用，不能只在一个节点 cache hit 就假设全局可运行。

### 7.3 撤销与缓存

Artifact 的不可变与 Release 的可撤销并不冲突：撤销只改变 PostgreSQL 中“是否允许新绑定”的控制状态，不删除 Bundle。运行时每次创建 Snapshot 前检查 Release 状态；缓存即使仍有字节也不能绕过撤销。是否中止已经开始的 run 是运行策略问题，但不能通过删除 MinIO 对象来传播撤销。

## 8. Presigned URL、传输与加密

### 8.1 Presigned URL

Agent 运行时和 Packager 都是服务端组件，应该使用独立最小权限 service account 通过 MinIO SDK 访问，不使用 presigned URL。

未来如提供用户下载：

- API 先根据认证用户与 PostgreSQL 做 owner/可见性校验；
- 只签发 GET，不提供绕过校验链的 presigned PUT；
- TTL 建议分钟级，并固定 bucket/key；支持 version ID 时也固定 version ID；
- URL 当作临时凭证，禁止写日志、Trace、Referer、数据库或错误正文；
- 设置安全的 Content-Disposition，文件名不能来自未清洗输入；
- 业务要求即时撤销或逐字节审计时，通过应用 API 代理下载，而不是 presigned URL。

AWS 明确把 presigned URL 定义为 bearer token；MinIO SDK 的 presigned GET 最大有效期可达 7 天，但“支持很长”不代表业务应该使用很长 TTL。[Amazon S3 presigned URLs](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)、[MinIO Go SDK presigned operations](https://docs.min.io/aistor/developers/sdk/go/api/)

### 8.2 加密

- 生产 MinIO endpoint 必须使用 HTTPS/TLS；开发环境可保留明确标记的 HTTP endpoint，但不能照搬到生产。
- Artifact Bucket 启用默认 server-side encryption；MinIO 官方推荐新部署使用 SSE-KMS，可按 System/User Bucket 使用不同 key/policy。
- 应用不持有或传输每对象 SSE-C 密钥；SSE-C 要求每次请求携带客户密钥并增加恢复复杂度，不适合当前原型。
- KMS、Bucket policy、复制目标和恢复环境必须联合演练；有加密的复制目标必须能解密或按目标配置重新加密。

MinIO 将 SSE-KMS列为推荐方式；AWS 也建议同时保护传输中和静态数据，并通过 TLS 强制策略拒绝明文访问。[MinIO SSE](https://docs.min.io/aistor/installation/linux/server-side-encryption/)、[Amazon S3 encryption](https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingEncryption.html)、[Amazon S3 TLS](https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingEncryptionInTransit.html)

## 9. 备份、复制与恢复

### 9.1 恢复目标

一个可恢复时间点至少需要：

- PostgreSQL：Skill、Release、Artifact Descriptor、Active/Enabled/Revoked、Publication Operation、Runtime Binding；
- Gitee：完整 bare mirror、branch、commit、tree、tag；
- MinIO：System/User Skill Artifact Bucket 中全部被 Descriptor 引用的对象及必要版本/metadata；
- 配置：System Skill Descriptor 清单、Bucket/KMS/权限/复制配置的备份；
- 恢复清单：DB watermark、Gitee `master` SHA/tag 集合、Artifact `(bucket,key,size,digest)` inventory。

由于对象不可变，备份的关键不在于瞬间冻结所有系统，而在于确保恢复的 MinIO 集合是恢复数据库所引用 Artifact 的**超集**。多出来的孤儿对象安全；数据库引用一个缺失对象则必须阻断该 Release/System Skill。

### 9.2 复制选择

- MinIO Site Replication：面向整站 BC/DR，并同步更完整的配置和对象；
- Bucket Replication：只复制 Skill Artifact Bucket，适合独立故障域的主动/被动副本；
- `mc mirror`：只复制当前对象，不保留 MinIO version history 和大部分 metadata；仅当恢复契约只依赖 content-addressed key + digest、且明确接受丢失 version ID 时使用。

如果数据库把 `storage_version_id` 当主身份，`mc mirror` 恢复后将产生不可移植问题；这也是本调研建议将 key + digest 作为主身份、version ID 仅作辅助字段的原因。MinIO 官方明确区分 replication 与 mirror 的版本保留能力。[MinIO Replication](https://docs.min.io/aistor/administration/replication/)、[MinIO `mc mirror`](https://docs.min.io/aistor/reference/cli/mc-mirror/)

复制不能单独替代备份：错误删除或配置变更可能同步到副本。生产至少需要独立凭证/故障域的副本或备份、Versioning，以及定期恢复演练。

### 9.3 恢复验收

恢复后、开放流量前执行：

1. 遍历所有未删除的 System Skill 配置和全部 User Skill Release Descriptor；
2. HEAD 检查对象存在与 size；对当前 Active/Enabled 与 System Skill 做完整 GET + SHA-256 校验；历史对象可分批后台校验；
3. 验证 User Skill `source_commit_sha/source_tree_sha` 在 Gitee mirror 中存在；
4. 检查 Artifact Bucket Versioning、加密、IAM、复制、Object Lock（若启用）与预期一致；
5. 任一 Active Artifact 缺失或 digest 不匹配时保持运行入口关闭，不能自动选择另一个历史版本；
6. 执行实际 Runtime Snapshot/缓存装载 smoke test，再恢复流量。

## 10. 与现有 Skill 设计的冲突评估

| 现有设计/TODO | 评估 | 业内对齐建议 |
| --- | --- | --- |
| System Skill 存 MinIO，User Skill 是否同步 MinIO 未决 | 两套运行源会造成两套缓存、故障和完整性协议 | System/User 统一发布成 MinIO Artifact；Gitee 只作 User Skill source of record |
| Gitee 不可用时版本切换、启停、归档全部暂停 | 对源码依赖过强；停用/撤销属于降低风险操作，不应受源码服务故障阻断 | Gitee 故障只阻断编辑/发布；已 `AVAILABLE` Artifact 的启用、回滚可继续，停用/撤销始终允许 |
| System Skill 配置引用 MinIO 对象，但版本/digest 未定 | 若按可变 key 或 latest 读取，无法得到可复现 Runtime Snapshot | 配置直接内嵌 `mediaType + size + sha256 + object key`，变更通过部署新 Descriptor |
| User Release 已声明不可变，但运行文件只在 Git | Git tag 不等同 Artifact Registry 的不可变制品 | Release 同时固定 source commit/tree 与 Artifact Descriptor；只有 Artifact verified 才可生效 |
| 已发布 Release 永久保留 | 与按年龄删除 Artifact 的 ILM 冲突 | released prefix 不设置 expiration；只清 orphan/incomplete multipart |
| 现有 MinIO `ResourceStorage` 可用 | 接口的随机 key、无 digest、补偿删除不符合 Artifact 语义 | 新建 Skill Artifact 深模块；复用底层 SDK/网络设施而不是复用聊天资源业务接口 |
| MinIO 故障降级未定 | 若自由回退会破坏版本确定性 | 仅精确 digest 的已验证 cache hit 可运行；miss fail closed，绝不换版本 |
| MinIO 备份未定 | 只备份 PG+Git 无法保证 System Skill 和运行字节恢复 | MinIO Artifact 纳入联合 inventory、复制/备份与恢复校验 |
| Object Lock 未定 | 把产品“永久历史”直接等同 WORM 会制造孤儿无法清理等运维问题 | v1 用内容寻址 + create-only + Versioning + 权限；监管/防管理员删除时再采用独立 WORM 区域 |

## 11. 建议写入 Skill 主设计的决策

1. MinIO 是 System/User Skill 的统一 Runtime Artifact Store；Gitee 不参与已发布 Skill 在线读取。
2. Skill Artifact 使用专用 Bucket；System 与 User 优先分 Bucket，不复用资源 Bucket。
3. Artifact Descriptor 固定采用 `mediaType + size + sha256 digest + object key`；MinIO version ID 可选、非主身份。
4. Bundle 是确定性单对象；v1 明确规范化 tar schema、路径与解包安全限制。
5. 发布采用 create-only digest PUT → HEAD/GET SHA-256 校验 → DB `AVAILABLE`；跨系统使用幂等 Saga/Reconciler。
6. Artifact Bucket 开启 Versioning；v1 不默认启用 Object Lock。应用发布/运行账号没有普通删除权限。
7. Release 永久保留期间，不对 released blob 配置过期 ILM；只治理孤儿和未完成 multipart。
8. Runtime Cache 按 digest 存储并验证；MinIO 故障时仅精确 cache hit 可继续，cache miss 失败关闭。
9. Gitee 故障只阻断 authoring/publish，不阻断停用/撤销，也不应阻断已经验证的 Release 回滚。
10. 生产强制 TLS + Bucket 默认 SSE-KMS；presigned URL 仅作为未来 UI 短期下载选项。
11. 备份同时覆盖 PG、Gitee、MinIO 和 System Skill 配置，恢复以 Artifact inventory 与 digest 校验验收。
12. 现有 `ResourceStorage` 保持聊天资源专用；Skill 新建独立 Artifact Repository/Resolver seam。

## 12. 实施前必须用当前 MinIO 环境验证的兼容契约

以下属于 S3/MinIO 版本和 SDK 组合的实现事实，不能只靠文档假设：

- MinIO Java SDK `9.0.1` 如何在 `PutObject` 的单次与 multipart 完成路径传递 `If-None-Match: *`；并发相同 digest 只允许一个 create，其余得到稳定 precondition 错误；
- 服务端对 SHA-256 checksum header/trailer 的支持，以及 SDK 返回/HEAD 暴露的 checksum 字段；
- Versioning 开启后的 put/delete marker、显式 version GET 与复制恢复行为；
- 当前服务端支持的 Object Lock、retention 和已有 Bucket 启用限制；
- incomplete multipart 的服务端自动清理和 Bucket lifecycle 兼容行为；
- SSE-KMS 下 multipart、HEAD、Range、复制、replication 与恢复；
- prefix policy 是否拒绝跨前缀读写、DeleteObject/DeleteObjectVersion、Bucket 管理；
- Bucket/Site Replication 是否完整保留本设计需要的 version/metadata/checksum；
- MinIO 故障、超时、部分读取和 digest mismatch 的稳定错误映射；
- 本地 cache 的并发 single-flight、半文件清理、原子 rename、容量淘汰和重启恢复。

这些应成为新的 `SkillArtifactRepository` 真实 contract test，不应混入现有聊天资源的 7 个 contract test。

## 参考资料（全部为一手来源）

- [OCI Image Specification：Content Descriptor](https://github.com/opencontainers/image-spec/blob/main/descriptor.md)
- [OCI Distribution Specification](https://github.com/opencontainers/distribution-spec/blob/main/spec.md)
- [SLSA v1.2：Verifying artifacts](https://slsa.dev/spec/v1.2/verifying-artifacts)
- [Amazon S3：Data consistency model](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html)
- [Amazon S3：Upload checksums and ETag](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html)
- [Amazon S3：Conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)
- [Amazon S3：Bucket patterns](https://docs.aws.amazon.com/AmazonS3/latest/userguide/common-bucket-patterns.html)
- [Amazon S3：Presigned URL](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
- [Amazon S3：Encryption](https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingEncryption.html)
- [Amazon S3：TLS](https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingEncryptionInTransit.html)
- [Amazon S3：Incomplete multipart lifecycle](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpu-abort-incomplete-mpu-lifecycle-config.html)
- [MinIO：Object Versioning](https://docs.min.io/aistor/administration/objects-and-versioning/versioning/)
- [MinIO：Object Locking and Immutability](https://docs.min.io/aistor/administration/object-locking-and-immutability/)
- [MinIO：Multi-Tenancy](https://docs.min.io/aistor/administration/multi-tenancy/)
- [MinIO：Policy Management](https://docs.min.io/aistor/administration/iam/access/)
- [MinIO：Server-Side Encryption](https://docs.min.io/aistor/installation/linux/server-side-encryption/)
- [MinIO：S3 API Compatibility](https://docs.min.io/aistor/developers/s3-api-compatibility/)
- [MinIO：Object Lifecycle Management](https://docs.min.io/aistor/administration/object-lifecycle-management/)
- [MinIO：Replication](https://docs.min.io/aistor/administration/replication/)
- [MinIO：Recover after site failure](https://docs.min.io/aistor/operations/failure-and-recovery/recover-after-site-failure/)
- [MinIO：`mc mirror`](https://docs.min.io/aistor/reference/cli/mc-mirror/)
- [MinIO：Strict consistency](https://blog.min.io/strict-consistency-hard-requirement-for-primary-storage/)
