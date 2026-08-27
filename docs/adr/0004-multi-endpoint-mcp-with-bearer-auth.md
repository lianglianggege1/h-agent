---
status: accepted
---

# 多 MCP Endpoint 手工装配并内置 Bearer 认证

other-agents 需要把不同工具集以多个按 URL 区分的 MCP Server 暴露单元（MCP Endpoint，如 /test1/mcp、/test2/mcp）对外提供，且每个 Endpoint 拥有独立的服务身份、工具集与认证凭证。Spring AI 的 MCP Server 自动装配只支持单端点，因此显式关闭它（`spring.ai.mcp.server.enabled=false`），改为手工装配：每个 Endpoint 一个 `WebFluxStreamableServerTransportProvider`（`messageEndpoint` 指定路径）加一个 `McpAsyncServer`，合并各自的 `RouterFunction`。认证不引入 Spring Security，而是利用 MCP SDK transport 内置的 `ServerTransportSecurityValidator` 钩子，对每个 Endpoint 校验 `Authorization: Bearer <token>`，失败抛 401 并由 transport 直接转成 HTTP 401；缺 token 的 Endpoint 启动即失败，不允许匿名暴露。代价是放弃官方自动装配的便利并手工管理 `McpAsyncServer` 生命周期，换来多端点、每端点独立认证与工具隔离；工具与 Endpoint 的映射采用代码驱动而非配置编排。
