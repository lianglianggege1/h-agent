---
status: accepted
date: 2026-08-27
last_updated: 2026-08-28
---

# H Agent 统一 Langfuse Trace 详细设计

## 1. 设计结论

H Agent 使用 OpenTelemetry 建立统一 Agent Trace，并通过 OTLP/HTTP 直接发送到自托管 Langfuse。LangChain4j、AgentScope、A2A 和 MCP 共享同一语义模型，但分别在各自真实执行接缝完成适配；业务编排不创建、修改或传播原始 Span。

本设计从零建立，不兼容旧的 `AgentRunTelemetryService`、Agent Turn、Collector、内容黑白名单、Trace Artifact Store、Trace 可用性验证和观测对账设计。历史代码和数据不构成兼容约束。

架构优先级固定为：

1. 业务正确性。
2. 业务响应延迟、取消和背压语义。
3. Trace 结构与内容完整度。
4. Langfuse 中的媒体预览效果。

观测是被动、非权威、尽力交付的数据平面。任何观测故障都不能改变业务行为。

## 2. 目标与非目标

### 2.1 目标

1. 一次根 Agent 执行在 Langfuse 中形成一棵可理解的因果树。
2. LangChain4j 与 AgentScope 产生可比较的 Agent、Generation、Tool 等 Observation。
3. A2A 和 MCP 使用标准传播机制跨进程保持同一 Trace。
4. 支持同步、异步、Reactor、流式、并行工具和动态子 Agent。
5. 产品响应完成后仍运行的 AgentScope 维护任务拥有正确而独立的生命周期。
6. 模型、工具和检索内容按统一结构显式采集，而不是任意对象字符串化。
7. 图片、视频、音频和文件只在业务 MinIO 中保存一份，Trace 记录语义引用。
8. Langfuse 未配置、不可用、拥塞或内容处理失败时业务保持原样。
9. 配置、属性和测试能够支撑后续升级 LangChain4j、AgentScope、A2A SDK 和 OTel。
10. Agent Trace、运行指标与结构化日志各自使用适合的信号，不把 Langfuse 当作通用 Metrics 后端。

### 2.2 非目标

1. 不把 Trace 作为审计日志或业务恢复依据。
2. 不建立观测专用 Agent Turn、幂等键、状态表、Outbox 或 Span Journal。
3. 不验证某个 trace ID 是否最终已在 Langfuse 可查询。
4. 不因 Trace 缺失改变 Agent Run 状态或前端结果。
5. 不采集 Spring、SQL、Redis 和所有 HTTP 自动埋点。
6. 不在第一版引入 OTel Collector、尾采样或可靠磁盘队列。
7. 不复制业务 MinIO 对象到 Langfuse Media。
8. 不为观测执行 OCR、文档解析、转码、抽帧或音频转写。
9. 不承诺观测数据百分之百交付。
10. 不通过 Langfuse Observation 计数替代 MinIO 的不采样运行指标和告警。

## 3. 当前项目契合度与一次性改造

项目目前有两个独立 Maven 应用：

- `backend`：产品聊天、LangChain4j Agent、AgentScope Harness、A2A/MCP 客户端和业务资源。
- `other-agents`：A2A Agent 服务端和 MCP 服务端。

两者使用相同 Java、LangChain4j 和 OTel 版本，适合增加一个共享 Maven Module。AgentScope 只存在于 `backend`，不应成为 `other-agents` 的传递依赖。

当前代码需要一次性替换：

| 当前实现 | 新实现 |
|---|---|
| `AgentRunTelemetryService` 暴露 `Span` | `AgentExecutionObservation` 暴露不透明 `ObservationContext` |
| `LangfuseTelemetryConfig` 直接导出所有 SDK Span | 只导出 H Agent instrumentation scope |
| Trace 在 Agent Run 创建前开始 | 先创建 Run，再开始 Trace 并尽力回写 trace ID |
| LangChain4j Agent 只挂产品 `AgentStepListener` | 组合产品监听器与观测监听器 |
| ChatModel 没有统一 Listener | 普通与 Streaming ChatModel 都集中安装 Listener |
| AgentScope 动态 USER 子 Agent 漏装观测 middleware | 所有 Agent 通过统一 Installer 构建 |
| 子 Agent 新建空 `RuntimeContext` | 从父 `RuntimeContext` 派生 |
| `AgenticServices.a2aBuilder` 隐藏 Transport 配置 | 项目自有 A2A Remote Agent Module |
| MCP Transport 无动态传播 | 使用每请求 `McpHeadersSupplier` |
| MinIO-only `ResourceStorage` 与事务补偿已落地 | 复用现有业务资源结果，只增加无 I/O 的 Artifact 映射 |
| `ResourceStorageMetrics` 仅进程内 LongAdder、不可被运维系统采集 | 用 Micrometer Timer/Counter 替换并由 Prometheus 抓取 |
| Trace Artifact Store | 删除；业务 MinIO 是唯一二进制真相源 |

旧测试中围绕 `AgentRunTelemetryService.TelemetryRun` 的 Mock 直接删除并按新 Interface 重写，不进行双写或兼容层叠加。

## 4. Module 与依赖结构

目标 Maven 结构：

```text
h-agent/
├── pom.xml
├── agent-observability/
│   ├── lifecycle/
│   ├── semantic/
│   ├── langfuse/
│   ├── propagation/
│   └── langchain4j/
├── backend/
│   └── .../observability/
│       ├── agentscope/
│       ├── a2a/client/
│       └── mcp/client/
└── other-agents/
    └── .../observability/
        ├── a2a/server/
        └── mcp/server/
```

### 4.1 `agent-observability`

共享 Module 负责：

- Trace 生命周期 Interface。
- `SemanticContent` 和 `ArtifactReference`。
- OTel SDK、Sampler、SpanProcessor 和 Langfuse OTLP 配置。
- W3C Propagator 和受限 Baggage schema。
- LangChain4j Agent、Model、Tool、Retriever、Embedding、Guardrail Adapter。
- No-op 和测试实现。

该 Module 不依赖 AgentScope、A2A Server、Spring AI MCP Server 或产品持久化实现。

### 4.2 应用本地 Adapter

`backend` 本地 Adapter 负责：

- AgentScope Middleware、Toolkit 装饰和 RuntimeContext 传播。
- Harness Primary/Maintenance 生命周期协调。
- A2A Client 与 LangChain4j 工作流适配。
- MCP Client ToolExecutor 与动态 Header 适配。
- 业务资源到 `ArtifactReference` 的映射。

`other-agents` 本地 Adapter 负责：

- A2A HTTP 服务端提取和异步响应生命周期。
- MCP WebFlux 服务端提取、Reactor Context bridge 和 Tool Callback 观测。

MinIO 运行指标属于 `backend` 资源存储 Module，不进入 `agent-observability`。共享 Trace Module 不依赖 Micrometer、Prometheus、MinIO SDK 或 `ResourceStorageMetrics`；这避免 Agent Trace 与基础设施监控互相控制生命周期。

### 4.3 Maven 构建契约

仓库根新增聚合 `pom.xml`，只负责聚合 Module 和锁定共享版本；`backend` 与 `other-agents` 继续是可独立启动的 Spring Boot 应用。三个 Module 使用同一 Java、LangChain4j、OTel 版本，AgentScope 依赖只留在 `backend`。

标准构建入口是：

```text
mvn -pl backend -am verify
mvn -pl other-agents -am verify
```

