# Nacos MCP 一手资料核查笔记

调研日期：2026-11-13。目标基线：Nacos Server / Java SDK **3.2.4**；Router 单独按源码 commit 核查。本文只记录官方资料和源码证据，集成方案见 [项目评估](2026-11-13-nacos-mcp-assessment.md)。未运行 Nacos、Router 或端到端 MCP 集成测试。

## 1. 注册中心与执行进程的边界

Nacos 提供 MCP 服务描述、工具定义、版本、端点的登记与发现；现有 HTTP/RPC API 转 MCP 的路径还需网关承担协议转换。官方自动注册方案依赖 Spring AI Alibaba 或 Python Wrapper；因此“登记到 Nacos”本身不会把现有 Java 工具执行进程搬进 Nacos，也不会自动给任意手写 MCP Server 加上动态治理。

来源：[3.2 自动注册与发现手册](https://www.nacos.io/docs/v3.2/manual/user/ai/mcp-auto-register/)、[3.2 AI Registry 概览](https://nacos-group.github.io/en/docs/v3.2/manual/user/ai/ai-registry-overview/)。

3.2.4 与 latest 文档必须分开。官网 latest MCP Registry 已描述 3.3 的 draft / submit / publish / online / offline 生命周期；不应直接作为当前 3.2.4 的能力承诺。

来源：[3.2.4 发布说明](https://github.com/alibaba/nacos/releases/tag/3.2.4)、[latest MCP Registry](https://nacos.io/en/docs/latest/manual/user/ai/mcp-registry/)。

## 2. 精确 Java SDK 3.2.4 的可用接口

已阅读本机 Maven 缓存 `nacos-api-3.2.4-sources.jar` 与 `nacos-client-3.2.4-sources.jar`，以下可在官方对应 tag 源码复核：

| 需求 | 接口 / 模型及边界 |
|---|---|
| 发布定义 | `AiService.releaseMcpServer(serverSpecification, toolSpecification, endpointSpecification)`；另有包含 resourceSpecification 的重载 |
| 版本 | `serverSpecification.versionDetail.version` 必填；API 注释说明同名同版本再次 release 不覆盖已有定义，不能依靠每次重启发布来更新旧版本 |
| 注册运行实例 | `registerMcpServerEndpoint(mcpName, address, port, version)`；定义发布和实例登记是两个动作 |
| 注销实例 | `deregisterMcpServerEndpoint(mcpName, address, port)`；API 注释要求由同一 client 注册 |
| 查询 | `getMcpServer(mcpName, version)`；省略版本查询最新版本 |
| 订阅 | `subscribeMcpServer(mcpName, version, listener)`，以及对应 unsubscribe；注册监听后可能立即收到当前快照，应避免初始化与回调重复安装 |
| 地址和路径 | `McpServerRemoteServiceConfig.exportPath`；返回的 `McpEndpointInfo` 包含 protocol、address、port、path、headers |
| 直接地址或注册中心引用 | `McpEndpointSpec.type` 为 direct 或 ref；direct 数据包含 address/port，ref 数据包含 namespaceId/groupName/serviceName |
| 工具治理元数据 | `McpToolSpecification.toolsMeta`、`McpToolMeta.enabled`、invokeContext、templates；这些字段须由执行方或代理读取和落实 |

来源：[AiService.java，3.2.4](https://github.com/alibaba/nacos/blob/3.2.4/api/src/main/java/com/alibaba/nacos/api/ai/AiService.java)、[NacosAiService.java，3.2.4](https://github.com/alibaba/nacos/blob/3.2.4/client/src/main/java/com/alibaba/nacos/client/ai/NacosAiService.java)、[MCP 模型目录，3.2.4](https://github.com/alibaba/nacos/tree/3.2.4/api/src/main/java/com/alibaba/nacos/api/ai/model/mcp)。

订阅不是可以假定即时到达的服务端推送。3.2.4 的 `NacosMcpServerCacheHolder` 使用定时任务轮询，间隔来自 `AI_MCP_SERVER_CACHE_UPDATE_INTERVAL`；3.2 官方 Java SDK 手册说明默认 10000ms。生产方案需定义传播延迟以及失联时沿用旧快照还是拒绝新增调用。

来源：[NacosMcpServerCacheHolder.java，3.2.4](https://github.com/alibaba/nacos/blob/3.2.4/client/src/main/java/com/alibaba/nacos/client/ai/cache/NacosMcpServerCacheHolder.java)、[3.2 Java SDK 手册](https://nacos-group.github.io/en/docs/v3.2/manual/user/java-sdk/usage/)。

## 3. 多手工端点适配判断

官方 Spring AI Alibaba 示例使用单个 `spring.ai.mcp.server.name/version` 与自动装配工具。该示例没有覆盖本项目“一进程多个手工 McpAsyncServer、独立 URL 与 Bearer token”的场景。**判断**：不能仅增加 starter 就宣称三个端点已正确独立登记；当前更可控的路径是直接使用上述 SDK，为每个端点分别发布一个 MCP 服务定义，保留现有 transport 与工具映射。此处是基于官方示例和 SDK 模型的工程判断，尚未验证任何具体 starter 版本的多端点实现。

来源：[3.2 自动注册与发现手册](https://www.nacos.io/docs/v3.2/manual/user/ai/mcp-auto-register/)、[AiService.java，3.2.4](https://github.com/alibaba/nacos/blob/3.2.4/api/src/main/java/com/alibaba/nacos/api/ai/AiService.java)。

## 4. Router 的实际边界

官方 Router 有两种模式：router 模式暴露 search_mcp_server、add_mcp_server、use_tool；proxy 模式为目标服务提供代理，并可转换 SSE/stdio 为 Streamable HTTP。router 模式会改变模型看到的工具结构，不能直接等同于本项目现有逐工具鉴权、审批、观测语义。

来源：[3.2 Router 手册](https://www.nacos.io/en/docs/v3.2/ecology/use-nacos-mcp-router/)、[官方 Router 仓库](https://github.com/nacos-group/nacos-mcp-router)。

本次通过 GitHub API 固定 Router main 的 commit 为 `2966844f05ce80ac2520d141bc59922d88588af8`。核查 Python 实现得到：

- `McpSseTransport` 和 `McpStreamableHttpTransport` 保存注册配置中的 `self.headers`，但 initialize / list_tools / call_tool 只把 `clean_headers(client_headers)` 传给下游。静态注册的 Authorization 不会在这些方法中被合并进去。官方仓库 [PR #59](https://github.com/nacos-group/nacos-mcp-router/pull/59) 正在修复这一点，本次读取页面状态仍为 Open。
- 客户端请求头会转发，但“上游一个 Authorization”不能自动变成多个下游各自的 token。项目订单调用还需要每次生成的业务上下文和 W3C trace headers；不能只验证 tools/list 成功就确认可替换直连。
- `sse_transport.py` 的 list_tools 在 info 日志中输出完整 client_headers。带 token 接入前应选择已修复版本或自行修复、复核。
- `router.py` 会在列出工具时过滤 toolsMeta.enabled；然而 `use_tool` 和 proxy 的 call_tool 路径直接进入 `execute_tool`，该文件未在调用点再次检查禁用状态。列表隐藏不能作为服务端拒绝调用的证明，必须对直接指定禁用工具名的请求进行实测。
- HTTP transport 每次操作都新建 ClientSession 并 initialize；因此不能假定它保留本项目原有会话复用、会话级状态或完整 MCP 协议能力。

源码依据：[mcp_transport.py](https://github.com/nacos-group/nacos-mcp-router/blob/2966844f05ce80ac2520d141bc59922d88588af8/src/python/src/nacos_mcp_router/mcp_transport.py)、[sse_transport.py](https://github.com/nacos-group/nacos-mcp-router/blob/2966844f05ce80ac2520d141bc59922d88588af8/src/python/src/nacos_mcp_router/sse_transport.py)、[streamable_http_transport.py](https://github.com/nacos-group/nacos-mcp-router/blob/2966844f05ce80ac2520d141bc59922d88588af8/src/python/src/nacos_mcp_router/streamable_http_transport.py)、[router.py](https://github.com/nacos-group/nacos-mcp-router/blob/2966844f05ce80ac2520d141bc59922d88588af8/src/python/src/nacos_mcp_router/router.py)。以上是源码审阅结论，不是已部署版本的实测结论；部署前需固定具体发行版及其 commit。

## 5. 当前建议与验证范围

优先采用“现有 MCP Server + Nacos 发布/发现 + backend 直连”，把 Nacos 放在管理和发现链路中；保留业务调用时的 Bearer、订单上下文、审批与观测。工具禁用和描述覆盖若有需求，需显式实现订阅应用和调用检查。Router 适合作为后续单独 PoC，不宜直接替换当前所有 MCP 调用。

验收至少覆盖：三个端点身份/工具隔离；错误或缺失 token；订单调用的用户上下文及 trace 传递；版本更新和回滚；实例下线、Nacos 失联；禁用工具被直接调用；旧连接排空。本文未运行这些验证。


## 2026-11-16 实施补充

原生注册发现已实施，运行方式见 [MCP 接入手册](../runbooks/nacos-mcp.md)。一次性 Nacos 3.2.4 实测发现：同名同版本 release 返回 409，而非正常返回；应用现在捕获冲突并核对已有定义。Naming-backed Endpoint 的 protocol 可能为空，按普通 HTTP 处理。注册成功到地址可发现之间有短暂传播延迟。以上补充优先于本笔记早期基于 SDK 注释的推断。
