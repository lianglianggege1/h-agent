---
status: accepted
---

# Agent Trace 与资源运行指标使用不同信号和后端

Langfuse 的 Metrics 是对已摄取 Trace、Observation 和 Score 的派生分析，不能接收或替代 MinIO 所需的不采样运行指标；用 sampled Observation 计数会漏掉独立上传、预览、下载、补偿以及丢失的 Trace。H Agent 因此用 OTel Trace → Langfuse 解释单次 Agent 因果过程，用 Micrometer → Prometheus 统计资源操作次数、错误和延迟，用脱敏结构化日志记录补偿删除失败；旧 `ResourceStorageMetrics` 的 LongAdder/snapshot 被 Micrometer Meter 取代，但 MinIO 原始调用不展开成 Langfuse Span。代价是维护专用 Metrics 后端，换来符合信号语义的时序聚合、低基数告警和不污染 Agent Trace 的结构。
