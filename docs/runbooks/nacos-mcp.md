# Nacos MCP 注册与直连

`other-agents` 把三个 MCP 服务定义、工具 schema 和运行地址登记到 Nacos 3.2.4，backend 发现后通过原有 Streamable HTTP 直接调用。Nacos 不执行 Java 工具，不代理业务请求。MCP Bearer、订单身份和 W3C 传播保持原有方式。

## 启动与配置

先启动 Nacos，再启动 other-agents，最后启动 backend。连接参数沿用 [服务注册手册](nacos-service-discovery.md) 的 namespace ID、账号与广播 IP/端口；账号还需有 MCP 定义发布、实例注册和查询权限。HTTP 服务绑定后发布三个定义，再注册运行地址；发布或注册失败会中止 other-agents 启动并清理已注册的运行地址。关闭时保留定义，只注销本进程实例。

| 环境变量 | 默认值 / 用途 |
| --- | --- |
| `NACOS_MCP_ENABLED` | `true`，两个服务的 MCP 注册/发现开关，与 A2A 开关独立 |
| `MCP_TEST1_VERSION` / `MCP_TEST2_VERSION` | `0.0.1`，两个测试服务的定义版本 |
| `ORDER_MCP_VERSION` | `0.0.1`，订单服务两端版本 |
| `OTHER_AGENTS_MCP_ENABLED` | `false`，是否向原有通用聊天 Agent 提供 test1 工具；开启注册发现不会自动开放工具 |
| `OTHER_AGENTS_MCP_NAME` | `test1-mcp`，通用客户端允许连接的逻辑服务 |
| `OTHER_AGENTS_MCP_VERSION` | 默认跟随 `MCP_TEST1_VERSION`，可固定其他允许的版本 |
| `ORDER_MCP_NAME` | `orders-mcp`，订单客户端的逻辑服务 |
| `MCP_TEST1_TOKEN` / `MCP_TEST2_TOKEN` / `ORDER_MCP_TOKEN` | 各端点自己的 Bearer，沿用既有配置，两端必须一致 |

三个定义为 `test1-mcp`（`/test1/mcp`）、`test2-mcp`（`/test2/mcp`）、`orders-mcp`（`/orders/mcp`）。测试工具和订单工具保持隔离。工具定义从实际 ToolCallback 生成，不维护第二份手写 schema；token 不发布到注册元数据。

Nacos 3.2.4 对已存在的同名同版本 release 会返回 409。应用读取该版本，对比协议、导出路径、工具名与 input schema；一致则继续注册，不一致则拒绝启动。变更工具契约或路径时提高版本。描述和启停规则没有接入运行期同步，不能靠控制台隐藏工具来撤销业务调用权限。

若暂时使用固定地址，两个服务设置 `NACOS_MCP_ENABLED=false`；backend 使用 `OTHER_AGENTS_MCP_URL` 和 `ORDER_MCP_URL`（保留 localhost 开发默认值）。`NACOS_ENABLED=false` 关闭整个注册模块，也会使用固定地址。已经启用 Nacos 发现时，发现错误和无实例会明确报错，不自动回退 localhost。

新工作树没有复制原目录被 Git 忽略的 `.env`；启动时需提供本环境的连接与认证变量。

## 会话与故障行为

客户端在每次工具操作前同步查询指定版本，通过返回的协议、主机、端口与路径组成 URL。3.2.4 naming-backed Endpoint 的 protocol 可能为空，按其普通 HTTP 入口处理；显式 https 保留。没有使用订阅缓存，但服务端运行地址仍有短暂传播延迟，因此刚注册完成的服务可能暂时查不到实例。

通用客户端优先继续使用当前仍存在的地址，一个 client 固定一个 MCP 会话及 URL。地址变化时为新调用创建新 client；旧 client 等在途调用完成后才关闭，异步调用同样排空。订单客户端保持每次业务调用建立/关闭 MCP client，在初始化前发现地址；业务身份继续在每次请求构造，避免用户串用。

没有自动重放工具调用。请求已经发出但响应丢失时，尤其是 `order_command`，应按既有 commandId / revision 机制核对结果。发现仅反映 Nacos 当前返回的实例，不额外做 HTTP 探活；进程异常退出到实例摘除之间仍可能收到一次连接失败。切换实例会重新初始化 MCP，不迁移服务端会话状态。

第一阶段没有接入 Router，也没有实现资源订阅等有状态能力跨实例迁移。

## 验证

使用项目要求的 JDK 26。日常测试不会连接共享 Nacos；可选集成测试必须指向一次性 Nacos 3.2.4，因为它会创建持久定义。

```bash
mvn -pl backend,other-agents -am test \
  -Dtest='NacosMcp*Test,NacosRegistryLifecycleTest,DiscoveredMcpClientTest,OtherAgentsMcpToolProviderTest,MultiEndpointMcpProtocolIT,OrderMcpProtocolTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# 仅对一次性环境执行；SDK gRPC 端口需映射为 HTTP 端口 + 1000。
NACOS_MCP_TEST_ADDR=127.0.0.1:18848 mvn -pl other-agents -am test \
  -Dtest=NacosMcpRegistryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

测试覆盖定义/路径隔离、同版本重启、部分失败清理、独立 AI client 开关、真实 Nacos 三端点发现、实际加法及带身份订单调用、错误凭证拒绝、注销后无地址、客户端换址排空与不重放命令。调用链和订单 operator 规则由原有协议及观测测试回归。

共享环境上线后在 AI Registry MCP 列表确认三项定义和运行实例，再用产品原有入口发起调用。仅看到定义不代表实例可用。需要回滚时固定旧版本，并确保仍有该版本实例；仅修改 backend 版本不会自动启动旧工具进程。