`-am` 保证先构建 `agent-observability`，不要求开发者把快照包手工安装到本地仓库。框架升级必须同时运行共享契约测试和两个应用的 Adapter 集成测试。

## 5. 核心 Interface

### 5.1 根执行生命周期

```java
public interface AgentExecutionObservation extends AutoCloseable {

    String traceId();

    ObservationContext observationContext();

    void succeed(SemanticContent output);

    void fail(Throwable error);

    void cancel(String reason);
}
```

```java
public interface AgentObservationLifecycle {

    AgentExecutionObservation start(AgentExecutionStart start);
}
```

`ObservationContext` 是不透明、不可序列化的执行期载体。业务代码只负责把它随执行命令传递给框架 Adapter，不能读取 Span、TraceFlags 或 Baggage。

Interface 约束：

1. `start` 永远返回对象；关闭或配置错误时返回 no-op。
2. `succeed`、`fail`、`cancel` 只有第一个终态生效。
3. 终态方法不抛出观测异常。
4. `close` 只用于遗漏终态时安全结束，不替代显式业务终态。
5. `traceId` 可能为空；业务必须允许为空。

### 5.2 语义内容

```java
public record SemanticContent(
        List<SemanticMessage> messages,
        List<SemanticBlock> blocks,
        ContentCaptureState captureState
) {}
```

`SemanticBlock` 允许：

- `TextBlock`
- `ThinkingBlock`
- `JsonBlock`
- `ToolCallBlock`
- `ToolResultBlock`
- `ArtifactReferenceBlock`
- `ProviderExtensionBlock`

禁止：

- 对任意框架对象调用 `toString()` 作为输入输出。
- 把完整 Java 类型名当作 schema。
- 把多个工具调用合并为一个字符串。
- 把 Base64、InputStream、byte[] 写入 Span attribute。
- 把完整会话快照复制到每个 Observation。

## 6. Observation 语义模型

统一语义类型：

| `h.kind` | 含义 | Langfuse 类型 |
|---|---|---|
| `agent` | Agent 决策或执行 | span |
| `generation` | 模型生成调用 | generation |
| `tool` | 单个工具真实执行 | span |
| `retriever` | 检索调用 | span |
| `embedding` | 向量生成 | span |
| `guardrail` | 输入或输出约束检查 | span/event |
| `workflow` | sequence、loop、router 等编排 | span |
| `remote_call` | A2A/MCP 跨进程逻辑调用 | span |
| `persistence` | 对定位问题有价值的 Agent 结果持久化 | span |
| `artifact_capture` | 可选临时媒体复制结果 | event/span |

### 6.1 典型 Trace

```text
agent.run
├── agent general-assistant
│   ├── generation anthropic
│   ├── tool search
│   │   └── remote_call mcp.tools/call
│   ├── agent delegated-writer
│   │   ├── generation anthropic
│   │   └── tool file_delivery
│   └── remote_call a2a.message/send
│       └── [remote] agent creative-writer
└── persistence assistant-message
```

### 6.2 唯一所有者

每个逻辑操作只有一个 Span 所有者：

| 操作 | 所有者 |
|---|---|
| 根 Agent Run | 产品入口 |
| LangChain4j Agent | AgentListener |
| LangChain4j Model | ChatModelListener |
| LangChain4j Tool | 真实 ToolExecutor 装饰器 |
| AgentScope Agent/Model | `HAgentObservabilityMiddleware` |
| AgentScope Tool | 真实 `AgentTool.callAsync` 装饰器 |
| A2A Client/Server | A2A Adapter |
| MCP Client/Server | MCP Adapter |
| Retriever/Embedding/Guardrail | 各框架正式 Listener |

高层 Listener 可以补充属性，但不能为同一真实操作再建 Span。

## 7. 根执行与终态

### 7.1 创建顺序

根聊天执行固定顺序：

1. 持久化用户消息。
2. 创建 `Agent Run(trace_id = null)`。
3. 使用 `rootRunId` 开始 `AgentExecutionObservation`。
4. 尽力把 32 位 OTel trace ID 回写 Agent Run。
5. 执行 Agent。
6. 持久化最终业务消息或失败事实。
7. 完成 Agent Run。
8. 调用 Observation 终态。

回写 trace ID 失败只记录受限日志，Agent 继续执行。

### 7.2 状态映射

| 业务结果 | OTel Status | `h.outcome` |
|---|---|---|
| 成功 | `UNSET` | `success` |
| 未处理异常 | `ERROR` | `failure` |
| 超时 | `ERROR` | `timeout` |
| 取消 | `UNSET` | `cancelled` |
| 子步骤失败后恢复 | 子 `ERROR`、根 `UNSET` | 根 `success` |

业务异常必须原样向上传播；观测 Adapter 不替换异常类型或消息。

### 7.3 Primary 与 Maintenance

Primary Trace 在产品结果完成持久化并且 Agent Run 完成后结束。AgentScope 在此之后执行的记忆提取、整理或 Hook 不延长 Primary Trace。

Harness 执行持有类型化 `ExecutionObservationCarrier`：

```text
PRIMARY -> MAINTENANCE -> CLOSED
```

规则：

1. 产品结果提交后原子地结束 Primary Trace 并切换阶段。
2. 切换后第一项新 Observation 才延迟创建 Maintenance Trace。
3. Maintenance Trace 通过 OTel Link 指向 Primary Trace。
4. Maintenance Trace 沿用产品 Session、rootRunId 和环境标签。
5. 原始 AgentScope Flux 完成、失败或取消时结束 Maintenance Trace。
6. 没有后置工作时不创建 Maintenance Trace。
7. 切换前已开始的 Observation 保持原 Trace，不迁移父级。
8. 维护失败只记录 Maintenance Trace，不修改已成功的 Agent Run 或消息。

阶段协调必须装饰 AgentScope 返回的原始 Publisher，并在产品订阅之前建立；不得依赖 `HarnessAgentExecutor.onEvent`，因为该投影在 `responseTerminal` 后会主动忽略事件。协调器不额外 `subscribe()`，产品的订阅、取消和背压仍是唯一驱动力。

## 8. 采样与内容采集

### 8.1 三个独立维度

必须区分：

1. Observation 覆盖：哪些语义操作有埋点。
2. Trace 采样：哪些完整 Trace 被记录与发送。
3. 内容采集：已采样 Observation 是否包含原始输入输出。

本设计不使用“无条件采集”。

### 8.2 采样

使用 `ParentBased(root = RatioBased)`：

- 本地和当前低流量部署显式配置 `root-ratio=1.0`。
- 子 Span 遵循父采样决定。
- A2A/MCP 通过 W3C sampled flag 保持跨服务一致。
- 采样率必须可配置，不能写死在代码中。
- 第一版不做尾采样和错误优先保留。

已采样 Trace 应具有完整观测覆盖；未采样 Trace 不发送任何子 Observation。

### 8.3 内容档位

```java
public enum ContentCaptureMode {
    METADATA_ONLY,
    STRUCTURED,
    STRUCTURED_WITH_MEDIA
}
```

`METADATA_ONLY`：

- 记录类型、名称、模型、token、耗时、状态和关联 ID。
- 不记录指令、消息正文、思考、工具参数和工具结果正文。

`STRUCTURED`：

- 显式记录模型实际收到和返回的结构化语义内容。
- 记录工具实际参数与结果、检索 query/结果和 guardrail 输入输出。
- Artifact 始终以引用表示。

`STRUCTURED_WITH_MEDIA`：

