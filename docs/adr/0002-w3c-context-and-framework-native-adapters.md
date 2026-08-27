---
status: accepted
---

# 使用 W3C Context 和框架原生接缝形成统一 Trace

LangChain4j、AgentScope、A2A 和 MCP 共享同一语义模型，但通过各自真实执行接缝建立 Observation；业务代码不操作 Span。跨服务因果关系只通过 W3C `traceparent`、`tracestate` 和受限 Baggage 传播，不把 Trace ID 写入 A2A/MCP 业务载荷；AgentScope 的产品响应与后置维护分别进入 Primary Trace 和按需创建的 linked Maintenance Trace，以保持业务终态与框架完整生命周期同时正确。
