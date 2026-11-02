# Java 服务接入 Nacos

`backend` 和 `other-agents` 通过共享 Maven 模块 `service-registry` 使用 Nacos Java SDK 3.2.4。模块自动适配 Spring Boot 4 的 Servlet / WebFlux 应用，不引入 Spring Cloud。

本次接入普通服务实例的注册、注销和健康实例查询。A2A 继续使用现有固定地址，AgentCard、MCP、配置中心和 Python LiveKit Worker 均未迁移。

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
- 不会为已存在的 A2A / MCP 调用自动替换地址，也不提供请求重放或自动切换实例。

后续业务可以直接注入统一发现入口：

```java
import com.h.registry.NacosServiceDiscovery;

// 构造器注入 NacosServiceDiscovery discovery
var instances = discovery.healthyInstances("other-agents");
// 每个实例提供 ip()、port()、metadata()；仅返回健康、启用且权重大于 0 的实例。
// 没有实例时返回空列表；调用方决定如何选址及处理错误。
```

未来新增 Spring Boot 服务时，在根 POM reactor 中添加服务模块，并依赖 `com.h:service-registry:0.0.1-SNAPSHOT`，配置唯一的 `spring.application.name` 和下述连接参数即可自动注册，无需复制生命周期代码。

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
| `NACOS_TIMEOUT_MS` | 单次 naming RPC 请求超时，默认 5000ms；不等同于整个 SDK 初始化或重连的总时限 |

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