- 在 `STRUCTURED` 基础上允许异步复制没有业务资源身份的临时媒体。
- 不复制业务 MinIO Artifact。

H Agent 第一版推荐：

```text
sampling.root-ratio = 1.0
content.mode = STRUCTURED
artifacts.mode = REFERENCE_FIRST
```

这是部署的显式选择，不是架构上的无条件承诺。

### 8.4 无黑白名单

不实现 Agent、Tool、字段路径或 MIME 黑白名单。新增 Agent 和 Tool 自动获得统一观测结构。

内容是否存在只由全局 `ContentCaptureMode` 和技术表示限制决定。未来出现真实合规需求时另行设计，不预留复杂规则引擎。

### 8.5 技术限额

建议初始值：

| 限额 | 默认值 |
|---|---:|
| 单内容块内联 | 128 KiB |
| 单 Observation 内容总量 | 256 KiB |
| 结构深度 | 16 |
| 集合元素数 | 512 |
| 临时媒体异步复制上限 | 10 MiB |

内容状态：

- `INLINE`
- `REFERENCE`
- `MIRROR_QUEUED`
- `MIRRORED`
- `TRUNCATED_BY_LIMIT`
- `SOURCE_UNAVAILABLE`
- `DROPPED_OVERLOAD`
- `CAPTURE_ERROR`

达到技术限额时保留 schema、原始大小、已有业务元数据、预览和明确状态。不得因为超限回退为 Base64 attribute，也不得为了补齐 Trace 临时计算内容 hash。

## 9. Artifact 与已落地 MinIO Module

### 9.1 当前代码事实与所有权

MinIO 已经是唯一生产 `ResourceStorage` Adapter，不再是本设计的待实现项。现有 Module 职责如下：

| Module/存储 | 已有职责 | Langfuse 是否参与 |
|---|---|---|
| `ResourceStorage` | `save/open/discard` 字节能力与稳定错误语义 | 否 |
| `ResourceWriteCoordinator` | 对象先写、PostgreSQL 后挂接、回滚时 best-effort `discard` | 否 |
| PostgreSQL | resourceId、owner、类型/角色、MIME、文件名、尺寸、应用 URL、storage key | 只消费已有业务视图 |
| MinIO | 私有业务二进制与少量技术 metadata | 否 |
| Langfuse | Agent 执行时的 Artifact 语义引用 | 不拥有二进制或生命周期 |

MinIO 是资源业务的依赖，不是观测依赖。MinIO 故障可以按现有 `ResourceStorageException` 语义使资源操作失败；Langfuse 故障不得进一步改变该结果。

### 9.2 业务资源引用不是对象身份

一次 `ResourceStorage.save` 生成的 UUID 同时成为初始业务 resourceId 和 object key 尾段。但 `ChatMessageResourceBinder` 在资源复用时允许创建新的消息资源行，并继续指向同一个 `storageKey`。因此：

- resourceId 表示某次业务资源引用身份，不是内容 hash。
- 多个 resourceId 可以指向同一个 MinIO 对象。
- `storageKey` 是基础设施定位信息，不是 Observation 身份。
- 不按 `storageKey`、文件名、MIME + size 或 MinIO ETag 在 Trace 中去重。
- 有明确 `sourceResourceId` 时可以记录业务血缘，但不得通过查询 MinIO 推断血缘。

Observation 记录当前操作实际消费或产生的 resourceId。它不试图创造一个当前业务模型中不存在的“全局 Artifact ID”。

### 9.3 ArtifactReference schema

```java
public record ArtifactReference(
        String resourceId,
        String sourceResourceId,
        ArtifactKind kind,
        ArtifactUse use,
        String businessRole,
        String mimeType,
        Long byteSize,
        Integer width,
        Integer height,
        String fileName,
        String applicationViewUrl
) {}
```

字段语义：

| 字段 | 来源/规则 |
|---|---|
| `resourceId` | `ChatMessageResourceDto.id` 或 `GeneratedArtifact.resourceId` |
| `sourceResourceId` | 只接受业务已经给出的复用/来源血缘，可空 |
| `kind` | 由资源 `type` 归一为 IMAGE/VIDEO/AUDIO/FILE/DOCUMENT |
| `use` | 由 Observation 接缝赋值，如 MODEL_INPUT、MODEL_OUTPUT、TOOL_INPUT、TOOL_OUTPUT、REMOTE_INPUT、REMOTE_OUTPUT、SOURCE |
| `businessRole` | 已有产品分类，如 ATTACHMENT、GENERATED；不可替代 `use` |
| MIME/size/dimensions/fileName | 只使用业务结果中已有值 |
| `applicationViewUrl` | 已有受鉴权应用 URL，可空；它不是 MinIO URL，也不是 Langfuse Media URL |

当前实现明确不计算自定义 SHA-256，也不能把 multipart ETag 当成内容 hash，因此 schema 不包含 `sha256`。当前没有类型化 duration 字段，不能从任意 `metadata_json` 猜测；未来确有需要时通过 schema version 增加显式字段。

### 9.4 映射接缝

`backend` 增加具体的 `BusinessArtifactReferenceMapper`，但不为它额外抽象一个只有单一实现的 Port。共享 `agent-observability` Module 只拥有 `ArtifactReference` 值类型；应用本地 Mapper 负责从已经在当前调用链中的业务对象映射：

```text
ChatMessageResourceDto + ArtifactUse -> ArtifactReference
GeneratedArtifact + ArtifactUse + applicationViewUrl -> ArtifactReference
StoredResource + 已提交的业务挂接结果 -> ArtifactReference
```

Mapper 必须是纯映射：

- 不依赖 `ResourceStorage`、MinIO SDK、Mapper/Repository 或 HTTP Client。
- 不调用 `open/stat` 验证对象是否存在。
- 不解析任意 `metadata_json`；只接受调用方显式提供的受控血缘字段。
- 不输出 `storageType`、`storageKey`、bucket、endpoint、凭据或预签名 URL。
- 映射失败返回受限的 `CAPTURE_ERROR` 内容状态，不抛向业务。

### 9.5 写入终态与 Observation 时机

Artifact 不是在 `ResourceStorage.save` 返回时就成为可观测业务输出，而是在业务挂接成功后才成立：

```text
ResourceWriteCoordinator.saveAndAttach
  -> MinIO save
  -> PostgreSQL attachment
  -> transaction commit/业务成功结果
  -> ArtifactReference 进入拥有它的 Tool/Workflow 输出
```

规则：

1. 不装饰 `ResourceStorage.save/open/discard` 生成 Langfuse Observation。
2. 不在 `save` 成功但数据库挂接尚未成功时记录 Artifact 输出。
3. 挂接失败时，拥有该操作的 Tool/Workflow 记录失败；不输出一个随后被补偿删除的成功 Artifact。
4. `discard` 及补偿失败由资源运行指标和结构化日志处理，不创建新 Agent Trace。
5. 若 Agent 场景确实需要展示“生成物落库”阶段，只在应用用例接缝创建一个 `persistence` Observation（如 `materialize-artifact`），不展开 MinIO stat/put/multipart 内部调用。

用户在 Agent Run 之前独立上传的资源不创建 Agent Trace；后续 Agent 输入通过 ArtifactReference 表达它。异步视频物化没有存续的 Agent Context 时，不能为了连线而把 OTel Context 塞进资源表；它保留 generation task/session 业务关联，只有业务本来就持有合法 Trace Context 时才建立因果关系。

