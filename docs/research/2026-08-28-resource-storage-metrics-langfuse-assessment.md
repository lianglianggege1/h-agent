# `ResourceStorageMetrics` 与 Langfuse 边界研究

> 日期：2026-08-28
> 状态：官方资料核验完成；这是决策证据文档，不是实现规格
> 范围：Langfuse、OpenTelemetry、Prometheus/Micrometer 对资源存储可观测性的合理分工

## 1. 结论

**Langfuse 不能、也不应取代 `ResourceStorageMetrics` 所承担的运行指标职责。**

应该选择的路线是：

1. 用 **Micrometer Metrics** 替换当前自制的进程内 `LongAdder` 计数；
2. 第一目标后端采用 **Prometheus**，由 `/actuator/prometheus` 拉取，后续也可通过 Micrometer 改接 OTLP Metrics 后端；
3. Langfuse 只接收 Agent/LLM traces；仅当存储行为是 Agent 执行中有解释价值的业务步骤时，记录一条语义 span；
4. 补偿删除失败继续产生一次脱敏结构化 ERROR 日志，并由标准 Metric 触发告警；
5. 三种信号共享统一语义，但必须分流，不能把 trace 派生统计当成精确运行指标。

简化后的边界是：

```text
ResourceStorage / ResourceWriteCoordinator
  ├─ Metrics ── Micrometer ── Prometheus（运行趋势、成功率、告警）
  ├─ Logs ─────────────────── 日志系统（单次补偿失败定位）
  └─ Traces
       ├─ Agent 业务语义 span ── Langfuse
       └─ 通用 S3/HTTP CLIENT span ── 通用 APM（若将来引入）
```

## 2. 当前代码的实际能力

当前 [`ResourceStorageMetrics`](../../backend/src/main/java/com/h/backend/chat/infrastructure/storage/ResourceStorageMetrics.java) 是“最小可观测性”实现，不是完整的 Metrics 系统：

- JVM 进程内用 `LongAdder` 累加 save/open/discard 成功和失败；
- 失败按四个稳定的 `ResourceStorageErrorKind` 分类；
- Coordinator 另记补偿删除成功/失败；
- 补偿删除失败同步输出一条脱敏 ERROR 日志；
- `snapshot()` 主要供测试断言，没有生产查询、持久化、时间序列、聚合或告警端点；
- 应用重启后计数归零，无法由现有实现计算跨实例的 rate、error ratio 或趋势。

两个实现语义需要在后续设计中明确保留：

1. `MinioResourceStorage.open()` 在取得 `GetObjectResponse` 后就记 `openSuccess`，此时响应流尚未被调用方读完。因此它表示“打开对象流成功”，不表示“客户端下载完整内容成功”。
2. 补偿路径会同时经过 Adapter 的 `discard` 埋点和 Coordinator 的补偿结果埋点。它们分别代表“物理存储调用”和“事务补偿工作流”，可以共存，但不能在同一个 attempts 总数中相加。

当前 [`LangfuseTelemetryConfig`](../../backend/src/main/java/com/h/backend/chat/infrastructure/config/LangfuseTelemetryConfig.java) 只装配 `SdkTracerProvider`、`BatchSpanProcessor` 和 OTLP trace exporter，没有 MeterProvider 或指标 exporter；这与 Langfuse 的实际产品边界一致。

## 3. Langfuse 的 OTLP 能力边界

### 3.1 信号支持矩阵

| OTLP 信号 | 截至 2026-08-28 的能力 | 对本项目的含义 |
| --- | --- | --- |
| Traces | 正式支持 `/api/public/otel/v1/traces`；支持 OTLP/HTTP JSON 与 protobuf，不支持 gRPC | Agent/LLM spans 可以发往 Langfuse |
| Metrics | 当前官方路由是 dummy handler，返回成功但不处理请求体 | **绝不能**把 Micrometer/OTel Metrics exporter 指向 Langfuse |
| Logs | 官方 `/otel/v1` 路由没有 logs；trace handler 会拒绝误投到 traces 路径的 OTLP logs | 结构化日志必须进入日志管道，而非 Langfuse OTLP |

