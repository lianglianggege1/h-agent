# Java 服务接入 Nacos

`backend` 和 `other-agents` 通过共享 Maven 模块 `service-registry` 使用 Nacos Java SDK 3.2.4。模块自动适配 Spring Boot 4 的 Servlet / WebFlux 应用，不引入 Spring Cloud。

两个应用接入普通服务实例的注册、注销和健康实例查询。A2A 同时使用 Nacos 原生 AI Registry 发布 AgentCard、注册 Endpoint 并发现地址；MCP、配置中心和 Python LiveKit Worker 仍使用原有方式。

## 连接与启动

| 项目 | 默认值 |
| --- | --- |
| Nacos 控制台 | `http://169.254.210.181:8081/next/` |
| SDK 服务地址 | `169.254.210.181:8848` |
| SDK gRPC 端口 | `9848`，需与 8848 一起放行 |
| Namespace ID | `bc2a5f7e-0ab7-452c-849c-13527706651f`（`h_agent`） |
| Group / Cluster | `DEFAULT_GROUP` / `DEFAULT` |
| 服务名 | `spring.application.name`：`backend`、`other-agents` |
| HTTP 端口 | backend `8083`，other-agents `8082` |

backend 从原来的 8081 调整为 8083，避免和本机 Nacos 控制台冲突。前端 rewrites 与 Copilot Runtime 的默认后端地址已同步调整。自定义 `BACKEND_API_BASE_URL` 或实时语音 Worker 的 `JAVA_VOICE_URL` 时，需同步使用实际后端端口；前端需重新构建。

两个服务都会读取仓库根目录 `.env`（也支持操作系统环境变量）。在 `.env` 中填写已有的、具有目标 namespace 服务注册与查询权限的 Nacos 账号：

```dotenv
NACOS_USERNAME=your-service-account
NACOS_PASSWORD=your-password
NACOS_SERVER_ADDR=169.254.210.181:8848
NACOS_NAMESPACE=bc2a5f7e-0ab7-452c-849c-13527706651f
```

namespace 必须填写 ID。控制台的 8081 端口不是 SDK 地址。SDK 自动处理登录、token 刷新、连接保活和重连。

```bash
# 在仓库根目录使用 JDK 26
mvn -pl backend,other-agents -am package -DskipTests
java -jar other-agents/target/other-agents-0.0.1-SNAPSHOT.jar
# 另一个终端；模型、数据库等其他必填项仍按原有说明配置
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar
```

如需覆盖 HTTP 监听端口，在对应进程设置 `SERVER_PORT`。不要在两个服务共用的 `.env` 中设置相同的 `SERVER_PORT`。

## 注册生命周期与发现

- HTTP 服务端口绑定后，同步注册临时实例；注册调用失败会抛出异常，中止 Spring 应用启动并关闭服务端口，不会进入 Ready 状态。
- 正常关闭时主动注销实例，然后关闭 NamingService；进程异常退出则由 Nacos 根据临时实例的连接状态摘除。
- 单独的 management HTTP 端口不会覆盖业务端口的注册。
- 启动成功后的断连由 SDK 自动重连并恢复注册。发现查询使用 SDK 订阅缓存；缓存可能短时滞后，没有可用缓存时错误传递给调用方。
- A2A 通过独立 AiService 查询当前 Endpoint，MCP 仍使用固定地址。A2A 请求失败后不自动重放，避免重复执行模型生成。

后续业务可以直接注入统一发现入口：

```java
import com.h.registry.NacosServiceDiscovery;

// 构造器注入 NacosServiceDiscovery discovery
var instances = discovery.healthyInstances("other-agents");
// 每个实例提供 ip()、port()、metadata()；仅返回健康、启用且权重大于 0 的实例。
// 没有实例时返回空列表；调用方决定如何选址及处理错误。
```

未来新增 Spring Boot 服务时，在根 POM reactor 中添加服务模块，并依赖 `com.h:service-registry:0.0.1-SNAPSHOT`，配置唯一的 `spring.application.name` 和下述连接参数即可自动注册，无需复制生命周期代码。

## A2A 注册与调用

```text
other-agents ── 发布 AgentCard / 注册 Endpoint ──→ Nacos AI Registry
backend      ── 按 Agent 名称和版本查询 ──────────→ Nacos AI Registry
backend      ── A2A JSON-RPC 直接调用 ────────────→ other-agents
```

`other-agents` HTTP 端口绑定后，发布 `creative-writer`、`audience-editor`、`style-editor` 三张 AgentCard，注册各自的 JSONRPC Endpoint；发布或注册失败会中止启动。关闭应用时注销本进程 Endpoint，随后关闭 AiService。AgentCard 定义保留在 Nacos，重启时相同版本幂等发布；修改能力描述或接口需提升版本。多个进程可共享同一版本定义并各自注册 Endpoint。

backend 默认启用 A2A 故事协作 Agent，按 `0.1.0` 版本查询 Nacos `SERVICE` 类型的 Card。初始化时从发现的地址读取 HTTP AgentCard 构造 LangChain4j 代理，每次发送 JSON-RPC 前再次从 Nacos 查询运行地址；原有 Agent 编排、contextId/taskId 传递及 W3C Trace Context 保留。没有可用地址或 Nacos 查询失败时明确报错，不回退 localhost，不自动重试消息。Nacos 服务端实例视图的变更存在短暂传播延迟。