### 9.6 预览与访问

当前 `ChatResourceUrls` 产生 `/api/chat/resources/{id}/content|download`，Bucket 私有且应用端点要求 owner 鉴权。这些 URL 的规则是：

1. `applicationViewUrl` 只作为 Trace 中的业务定位元数据。
2. 不把受鉴权 URL 编码成 Langfuse multimodal media block，因为 Langfuse 服务端无法获得产品用户凭证。
3. 不把相对 URL 描述为公开、永久或由 Langfuse 可抓取。
4. 不持久化 MinIO 预签名 URL。
5. 不为 Langfuse 预览公开 Bucket、绕过 owner 鉴权或复制业务对象。

未来若业务正式提供独立、稳定、可由观测平台读取的资源访问能力，必须作为资源 Module 的新 Interface 另行设计；不能让观测 Adapter 自行签名 URL。

### 9.7 内容策略与观测策略

`ResourceContentPolicy` 对 MIME/签名和 inline preview 的允许列表属于业务内容安全规则，不是 Langfuse 内容采集黑白名单。两者保持独立：

- 业务内容策略决定资源能否保存、预览或必须下载。
- `ContentCaptureMode` 决定 Trace 是否记录正文和 Artifact 元数据。
- 观测不得放宽或再次执行业务内容检查。

`ResourceStorageException` 进入 Trace 时只记录稳定 `error.kind`：NOT_FOUND、SIZE_LIMIT、UNAVAILABLE、IO_ERROR。不得记录 cause 中的 SDK 消息、完整 object key、bucket 或 endpoint。

### 9.8 MinIO 与 Langfuse 对象存储隔离

当前开发环境允许业务使用已有 bucket 的 `resources/` 前缀，不要求为了接入 Langfuse 迁移现有对象。隔离规则：

```text
MinIO Cluster
├── business bucket / resources/*     # H Agent ResourceStorage
├── langfuse event bucket/prefix       # Langfuse 内部事件
└── langfuse media bucket/prefix       # 可选临时媒体/评估样本
```

- 生产环境优先使用独立 bucket 和独立凭据。
- 开发环境若共享 bucket，至少使用互斥 prefix 和互不越权的凭据。
- H Agent 观测进程不需要业务 MinIO 凭据。
- Langfuse 不获得业务 `resources/*` 的读取、删除或生命周期管理权限。

### 9.9 不同来源

| 来源 | 业务资源处理 | Observation |
|---|---|---|
| 用户上传 | 现有 Coordinator 落 MinIO + PostgreSQL | 后续 Agent 输入引用实际 resourceId；不追溯创建上传 Trace |
| 模型消费已有图片 | 业务 Resolver 已按 owner 读取 | Model 输入记录 ArtifactReference；观测不再次读取 |
| 同步生成图片 | `saveAndAttach` 成功后进入消息 | Generation/Tool 输出记录 GENERATED 引用 |
| 异步生成视频 | generation task 物化并投影聊天消息 | 物化成功后记录引用；无 Context 时不伪造父 Trace |
| Agent 交付文件 | FileDelivery Tool 经 Coordinator 保存 | Tool 输出记录 TOOL_OUTPUT 引用 |
| 文档解析 | 原文件仍为业务资源 | 同时表达 SOURCE 引用与模型实际看到的文本 |
| A2A Artifact | 产品需要时才业务物化 | 记录 REMOTE_* 血缘和业务 resourceId |
| MCP Resource | 产品需要时才业务物化 | 记录 Tool/MCP 血缘和业务 resourceId |
| 临时 Base64 | 不自动进入业务 MinIO | 元数据或可选异步 Langfuse Media；不进 Span attribute |

观测模块不能为了获得更完整 Trace 而擅自把远程附件变成业务资源。

### 9.10 保留与孤儿对象

当前 MinIO Module 尚未实现用户删除、未绑定资源 TTL、引用计数、对象 inventory 或自动 GC；消息/会话 metadata 删除也不等于对象删除。Trace 必须如实接受这个业务现状：

- Langfuse 不是对象引用账本或孤儿对象对账系统。
- Trace 保留期不控制业务对象保留期，业务对象保留期也不反向控制 Trace。
- 历史 ArtifactReference 允许因为业务 metadata 或对象变化而不可访问。
- `discard` 只服务挂接失败补偿，观测不得调用它清理 Trace 关联对象。
- 正式生产前的资源删除与保留设计属于资源 Module，不并入 Agent observability。

### 9.11 Langfuse 成本约束

每个 ArtifactReference 是有界小 JSON，不包含二进制和任意业务 metadata。记录规则：

- 最近直接消费/产生 Artifact 的 Observation 记录完整引用。
- 根 Agent 输出仅在 Artifact 确实是最终产品结果时再次表达。
- 不在每个 workflow/relay/持久化父节点机械重复同一引用。
- 单 Observation 的引用数量受 `max-collection-elements` 和总字节上限约束。
- 业务 MinIO Artifact 不进入 `STRUCTURED_WITH_MEDIA` 镜像队列。

## 10. Langfuse 与 OTel 数据平面

### 10.1 直接 OTLP

第一版直接使用 OTLP/HTTP：

```text
Application
  -> BatchSpanProcessor
  -> OTLP/HTTP
  -> {LANGFUSE_BASE_URL}/api/public/otel/v1/traces
```

请求包含：

- HTTP Basic Auth：public key + secret key。
- `x-langfuse-ingestion-version: 4`。
- OTLP HTTP protobuf。

第一版不引入 Collector。配置仍以标准 OTLP 形式组织，使以后改 endpoint 即可接入 Collector。

### 10.2 Instrumentation scope 过滤

所有 H Agent Span 使用：

```text
com.h.agent.observability
```

自定义 Filtering SpanProcessor 只把该 scope 发送到 Langfuse。Spring、数据库、Redis 或通用 HTTP 自动 Span 即使存在，也不进入 Langfuse。

不得让被过滤的自动 HTTP Span 成为已导出语义 Span 的中间父级，否则会形成缺失父节点。A2A/MCP 远程调用由项目语义 Adapter 显式创建。

### 10.3 Resource attributes

- `service.name`
- `service.version`
- `deployment.environment.name`
- `telemetry.sdk.*`

`backend` 与 `other-agents` 可以进入同一个 `h-agent` Langfuse Project，通过 service 和 environment 区分。

### 10.4 Langfuse 属性

每个已导出 Span 重复必要的 Trace 级属性：

- `langfuse.session.id`：顶级产品聊天 Session。
- `langfuse.user.id`：真实产品用户；机器入口可缺失。
- `langfuse.trace.name`
- `langfuse.trace.tags`

平台属性：

- `h.schema_version`
- `h.kind`
- `h.runtime`
- `h.agent_id`
- `h.agent_session_id`
- `h.root_run_id`
- `h.entry_kind`
- `h.tool_name`
- `h.outcome`
- `h.content.capture_mode`
- `h.content.capture_state`

需要在 Langfuse 中筛选的自定义值同时使用 `langfuse.trace.metadata.*` 或 `langfuse.observation.metadata.*`。

### 10.5 Session 与 User

- Langfuse Session 对应顶级产品聊天 Session。
- 当前实际 Agent Session 作为 Observation metadata。
- Langfuse User 只对应真实产品用户。
- A2A/MCP 机器调用没有明确产品用户时不伪造 user id。
- 调用方 Agent、service 和 actor 作为 metadata，不混入 user id。