官方 OpenTelemetry 集成文档始终把 Langfuse 描述为 trace backend，并只给出 `/v1/traces` 端点；还明确写出协议限制。[Langfuse：OpenTelemetry 集成](https://langfuse.com/integrations/native/opentelemetry)

Metrics 路由很容易造成误判：当前官方源码中处理函数仍是空函数；创建它的官方 PR 明确称其为 dummy route，目标是返回 `200`，但不处理 body。[Langfuse 当前 Metrics 路由源码](https://github.com/langfuse/langfuse/blob/95933f73a6685884ae946961cb3a77249d68520d/web/src/pages/api/public/otel/v1/metrics/index.ts#L11-L19)、[Langfuse PR #6408](https://github.com/langfuse/langfuse/pull/6408)

官方 `/otel/v1` 路由树只有 `traces` 与 `metrics`，没有 `logs`；trace handler 还会识别并拒绝错误发送到 traces 端点的 OTLP logs。[Langfuse 官方路由树](https://github.com/langfuse/langfuse/tree/95933f73a6685884ae946961cb3a77249d68520d/web/src/pages/api/public/otel/v1)、[同一版本的 Langfuse trace handler](https://github.com/langfuse/langfuse/blob/95933f73a6685884ae946961cb3a77249d68520d/web/src/pages/api/public/otel/v1/traces/index.ts#L119-L145)

因此不能只设置一个通用的 `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:3000/api/public/otel` 并期待三种信号都被保存。每种信号必须配置自己的 exporter 和真实后端。

### 3.2 “Langfuse Metrics”不是 OTLP Metrics

Langfuse Metrics API 是对其 observations/scores 数据做聚合查询，支持 cost、token usage、volume、latency、score 等 LLM 应用维度；它不是 Counter、Histogram、Gauge 的摄取端点。[Langfuse Metrics API](https://langfuse.com/docs/metrics/features/metrics-api)、[Langfuse Metrics 概览](https://langfuse.com/docs/metrics/overview)

两个同名概念必须严格区分：

```text
Langfuse Metrics API = 对已摄取 traces / observations / scores 的派生分析
OTLP Metrics         = 独立的 Sum / Gauge / Histogram 时间序列信号
```

Langfuse 官方概念页也明确给出分工示例：同一套 OpenTelemetry traces 可以发往多个目的地，例如 Langfuse 用于 LLM observability、Datadog 用于 infrastructure monitoring。[Langfuse Core Concepts](https://langfuse.com/docs/observability/data-model)

### 3.3 为什么 trace count 不能成为存储监控真值

- Agent trace 可以采样；未采样 trace 的 observations 不会送出；
- trace export 是异步、best-effort 的，进程崩溃或队列溢出时允许丢失；
- 不是所有资源操作都发生在 Agent trace 内，例如普通上传、预览和下载；
- 为每个资源操作创建 Langfuse observation 会增加 trace 噪声、摄取量与 ClickHouse/对象存储成本；
- Langfuse analytics 只能统计“被摄取的 observations”，不能证明实际存储调用的精确次数。

Langfuse 官方提供 trace sampling，说明 observation 数据本身允许按 trace 丢弃。[Langfuse Sampling](https://langfuse.com/docs/observability/sdk/advanced-features#sampling)

## 4. OpenTelemetry 标准的信号分工

OpenTelemetry 把 traces、metrics、logs 定义为不同但可相关联的信号：trace 描述一次请求经过的路径，metric 是运行期测量，log 是事件记录。[OpenTelemetry Signals](https://opentelemetry.io/docs/concepts/signals/)

对资源存储而言：

| 问题 | 正确信号 |
| --- | --- |
| 最近 5 分钟 save 错误率是多少？ | Metric |
| P95 save/open 建立流的耗时是多少？ | Histogram/Timer Metric |
| 哪一次 Agent 执行产生了这个 Artifact？ | Trace span |
| 某次补偿删除为什么失败、对应哪个 resourceId？ | 结构化 Log，并用 trace/span id 关联（若存在） |
| 当前有多少“已确认且尚未解决”的孤儿对象？ | 只有存在真实 reconciliation 状态源时才可用 Gauge |

OpenTelemetry 指标指南规定：只增加的 delta 应使用 Counter，需要统计分布和耗时则使用 Histogram。[OpenTelemetry Metrics supplementary guidelines](https://opentelemetry.io/docs/specs/otel/metrics/supplementary-guidelines/)

OpenTelemetry 的错误记录指南进一步建议：

- 操作通常记录一个包含成功和失败的 duration histogram；
- 失败用 `error.type` 区分；
- 推荐用一个指标表达成功与失败，而不是各造一个指标；
- span 与 metric 同时存在时，应使用一致的错误分类；
- 同一个异常不应在多个层级重复记录。[OpenTelemetry Recording errors](https://opentelemetry.io/docs/specs/semconv/general/recording-errors/)

这直接说明当前 `saveSuccess`、`saveFailures` 等多个手工 `LongAdder` 应收敛为标准 Meter，而不是迁移成多种 Langfuse observation 名称。

## 5. 推荐的项目内指标模型

### 5.1 物理存储操作

由 `MinioResourceStorage` 唯一记录，每个公开 Adapter 调用完成时只记一次：

```text
hagent.resource.storage.operation
type: Timer（Prometheus 下得到 count / sum / histogram buckets）
tags:
  operation = save | open | discard
  outcome   = success | error
  error.kind = none | not_found | size_limit | unavailable | io_error
```

选择 Timer 而不是“Counter + 另一个 duration”的原因：Micrometer Timer 本身已经发布事件 count；Micrometer 官方明确建议不要再为已经计时的事件增加重复 Counter。[Micrometer Counters](https://docs.micrometer.io/micrometer/reference/1.16/concepts/counters.html)

这里保留固定的 tag key 集合，并在成功时使用 `error.kind=none`，是为了兼容 Micrometer Prometheus registry 对同名 Meter tag keys 必须一致的约束。[Micrometer Prometheus tag-key 限制](https://docs.micrometer.io/micrometer/reference/1.16/implementations/prometheus.html#_limitation_on_same_name_with_different_set_of_tag_keys)

标签必须保持低基数：

- 允许：`operation`、`outcome`、四种稳定 `error.kind`；
- 禁止：`resourceId`、object key、bucket、文件名、用户 ID、session ID、trace ID、异常消息；
- `storage.system=minio` 若整个服务只有一个实现，可以作为 resource/common tag，也可以省略，不能按 endpoint 动态扩张。

Prometheus 官方建议外部资源访问至少监控请求量、错误和耗时；失败指标还应有对应的总 attempts，才能计算失败率。同时警告高基数 label 会快速扩大时间序列成本。[Prometheus Instrumentation](https://prometheus.io/docs/practices/instrumentation/)

### 5.2 事务补偿结果

由 `TransactionalResourceWriteCoordinator` 唯一记录，每次 rollback 补偿完成时只记一次：

```text
hagent.resource.storage.compensation
type: Counter
tags:
  operation  = discard
  outcome    = success | error
  error.kind = none | not_found | size_limit | unavailable | io_error
```

这不是对物理 discard 的第二次计数：

- `storage.operation{operation=discard}` 回答 MinIO 删除调用是否成功；
- `storage.compensation` 回答事务回滚清理是否成功；
- 两个指标族不可求和，也不能共同作为 `storage attempts` 的分母。

不建议在当前阶段创建 `orphan.count` Gauge。一次补偿失败只表示“可能残留孤儿对象”；在没有扫描、确认、清理和 resolved 状态的 reconciliation 数据源前，Gauge 无法在人工清理后正确下降。应当用 compensation error Counter 告警，用日志定位；将来有 reconciliation 子系统后，再由该子系统提供权威 Gauge。

### 5.3 `open` 的边界

第一版 Timer 应忠实保留当前语义：

```text
open start = 进入 ResourceStorage.open
open end   = 获取 GetObjectResponse，或在 stat/getObject 阶段失败
```

它衡量“对象流建立耗时”，不是客户端下载耗时。若未来需要真实传输指标，应在 `ResourceContent.inputStream` 的 EOF/close/error 处单独设计 stream telemetry，并区分：

- expected bytes；
- actually transferred bytes；
- completed / cancelled / error。

不能在 `open()` 返回时把 `responseLength` 当成已传输字节，否则取消下载和中途断流会被误记为完整成功。

## 6. Langfuse 中允许记录什么

### 6.1 允许的场景

当资源存储是 Agent 执行中的重要业务步骤，例如“生成图片后持久化并返回 ArtifactReference”，可以在 Agent trace 中记录应用级 `artifact_capture` 或 `persistence` child span：

```text
name: artifact.capture
attributes:
  h.observation.kind = artifact_capture
  h.artifact.id      = <stable resourceId>
  h.artifact.role    = input | output | intermediate
  h.artifact.mime_type
  h.artifact.size_bytes
  h.outcome          = success | error
```

它用于回答“这次 Agent Run 产生了什么 Artifact、在哪一步失败”，不是用来统计 MinIO 全站可用率。

### 6.2 不允许的场景

- 不把普通用户下载/预览的每个 `open` 强行塞进 Langfuse；
- 不因“可统计”而为每个 MinIO SDK 调用创建 Langfuse observation；
- 不把二进制内容、object key、bucket、预签名 URL 发送到 Langfuse；
- 不把同一操作同时手工埋 span、又由 MinIO/AWS SDK 自动埋相同 CLIENT span；
- 不把 Agent 业务级 `artifact_capture` span 和底层 S3 CLIENT span视为同一指标来源。

OpenTelemetry 当前确实提供 AWS S3 client span 语义，但状态仍为 Development，并把它定义为 `CLIENT` span。[OpenTelemetry S3 client spans](https://opentelemetry.io/docs/specs/semconv/object-stores/s3/)

因此若未来引入通用 APM，可以在独立 exporter/scope 下发送 S3 CLIENT spans；Langfuse exporter仍只筛选 h-agent Agent observation scope，避免基础设施噪声进入 LLM trace 平台。

## 7. 避免重复计数

必须采用“一个语义、一个 owner、一次 terminal record”规则：

| 语义 | 唯一 owner | 记录时点 |
| --- | --- | --- |
| save/open/discard 物理操作 | `MinioResourceStorage` | 方法成功返回或异常确定后 |
| rollback 补偿工作流结果 | `TransactionalResourceWriteCoordinator` | `discardQuietly` 成功/失败确定后 |
| Agent Artifact 业务步骤 | Agent observability adapter | ArtifactReference 形成或业务失败后 |
| 补偿失败详情日志 | Coordinator 的 telemetry adapter | 与补偿 error Counter 同一出口 |

禁止：

- 业务 Service、Coordinator、MinIO Adapter 同时为同一个 save 增加同名 Meter；
- Timer 已产生 count 后再额外增加 save success/failure Counter；
- retry 的每个内部 attempt 和顶层 logical operation 使用同一个指标名；
- 日志采集器再从 ERROR 文本反推并重复生成相同 compensation Counter；
- 用 Langfuse observation count 与 Prometheus operation count 相加。

如果将来确实需要区分 logical call 和 physical retry attempts，应使用两个明确的指标族，并在名称和文档中声明边界。

## 8. 不影响业务的约束

新的存储 telemetry 必须比当前实现更弱耦合：

1. Meter 未配置、Prometheus 不可达或 scrape 停止时，资源业务照常运行；
2. 请求线程只更新进程内 Meter，不执行网络上报；Prometheus 从独立端点拉取；
3. telemetry API 必须提供 Noop 实现，记录失败不得覆盖业务返回值或原始异常；
4. 不新增 MinIO HEAD/GET/LIST 请求来补全 telemetry；
5. 不读取、复制或上传文件内容用于指标；
6. 不为了 span 改变流的订阅、背压、scheduler、取消或 close 语义；
7. 不把 MinIO 故障直接绑定为整个应用 readiness 失败；告警和资源能力降级由运维策略处理；
8. exporter 队列必须有界，停机 flush 有超时，失败只记录 telemetry 自身诊断信息。

Spring Boot 官方把 Micrometer 作为应用 Metrics 门面，并能自动配置 Prometheus 或 OTLP registry；Prometheus 模式由 `/actuator/prometheus` 提供 scrape 格式。[Spring Boot Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)

对当前 Spring Boot 项目，官方还明确建议业务指标使用 Micrometer；Spring Boot 不自动导出 OpenTelemetry metrics，若使用 Micrometer，可以再通过 OTLP registry 发往真正支持 Metrics 的后端。[Spring Boot Observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html)

这也是选择 Micrometer 而不是直接扩展当前 `SdkTracerProvider` 为 Langfuse MeterProvider 的主要项目契合理由。

## 9. 告警建议

第一版至少应有两类规则：

1. **资源操作高错误率**：按 operation 计算 error count / total count，设置持续窗口和最小流量，避免低流量单次错误产生噪声；
2. **补偿删除失败**：`increase(compensation{outcome="error"}[window]) > 0`，因为它可能产生孤儿对象并需要人工处理。

补偿错误告警应链接到现有 MinIO runbook；具体 `resourceId` 通过同一时刻的脱敏 ERROR 日志查找，不能成为 Metric label。

Prometheus 官方建议告警优先针对用户可见症状；对非用户可见但严重且确实需要人工处理的失败也可以告警，并要求监控系统自身被监控。[Prometheus Alerting](https://prometheus.io/docs/practices/alerting/)

Counter 是单调累计值，进程重启允许 reset；图表和告警应使用 `rate()` 或 `increase()`，而不是比较原始累计数。Prometheus 官方也强调 raw counter 通常没有直接意义，应计算增长率。[Prometheus Instrumentation：Counter](https://prometheus.io/docs/practices/instrumentation/#counter-vs-gauge-summary-vs-histogram)

## 10. 建议的迁移方向

这不是实现计划，但后续设计应遵循以下顺序：

1. 在资源存储基础设施模块内，用具体的 `ResourceStorageMeters` 封装 Meter 名称、低基数标签、计时和 first-wins 终态；它不是业务 Port，也不把 Micrometer 暴露给领域层；
2. 由 `ResourceStorageMeters` 基于 Micrometer `MeterRegistry` 取代所有 `LongAdder` 和 `snapshot()` 生产逻辑；
3. 用 Timer 统一 save/open/discard 的 count、duration、outcome、error kind；
4. 保留单独的 compensation Counter 和一次脱敏 ERROR 日志；
5. 引入 Actuator + Prometheus registry，但只开放必要 endpoint；
6. 用 MeterRegistry/SimpleMeterRegistry 断言测试，不再通过自定义 snapshot record；
7. Langfuse 只补充 Agent Artifact 业务 span，不接 storage metrics exporter；
8. 为 metrics、Langfuse traces、logs 分别设置 endpoint，禁止共用一个会自动派生 `/v1/*` 的 Langfuse base endpoint。

## 11. 验收判断

后续实现只有同时满足以下条件才算合理：

- Langfuse 关闭或不可达，MinIO 业务行为与响应语义完全不变；
- Prometheus 关闭或不可达，MinIO 业务行为与响应语义完全不变；
- 每次 Adapter logical operation 在 operation Timer 中只增加一次 count；
- 每次 rollback cleanup 在 compensation Counter 中只增加一次 count；
- 失败率可由同一个 operation Timer 的 total/error 序列计算；
- `resourceId`、key、bucket、用户/会话/trace ID 不出现在 Metric labels；
- `openSuccess` 文档和测试明确为“流建立成功”，不冒充完整下载成功；
- compensation error 同时产生一次 Counter 和一次脱敏日志，但不会重复抛错；
- Langfuse 中没有普通下载、预览和底层 MinIO 调用造成的高噪声 observations；
- Metrics exporter 没有指向 Langfuse `/api/public/otel/v1/metrics`。

## 12. 最终决策摘要

| 选项 | 判断 | 原因 |
| --- | --- | --- |
| 用 Langfuse observations 完全替代 `ResourceStorageMetrics` | 拒绝 | 采样、异步、非全量、非 OTLP Metrics backend、成本和噪声均不合理 |
| 保留现有 `LongAdder`，同时增加 Langfuse spans | 仅可作为临时态 | 仍无生产查询、时序、跨实例聚合和标准告警 |
| 直接用 OTel Metrics SDK 发往 Langfuse | 拒绝 | Langfuse Metrics 路由不摄取 body，会静默丢数 |
| Micrometer Metrics → Prometheus，Langfuse → Agent traces | **采用** | 符合 Spring Boot、OpenTelemetry 和 Prometheus 的标准边界，可替换后端且不污染业务 |
