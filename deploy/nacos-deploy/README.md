# Nacos 部署（Docker，单机）

按官方 [Docker quickstart](https://nacos.io/docs/latest/quickstart/quick-start-docker/) 部署，单机模式 + 内置 Derby，**不依赖外部 MySQL**。镜像实测为 `v3.2.4`（镜像 label 版本；daocloud 源只有 `latest`）。

```
浏览器/你的 Java 应用
   │ 控制台 HTTP          :8081  → 容器 8080（3.x 起控制台独立端口，不再挂 8848）
   │ 服务端 HTTP API      :8848  → /nacos/v3/...（配置中心 / 注册中心）
   │ 客户端 gRPC 长连接   :9848  → = 8848+1000，2.x 起的客户端必需
   ▼
nacos 容器（standalone，Derby 落盘到卷 nacos_nacos-data）
```

## 日常命令

```bash
./start.sh                 # 端口预检 → 启动 → 探活 → 打印访问地址
./stop.sh                  # 停止（容器、数据都保留）
./stop.sh --remove         # 删除容器（数据卷保留）
docker compose logs nacos -f --tail 100
```

- 控制台：`http://<宿主机IP>:8081/`（3.x 新版 UI 路径为 `/next/`，访问根路径会自动跳转）
- **控制台账号：首次打开按页面提示创建管理员**（全新 Derby 无 `nacos/nacos` 默认账号，3.x 会引导初始化）
- 客户端鉴权：`.env` 的 `NACOS_AUTH_ENABLE=false`（官方单机默认），Java 客户端直接连即可；改成 `true` 后客户端需配 username/password
- 数据重置：`docker compose down && docker volume rm nacos_nacos-data`

## 端口

| 端口 | 用途 |
|------|------|
| 8848 | 服务端 HTTP API（配置 + 注册） |
| 9848 | 客户端 gRPC 长连接（必须映射，否则客户端连上后立即断开） |
| 8081 → 容器 8080 | 控制台。宿主 8080 已被 xxljob-admin 占用，故改用 8081 |

## 客户端接入

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: <宿主机IP>:8848
      config:
        server-addr: <宿主机IP>:8848
```

- 地址必须是宿主机局域网 IP，不是 `127.0.0.1`（容器内回环不通）
- 若用 Spring Cloud Alibaba，请确认其 `nacos-client` 与 Nacos 3.x 服务端兼容（本部署只验证了 HTTP API，Java 客户端兼容性未实测）
- 网关/防火墙需放行 8848、9848

## 验证（官方 quickstart 同款）

```bash
# 注册一个临时实例（ephemeral，15s 无心跳自动清除）
curl -X POST 'http://127.0.0.1:8848/nacos/v3/client/ns/instance?serviceName=quickstart.test.service&ip=127.0.0.1&port=8081'
# 查询
curl -X GET 'http://127.0.0.1:8848/nacos/v3/client/ns/instance/list?serviceName=quickstart.test.service'
# 控制台
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/   # 期望 302
```

启动成功日志：`Nacos started successfully in stand alone mode with embedded storage in ... ms`

## 排障

| 症状 | 处理 |
|------|------|
| 控制台 8081 报 `Connection reset by peer`，容器内却在 8081 监听 | **踩过的坑**：`.env` 里不能出现 `NACOS_CONSOLE_PORT`，`env_file` 会把它传进容器，Nacos 据此改掉控制台内部端口。宿主侧端口变量用 `NACOS_CONSOLE_HOST_PORT` |
| 容器启动即退出，日志 `env NACOS_AUTH_TOKEN must be set...` | 三个鉴权变量缺一，镜像直接 `exit 255`；`NACOS_AUTH_TOKEN` 须 Base64 且解码后 >32 字节 |
| 控制台登录提示 `User not found!` | 3.x 新库没有默认账号，先在控制台完成管理员初始化，不要试 `nacos/nacos` |
| 客户端注册成功但连接不断断开 | 9848 没映射或被防火墙拦截 |
| 客户端连 `127.0.0.1` 不通 | 用宿主机局域网 IP |
| `./start.sh` 报端口被占用且容器已在运行 | 脚本已对"已在运行"跳过预检；仍报占用则是别的进程，`lsof -i :端口` 查 |
| 内存紧张 | `.env` 的 `JVM_XMS/JVM_XMX`（默认 512m）+ `NACOS_MEM_LIMIT`（1g） |

## 与官方文档的差异

- 官方 `docker run` 的 8080 控制台端口在本机被 xxljob-admin 占用 → 改映射 8081
- 官方用 `docker run` 一次性容器；这里按本仓 `*-deploy` 规范做成 compose + 数据卷 + start/stop 脚本
- 官方 `latest` 在 daocloud 源上即 v3.2.4；若以后需要钉版本，把 compose 的镜像 tag 改成具体版本号