不把 Langfuse Project ID 作为第一版必需配置。当前前端没有 Trace 跳转消费方，trace ID 只用于关联和排障。

### 10.6 Trace、Metrics、Logs 三信号分工

Langfuse 文档中的 Metrics 是对已摄取 Trace/Observation/Score 的聚合查询和告警，不是 OTLP Metrics 接收端。Langfuse 当前公开的有效 OTLP 写入口是 `/api/public/otel/v1/traces`；`GET /api/public/v2/metrics` 是读取 Langfuse 观测数据的分析接口，二者不能混为一谈。部分 Langfuse 版本虽然暴露 `/api/public/otel/v1/metrics` dummy route 并返回成功，却不处理请求体；任何 Metrics exporter 都禁止指向该地址，避免静默丢数。

`ResourceStorageMetrics` 不能由 Langfuse 取代，原因是：

1. Agent Trace 可采样、可丢弃，不能作为准确请求数和错误率分母。
2. 用户上传、预览、下载和事务补偿不一定处于 Agent Trace 内。
3. Langfuse 不接收标准 OTLP Metrics 数据点。
4. 为每次 MinIO stat/get/put 创建 Span 会污染 Agent 因果树并增加平台存储成本。
5. 补偿删除失败需要不采样告警；Observation count 无法提供相同保证。

采用三个独立信号：

| 信号 | 技术路径 | 回答的问题 | 完整性 |
|---|---|---|---|
| Agent Trace | OTel Span -> Langfuse | 某次 Agent 为什么这样执行/失败 | 可采样、best-effort |
| 资源 Metrics | Micrometer -> Prometheus scrape | MinIO 整体错误率、吞吐和延迟是否异常 | 不做 Trace 采样 |
| 结构化 Logs | SLF4J -> 现有日志系统 | 稀有故障的安全现场证据 | 按日志策略保留 |

不把 `trace_id`、resourceId、storageKey、userId 或 sessionId 放进 Metrics label。需要单次调用上下文时查 Trace/Log，需要总体趋势时查 Metrics。

### 10.7 ResourceStorageMetrics 演进

删除自制 LongAdder + `snapshot()` 形态，用 Micrometer `MeterRegistry` 重建为 `ResourceStorageMeters`。Spring Boot 的标准指标入口是 Micrometer；生产默认选择 Prometheus pull，未来也可以把同一 Meter 通过 Micrometer OTLP 发往专用 Metrics backend，但不能发往 Langfuse traces endpoint。

建议 Meter：

| Meter name | 类型 | Tags | 语义 |
|---|---|---|---|
| `h.agent.resource.storage.operation.duration` | Timer/Histogram，单位秒 | `operation`、`outcome`、`error.kind` | save/open/discard 次数、延迟与失败分布 |
| `h.agent.resource.storage.object.size` | DistributionSummary，单位 byte | `operation=save` | save 成功后确认的实际对象字节量 |
| `h.agent.resource.storage.compensation` | Counter | `outcome`、`error.kind` | 回滚补偿删除成功/失败 |

Tag 取值必须是有界枚举：

```text
operation  = save | open | discard
outcome    = success | failure | rejected
error.kind = none | not_found | size_limit | unavailable | io_error | range
```

基数是设计约束，不允许在运行时扩展动态标签。Timer 的 count 已经提供操作次数，不再额外维护一组同义 Counter。`ResourceRangeException` 可以记为 `outcome=rejected,error.kind=range`，但不计入 MinIO availability 失败率。

`ResourceStorageMeters` 是具体 Module，不增加只有一个实现的业务 Port。它隐藏 Meter 名称、Timer 缓存、tag 归一化、首个终态和计时细节；调用处只开始一次测量并以 success/failure/rejected 结束。测量方法必须 no-throw，MeterRegistry/Prometheus 不可用不得改变资源操作结果。Metrics 被显式关闭时由空 `CompositeMeterRegistry` 提供进程内 no-op 行为，不要求调用方判空或分支。

建议 Interface：

```java
public final class ResourceStorageMeters {

    public StorageMeasurement start(StorageOperation operation);

    public void recordCompensationSuccess();

    public void recordCompensationFailure(ResourceStorageErrorKind kind);
}

public interface StorageMeasurement extends AutoCloseable {

    void success();

    void success(long actualBytes);

    void failure(ResourceStorageErrorKind kind);

    void rejected(StorageRejectionKind kind);

    @Override
    void close();
}
```

`StorageMeasurement` 使用单调时钟并保证终态 first-wins；遗漏终态时以 `failure/io_error` 安全结束。save 在确认实际写入大小后调用 `success(actualBytes)`；open/discard 调用无字节参数的 `success()`。`MinioResourceStorage` 在公开 `save/open/discard` 的单一出口结束 measurement，`TransactionalResourceWriteCoordinator` 只记录 compensation Meter。生产类不再暴露计数 getter 或 `snapshot()`，测试通过注入 `SimpleMeterRegistry` 查询 Meter 结果。

当前 open Timer 的结束点是 `stat/getObject` 返回 `GetObjectResponse`，表示“对象流建立成功”，不表示 HTTP 客户端已读完。第一版不记录 open 实际传输字节；未来需要时必须在 `ResourceContent` 流的 EOF/close/error 接缝单独设计 completed/cancelled/error measurement，不能把 `responseLength` 冒充已传输量。

物理 `discard` Timer 与 compensation Counter 可以同时增加：前者衡量一次 MinIO 删除调用，后者衡量事务回滚清理工作流。两者不能相加为同一 attempts 分母。没有对象扫描、确认和 resolved 状态源前，不创建 `orphan.count` Gauge；补偿失败只代表“可能存在孤儿对象”。

### 10.8 Metrics 导出与告警

`backend` 增加 Spring Boot Actuator 与 Prometheus registry，只暴露 Prometheus scrape 所需端点。Prometheus 缺失、未抓取或暂时不可用不影响应用启动、资源调用和 health/readiness。

首批告警：

- compensation failure 在窗口内增加：需要人工检查孤儿对象，最高优先级。
- `error.kind=unavailable` 错误率超过阈值且请求量达到最小门槛。
- save/open 的 p95/p99 延迟持续超阈值。
- SIZE_LIMIT/IO_ERROR 的异常增幅用于容量或内容链路排障，不直接等价于 MinIO 宕机。

补偿失败继续输出一条脱敏 ERROR 日志；Metrics 不取代日志。成功操作不写日志。Langfuse 可以对 sampled `materialize-artifact` Observation 做产品体验分析，但该 Dashboard 必须标注为 Trace-derived，不作为 MinIO SLO 或告警真相源。

第一版不要求引入 OTel Collector：Trace 继续直接发送 Langfuse，Prometheus 直接 scrape Micrometer。未来统一传输时可引入 Collector，把 traces 和 metrics 路由到不同后端，但仍不合并两种信号的语义。

## 11. LangChain4j Adapter

### 11.1 Agent

使用 `AgentListener.beforeAgentInvocation/afterAgentInvocation/onAgentInvocationError`。

现有产品 `AgentStepListener` 的强关联身份必须保留：

- Agentic Scope 实例身份。
- Agent 实例身份。
- inputs Map 实例身份。

增加 `PlatformAgentListener` 组合产品投影与观测 Listener。禁止退化为 `memoryId + agentId`，因为重入和并行调用会冲突。

### 11.2 Model

普通 `ChatModel` 和 `StreamingChatModel` 都在集中配置处安装 `ChatModelListener`：