**启动顺序：先 Nacos，再 other-agents，最后 backend。** backend 创建 A2A 代理时需要远端可用；注册中心无对应 Agent 或远端 HTTP 不可达会导致启动失败。只运行 backend 时设置 `OTHER_AGENTS_A2A_ENABLED=false`；若同时关闭 Nacos，需同时关闭 A2A。`NACOS_A2A_ENABLED=false` 关闭 AI Registry 客户端和发布，backend 的 A2A 功能也必须关闭。

| 环境变量 | 默认值与用途 |
| --- | --- |
| `NACOS_A2A_ENABLED` | `true`，启用 AI Registry 客户端及 other-agents 的 A2A 发布 |
| `OTHER_AGENTS_A2A_ENABLED` | `true`，backend 是否创建 A2A 故事协作 Agent |
| `OTHER_AGENTS_A2A_VERSION` | `0.1.0`，两端应使用相同版本 |
| `OTHER_AGENTS_A2A_PUBLIC_URL` | 留空时按 `NACOS_IP` / `NACOS_PORT` 和实际 HTTP 端口推导；反向代理场景可显式设置可访问的 HTTP(S) 基地址，支持路径前缀 |

普通服务注册仍受 `NACOS_GROUP` 控制；AI Registry 的 Agent 名称按 namespace 隔离，不使用 naming group。两端需连接相同 namespace，账号需具备 AgentCard 发布、Endpoint 注册和查询权限。

Nacos 3.2.4 在没有运行 Endpoint 时会返回 Card 中保存的 URL。因此发布到注册中心的静态 Card 使用保留域名 `unregistered.invalid` 作为未解析地址，在线 Endpoint 会由 Nacos 注入真实地址；backend 识别该域名并报“无可用 Endpoint”。HTTP `.well-known/agent-card.json` 始终提供真实服务地址。不要把注册中心 Card 的静态地址改成某个进程的 IP，否则下线后可能得到旧地址；如已发布同名同版本的旧定义，应提升版本重新发布。

当前三个远端 Agent 均为同步执行、无本地对话记忆，任务一次请求内完成；允许每次调用选择在线 Endpoint。任务记录仍在进程内存中，未来增加异步任务、任务查询或本地会话状态时，需要共享状态或会话亲和路由。

验证时除普通服务列表外，还应在控制台 AI Registry 的 Agent 管理中确认三张 Card 及运行 Endpoint。用故事协作 Agent 发起调用，确认日志中的实际地址；重启 other-agents 后，下一次调用应发现新地址。

以下可选集成测试需显式提供**一次性 Nacos 3.2.4** 的地址；它会发布持久化 Card 定义，不要连接共享环境。测试覆盖三张 Card 发布、重复发布、真实 A2A 调用及 Endpoint 注销；普通测试不连接 Nacos。

```bash
NACOS_A2A_TEST_ADDR=127.0.0.1:18848 mvn -pl other-agents -am test \
  -Dtest=NacosA2ARegistryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

## 可覆盖参数

配置前缀为 `h-agent.registry`；两个服务的 application.yml 已映射以下环境变量。

| 环境变量 | 用途 |
| --- | --- |
| `NACOS_ENABLED` | 默认 `true`。测试或明确不接入注册中心时可设为 `false`；启用时始终执行注册失败即启动失败策略 |
| `NACOS_SERVER_ADDR` | SDK 地址，可使用 Nacos SDK 支持的逗号分隔多地址 |
| `NACOS_NAMESPACE` / `NACOS_GROUP` / `NACOS_CLUSTER` | 环境隔离、分组与集群 |
| `NACOS_USERNAME` / `NACOS_PASSWORD` | 客户端鉴权凭据 |
| `NACOS_IP` | 实例广播 IP，留空时优先选择非回环内网 IPv4 |
| `NACOS_PORT` | 实例广播端口，默认 `0` 表示 HTTP 实际绑定端口；支持随机端口 |
| `NACOS_TIMEOUT_MS` | 单次 naming / AI Registry RPC 请求超时，默认 5000ms；不等同于整个 SDK 初始化或重连的总时限 |

多网卡、VPN、容器部署时应明确设置调用方可访问的 `NACOS_IP`；若宿主机端口与容器监听端口不同，同时设置 `NACOS_PORT`。两个进程的广播地址必须分别与真实入口一致。需要实例元数据时可使用 `h-agent.registry.metadata` 配置 Map。

## 验证和排查

1. 启动两个服务，日志应分别出现 `Registered backend at ...` 和 `Registered other-agents at ...`。
2. 打开控制台，选择 `h_agent` namespace 和 `DEFAULT_GROUP`，确认服务名、IP、HTTP 端口及健康状态。
3. 正常关闭服务，确认对应实例被注销。重启应恢复注册。
4. 注册异常时检查账号权限、namespace ID、8848/9848 网络连通性。发现地址不可达时检查广播 IP/端口。

共享模块测试覆盖真实 Servlet/WebFlux 启动生命周期、注册失败中止启动、关闭清理、端口覆盖及健康实例发现；测试使用模拟 NamingService，不需要真实 Nacos。backend 测试配置及 other-agents Surefire 配置关闭真实注册，避免测试进程污染共享 namespace。

```bash
mvn -pl service-registry -am test
```

参考：[Nacos Java SDK 官方手册](https://nacos.io/docs/latest/manual/user/java-sdk/usage/)。