- request：模型、结构化输入、工具定义和参数。
- response：结构化输出、finish reason、usage 和 response id。
- error/cancel：透明记录终态。
- streaming delta：有界聚合，完成后形成最终输出，不为每个 token 建 Span。

Agent Listener 与 ChatModel Listener 分别拥有 Agent 和 Generation，不额外创建重复 AiService Span。

### 11.3 Tool

普通 AiService 工具必须装饰真实 `ToolExecutor`，因为 `ToolExecutedEvent` 只在执行后发生，无法得到准确开始时间和父 Context。

每个 tool call 以稳定 tool call ID 独立建立 Observation。并行工具是同一个 Agent/Generation 下的兄弟节点。

### 11.4 Retriever、Embedding、Guardrail

优先使用框架正式 Listener：

- Retriever：query、返回文档身份/score、可选正文。
- Embedding：模型、输入数、维度、usage。
- Guardrail：检查类型、结果、耗时和失败。

这些 Listener 只观察，不改变框架异常与结果。

## 12. AgentScope Adapter

### 12.1 不使用内置 OtelTracingMiddleware

AgentScope 2.0.1 内置 middleware 不满足本设计：

- 名称和属性不可按 H Agent schema 控制。
- 把一批工具合并为一个 Span。
- 取消状态不符合业务语义。
- 缺少完整结构化输入输出。
- instrumentation scope 无法纳入项目过滤。

使用官方 `MiddlewareBase` 接缝实现 `HAgentObservabilityMiddleware`。

### 12.2 Span 所有者

- `onAgent`：Agent Observation。
- `onModelCall`：Generation Observation。
- `onReasoning`：不重复创建 Generation，可补充阶段属性。
- `onActing`：不创建批量 Tool Span。
- `ObservedAgentTool.callAsync`：单个 Tool Observation。

Tool 装饰器必须区分：

1. `ToolBase` delegate：继续表现为 `ToolBase`，代理 permission、readOnly、concurrencySafe、MCP、external、rule 和 suggestion 行为。
2. 普通 `AgentTool` delegate：继续表现为普通 `AgentTool`。

这样不会因观测装饰改变 AgentScope 权限或调度语义。

装饰发生在 Toolkit 注册/复制接缝，并保留原 Tool schema、group、扩展模型、preset 和 delegate 实例语义；不得在 Agent build 完成后通过“移除再注册”偷偷重排工具或丢失权限元数据。

### 12.3 统一安装

增加 `AgentScopeObservationInstaller`，所有 Agent 构建路径必须经过它：

- 父 Harness Agent。
- SDK 静态子 Agent factory。
- Catalog USER 动态子 Agent。
- Gateway 独立子 Agent。

Installer 统一添加 middleware 和已观测 Toolkit。动态 USER 子 Agent 不再手工只添加 assignment/lifecycle middleware。

### 12.4 RuntimeContext

根执行把 `ObservationContext` 作为类型化值放入 `RuntimeContext`。

子上下文必须使用：

```java
RuntimeContext.builder(parentContext)
```

然后覆盖子 Agent 的 user、session、state 和 assignment。不得创建与父执行无关的空 RuntimeContext。

跨 Reactor 调度使用 AgentScope 已采用的 `ContextPropagationOperator` 模式，从 Reactor Context 优先解析 OTel Context，ThreadLocal 只作同步回退。

### 12.5 动态物化

`AgentScopeSubagentRuntimeFactory.buildUserChild` 通过 Installer 构建；父 Toolkit 中的 Tool 装饰器在 copy 和能力裁剪后仍然存在。

静态/BUILTIN factory 是否继承 middleware 和 typed RuntimeContext 必须通过 SDK contract test 固定，不能只依赖注释或人工推断。

### 12.6 产品事件

`HarnessEventMapper`、协作进度投影和 SSE 信封不是观测接缝。观测 Adapter 不消费产品 relay 后的重复事件来创建 Span，避免父 emitter 与 relay 双重记录。

## 13. A2A 传播与生命周期

### 13.1 标准

A2A over HTTP 使用 W3C：

- `traceparent`
- `tracestate`
- `baggage`

不把 trace ID 写入 A2A Message、Artifact、Task、contextId、taskId 或 JSON-RPC metadata。A2A 身份与 Trace 身份保持独立。

### 13.2 客户端

当前 LangChain4j `AgenticServices.a2aBuilder` 隐藏 Transport 配置。新版用项目自有 `A2ARemoteAgentModule` 替换：

```java
public interface A2ARemoteAgentModule {
    <T> T create(RemoteAgentSpec<T> spec);
}
```

Implementation 使用官方 A2A Java SDK：

1. 获取 AgentCard。
2. 创建官方 `Client`。
3. 使用 `JSONRPCTransportConfigBuilder`。
4. 安装官方 `ClientCallInterceptor`。
5. 在逻辑 `message/send` 或 streaming 操作外创建 `SpanKind.CLIENT` 的 `remote_call`。
6. Interceptor 在实际请求创建时从当前 client Span 注入 W3C Context。
7. 同步结果、SSE complete、error 和 cancel 只结束一次 Span。
8. 保持原始中断标记和业务异常。

AgentCard 启动期查询不是某次 Agent 执行，不伪造业务 Trace。

### 13.3 服务端

`other-agents` 增加 A2A WebFilter：

1. 每个 HTTP 请求独立提取 W3C Context。
2. 创建 `SpanKind.SERVER` 的 `remote_call`。
3. 把 server Context 放入 Reactor Context。
4. A2AAgentServer 和远程 LangChain4j Agent 形成子 Observation。
5. 普通 JSON 在响应写出结束时终止 server Span。
6. SSE 在 complete、error 或 cancel 时终止。

非法 `traceparent` 被 Propagator 忽略，服务端从新根开始，不返回协议错误。

### 13.4 并发与回调

异步回调必须捕获调用自己的 immutable OTel Context，不读取后来线程上的 `Context.current()`。两个并行 A2A SSE 调用不得共享可变 parent、结束标记或 response accumulator。

## 14. MCP 传播与生命周期

### 14.1 客户端

LangChain4j `StreamableHttpMcpTransport` 使用：

```java
.customHeaders(McpHeadersSupplier)
```

Supplier 每次构造请求时动态注入 W3C Context，不在 McpClient 创建时固定 Header。

真实链路：

```text
tool CLIENT/INTERNAL
  -> remote_call CLIENT
    -> MCP HTTP POST
```

`McpToolProvider` 产生的真实 ToolExecutor 被装饰；remote_call Context 在 Transport 构造 POST 时仍为 current。请求完成、错误、超时或取消后恢复父 Context。

### 14.2 服务端

Spring WebFlux MCP 服务端增加：

- WebFilter：逐请求提取 Context 和创建 SERVER remote_call。
- Reactor Context bridge：在线程切换后恢复 Context。
- `ObservedToolCallbackProvider`：包装真实 Spring AI Tool Callback，创建 server-side Tool Observation。

不能在 WebFilter 中用同步 `try (Scope)` 包住 `chain.filter()`，因为异步执行可能发生在其他线程。

### 14.3 协议身份

- `Mcp-Session-Id` 是传输会话，不是 Trace。
- `MCP-Protocol-Version`、`Last-Event-ID` 和标准 Accept/Content-Type 保持原样。
- OTel Context 不存入 MCP Session。
- 两次工具调用即使复用同一个 McpClient 和 MCP Session，也必须携带各自 Trace。

### 14.4 长连接

每次 JSON-RPC POST 传播当前调用 Context。属于该 POST 的 SSE 响应沿用该调用。

共享 subsidiary GET/SSE 通道代表连接生命周期，不能绑定某一个 Agent Run；第一版保持该能力关闭。未来启用时，连接本身不携带业务 Trace，服务端主动通知若无逐消息标准载体则建立独立或 linked Trace，不伪造父子关系。

## 15. Baggage schema

只传播：

- `langfuse.session.id`
- 可选 `langfuse.user.id`
- `langfuse.trace.name`
- `langfuse.trace.tags`

不传播：

- prompt、工具参数或结果。
- Artifact 数据。
- A2A task/context ID。
- MCP Session ID。
- Agent Run 状态。
- 鉴权凭据。

这不是内容黑名单，而是稳定的跨服务传播 schema；目的是防止 Header 无界增长和业务对遥测载体产生依赖。

## 16. 非干扰保证

### 16.1 实现规则

1. OTLP 只通过有界 `BatchSpanProcessor` 后台发送。
2. 请求线程不执行 OTLP flush。
3. 观测 Adapter 不调用 `block()`、不主动 `subscribe()`、不改变 scheduler。
4. Reactor Adapter 只使用 `deferContextual`、`doOn*`、`doFinally` 和 context bridge 保持原 publisher 语义。
5. 同步 Adapter 在 `try/finally` 中记录并重新抛出原异常。
6. 内容编码器限制深度、元素数和字节数，避免无界遍历。
7. Artifact 引用不触发 MinIO I/O。
8. 队列满时允许丢弃观测数据。
9. Langfuse 配置错误产生 no-op，不阻止 Spring Context 启动。
10. shutdown 最多执行一次有截止时间的 bounded flush。
11. Micrometer 记录只更新进程内 Meter；不得在资源调用线程同步访问 Prometheus 或 Langfuse。

### 16.2 故障矩阵

| 故障 | 观测行为 | 业务行为 |
|---|---|---|
| Langfuse 未配置 | no-op | 正常 |
| 部分 key/URL 错误 | DEGRADED 配置状态、no-op | 正常启动 |
| OTLP 超时/拒绝 | exporter 记录受限错误 | 不等待、不失败 |
| Batch 队列满 | 丢 Span、增加内部计数 | 不反压 |
| Prometheus 未部署/未抓取 | Metrics 暂不可查询 | 资源与 Agent 正常 |
| Meter 记录内部异常 | 丢本次 measurement、受限日志 | 原资源结果不变 |
| 内容序列化异常 | `CAPTURE_ERROR` 或省略内容 | 原调用继续 |
| 内容超限 | 截断/引用状态 | 原调用继续 |
| 非法跨服务 Context | 忽略 parent、新根 | 协议继续 |
| MinIO metadata/对象不可用 | Artifact 不可预览 | 历史 Trace 仍可读 |
| 媒体复制失败 | 可选 capture event 失败 | Agent/Run 不降级 |
| 应用关闭 flush 超时 | 放弃未发送数据 | 关闭继续 |

### 16.3 运行状态

Actuator 只承担 Micrometer/Prometheus 指标暴露，不给 Langfuse 或 MinIO 注册会影响 readiness/liveness 的 HealthIndicator。Langfuse 仍提供可测试的 `LangfuseRuntimeStatus` Bean 和结构化启动日志：

- `ACTIVE`
- `DISABLED_EXPLICITLY`
- `DISABLED_NOT_CONFIGURED`
- `DEGRADED_MISCONFIGURED`

运行状态用于运维，不参与业务健康判定。

## 17. 配置

```yaml
agent-observability:
  enabled: auto
  service-name: ${spring.application.name}

  langfuse:
    base-url: ${LANGFUSE_BASE_URL:}
    public-key: ${LANGFUSE_PUBLIC_KEY:}
    secret-key: ${LANGFUSE_SECRET_KEY:}
    environment: ${LANGFUSE_ENVIRONMENT:local}

  sampling:
    strategy: parent-based
    root-ratio: ${LANGFUSE_SAMPLE_RATE:1.0}

  content:
    mode: ${LANGFUSE_CONTENT_MODE:structured}
    max-inline-block-bytes: 131072
    max-observation-bytes: 262144
    max-structure-depth: 16
    max-collection-elements: 512

  artifacts:
    mode: reference-first
    transient-media-mirror-enabled: false

  export:
    queue-size: 2048
    batch-size: 512
    schedule-delay: 1s
    timeout: 5s
    shutdown-timeout: 5s

management:
  endpoints:
    web:
      exposure:
        include: prometheus
  prometheus:
    metrics:
      export:
        enabled: true
```

`enabled=auto`：

- base URL、公钥、密钥全部存在时启用。
- 三者都不存在时视为未配置并 no-op。
- 只存在部分配置或 URL 非法时进入 `DEGRADED_MISCONFIGURED` 并 no-op。
- 显式 `false` 是运行期开关。

两个应用必须以同一种机制加载仓库根 `.env`，不能由 `other-agents` 手工解析而 `backend` 依赖工作目录。密钥只进入 Spring Environment，不写日志或 Trace。

Prometheus 配置与 `LANGFUSE_*` 完全独立。禁用 Prometheus export 只停止运行指标输出，不切换到 Langfuse Observation 计数兜底。

## 18. 数据持久化

Agent Run 只增加：

```sql
trace_id VARCHAR(32) NULL
```

不增加：

- observation ID。
- Langfuse Project ID。
- trace availability/status。
- sampled/retained 状态。
- Trace Link 表。
- Artifact 引用账本。
- Agent Turn 表。

开发环境允许直接重建或一次性迁移旧 `langfuse_trace_id`，不双写。

`trace_id` 只表示本次执行生成的 W3C Trace 身份，不表示该 Trace 被采样、成功送达或仍被 Langfuse 保留。根采样决定为“不记录”时也可能存在有效 trace ID，因此业务和前端不得据此承诺平台可查询。

业务资源表继续保存 `storage_type`、`storage_key` 和资源元数据；观测 Mapper 只消费调用链中已有的 `ChatMessageResourceDto`、`GeneratedArtifact` 或已提交业务结果，不在 Agent Run 中复制 Artifact 字段，也不为映射反查资源表。

## 19. 测试策略

### 19.1 Core contract

使用 `InMemorySpanExporter` 验证：

- 根与子父子关系。
- 终态 first-wins。
- success/error/cancel/timeout 状态。
- no-op 不抛异常。
- scope filtering。
- ParentBased sampling。
- SemanticContent 编码、限额和失败状态。
- Artifact 只引用不读取。
- W3C inject/extract 往返。

### 19.2 LangChain4j

- Agent 强身份在重入和并行时不串线。
- 普通/Streaming ChatModel 输入输出和 usage。
- streaming cancel/error。
- 并行工具按 tool call ID 独立 Span。
- Retriever、Embedding 和 Guardrail。
- 同一模型或工具不产生重复 Observation。

### 19.3 AgentScope

- 父 Agent。
- 静态 BUILTIN 子 Agent。
- 动态 USER 子 Agent。
- Gateway 直接子 Agent。
- `RuntimeContext.builder(parent)` 继承 typed carrier。
- 并行工具各自真实执行时长。
- ToolBase 权限和类型语义不因装饰改变。
- Reactor thread hop。
- Primary 响应成功后 Maintenance Trace 延迟创建。
- Maintenance 失败不修改 Agent Run。
- 前端结束后维护继续。

### 19.4 A2A

真实启动两个应用：

- client/server 相同 trace ID。
- CLIENT -> SERVER -> remote Agent 正确父子关系。
- Header 实际出现在 HTTP 请求而非 JSON body。
- taskId/contextId 与 Trace 独立。
- 两个并行 SSE 不串 Context。
- error/cancel/timeout 透明。
- 非法 Header 降级新根。

### 19.5 MCP

- 复用同一个 McpClient 并行两个根 Trace。
- 每次 POST 动态 Header 正确。
- MCP Session 不存 Trace Context。
- WebFlux thread hop 后 server Tool 仍是正确子节点。
- POST SSE 生命周期。
- 标准 MCP Header 未被覆盖。
- 非法 Header 不改变 JSON-RPC 结果。

### 19.6 Artifact

- 用户上传资源只保存一次。
- 生成图片/视频先业务落库再记录引用。
- 资源复用产生新 resourceId、共享同一 storageKey 时，两次引用身份正确且 Trace 不暴露 storageKey。
- schema 不要求 SHA-256，不把 multipart ETag 当 hash。
- Data URI 不进入 attribute。
- PDF 源 Artifact 与模型实际文本同时表达。
- MinIO open 未被观测模块调用。
- 受鉴权应用 URL 只作 metadata，不编码成 Langfuse Media。
- 业务挂接回滚时不输出成功 Artifact，补偿失败不改变原业务异常。
- 资源 metadata/对象不可用只影响预览，不改变历史 Trace。
- transient media queue full 不影响业务。

### 19.7 Resource Metrics

使用 `SimpleMeterRegistry` 验证：

- save/open/discard 的 Timer count、duration 和有界 tag。
- success/failure/rejected 只结束一次。
- NOT_FOUND/SIZE_LIMIT/UNAVAILABLE/IO_ERROR 映射稳定。
- Range 不可满足记 rejected，不进入 availability 失败率。
- open success 只表示响应流建立，不产生虚假的 completed-download bytes。
- compensation success/failure Counter。
- 物理 discard Timer 与 compensation Counter 分属不同语义，不做求和。
- 补偿失败同时产生脱敏 ERROR 日志。
- 没有 reconciliation 状态源时不存在 orphan Gauge。
- Meter 中不存在 resourceId、storageKey、userId、sessionId、bucket 或 endpoint。
- 删除旧 `snapshot()` 测试；测试通过 MeterRegistry 查询公开指标结果。

使用 Prometheus registry 验证 scrape 文本包含预期 Meter；不要求 Langfuse 可用，也不向 Langfuse `/v1/traces` 或 dummy `/v1/metrics` 发送 Metrics payload。

### 19.8 真实 Langfuse smoke

可选集成测试连接本地 Langfuse：

- 发送一条根 Trace。
- 验证 Agent、Generation、Tool 树。
- 验证 Session/User/metadata。
- 验证 A2A/MCP 跨服务树。
- 验证 ArtifactReference 为小 JSON 且无 Base64。

Smoke test 由显式 profile 启用，不成为普通单元测试前置条件。

## 20. 实施顺序

1. 增加根 Maven Reactor 和 `agent-observability` Module。
2. 建立 SemanticContent、ArtifactReference 和生命周期 contract tests。
3. 替换 OTel/Langfuse 配置、Sampler、scope filter 和 no-op 状态。
4. 替换 `AgentRunTelemetryService`，调整 Run 创建与 trace ID 回写顺序。
5. 接入 LangChain4j Agent/Model/Tool/Retriever/Embedding/Guardrail。
6. 接入 AgentScope Installer、Middleware、Tool 装饰和 RuntimeContext 继承。
7. 实现 Primary/Maintenance 阶段协调。
8. 替换 A2A builder，完成 client/server 标准传播。
9. 完成 MCP client/server 动态传播和 Reactor bridge。
10. 用 Micrometer `ResourceStorageMeters` 替换旧 LongAdder/snapshot，并增加 Prometheus scrape 与告警规则。
11. 接入已有 MinIO 业务结果的 Artifact 映射，确保 Observation 不读二进制、不反查数据库。
12. 删除旧 telemetry config、旧 Mock、旧字段和旧 Metrics snapshot 测试。
13. 执行跨服务、Prometheus scrape 与真实 Langfuse smoke test。

每一步先以 no-op/内存 exporter 测试，不要求 Langfuse 可用才能完成业务测试。

## 21. 验收标准

1. LangChain4j 和 AgentScope 使用同一语义 schema。
2. 一次复杂执行能看到 Agent、Generation、Tool、A2A、MCP 的完整因果树。
3. 动态 AgentScope 子 Agent 不丢父 Trace。
4. 产品响应完成后，后置维护不延长或改变 Primary Trace 结果。
5. A2A/MCP 使用 W3C Context，并在并行、流式和 Reactor thread hop 下不串线。
6. 关闭或破坏 Langfuse 配置后，所有业务测试结果保持不变。
7. 采样率和 ContentCaptureMode 可配置，设计中不存在“无条件采集”。
8. 不存在内容黑白名单和规则引擎。
9. 业务附件在 MinIO 只有一份；Langfuse 不拥有或清理业务对象。
10. Span attribute 中没有 Base64、byte[]、InputStream 或无界 JSON。
11. 观测失败不产生业务 degraded 状态。
12. 本地数据库除 nullable trace ID 外没有观测状态机。
13. MinIO 运行指标由 Micrometer/Prometheus 不采样记录，Langfuse Observation count 不承担 SLO 或告警。
14. Metrics label 没有 resourceId、storageKey、userId、sessionId 等高基数值。

## 22. 参考规范

- [ResourceStorageMetrics 与 Langfuse 边界研究](../../research/2026-08-28-resource-storage-metrics-langfuse-assessment.md)
- [MinIO 资源对象存储运行手册](../../runbooks/minio-resource-storage.md)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [OpenTelemetry Context Propagation](https://opentelemetry.io/docs/concepts/context-propagation/)
- [OpenTelemetry Trace SDK](https://opentelemetry.io/docs/specs/otel/trace/sdk/)
- [OpenTelemetry GenAI Semantic Conventions](https://github.com/open-telemetry/semantic-conventions-genai)
- [OpenTelemetry MCP Semantic Conventions](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/mcp.md)
- [A2A Protocol Specification](https://a2a-protocol.org/latest/specification)
- [MCP Streamable HTTP Transport](https://modelcontextprotocol.io/specification/draft/basic/transports)
- [Langfuse OpenTelemetry Ingestion](https://langfuse.com/docs/api-and-data-platform/features/public-api)
- [Langfuse Metrics Overview](https://langfuse.com/docs/metrics/overview)
- [Langfuse Metrics API](https://langfuse.com/docs/metrics/features/metrics-api)
- [Langfuse Sampling](https://langfuse.com/docs/observability/features/sampling)
- [Langfuse Multi-Modality](https://langfuse.com/docs/observability/features/multi-modality)
- [Langfuse Blob Storage](https://langfuse.com/self-hosting/deployment/infrastructure/blobstorage)
- [OpenTelemetry Metrics](https://opentelemetry.io/docs/concepts/signals/metrics/)
- [Spring Boot Metrics and Micrometer](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
- [Spring Boot Observability Signal Guidance](https://docs.spring.io/spring-boot/reference/actuator/observability.html)
- [Prometheus Instrumentation Best Practices](https://prometheus.io/docs/practices/instrumentation/)
