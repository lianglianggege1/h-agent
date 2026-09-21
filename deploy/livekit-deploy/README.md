# LiveKit（h-agent 语音服务）

服务于 h-agent 局域网语音功能。配置与 [h-agent/deploy/voice/livekit.yaml](../../h-agent/deploy/voice/livekit.yaml) 保持一致（同一 API key，Java 后端/Worker 依赖它签发 token）。

## 架构

```
浏览器(局域网) ──ws://169.254.210.181:7880──→ LiveKit 容器(bridge+端口映射)
    │                                            │ webhook 事件回调
    │ws 媒体(TCP 7881 / UDP 7882)                ▼
    └──────────WebRTC 直连──────────────→ Java 后端 /internal/livekit/webhook
```

- **bridge + 端口映射**（含 UDP 7882）：注意 **Docker Desktop on macOS 的 `network_mode: host` 绑定的是内部 Linux VM 而非 Mac 本机**，host 模式在 Mac 上端口完全不可达（首版部署实测踩坑），所以这里必须用 bridge。LiveKit 单 UDP 端口 mux（`rtc.udp_port: 7882`）让一个 UDP 映射覆盖全部媒体流
- **无数据卷**：LiveKit 无状态，全部状态在内存（房间/连接）；配置 `livekit.yaml` 在宿主机
- **无 TURN**：局域网直连场景不需要（runbook 已明确）

## 日常操作

```bash
./start.sh     # 预检端口+node_ip → 启动 → 探活 → 打印 Java 后端配置
./stop.sh      # 停止（容器保留）
./stop.sh --remove   # 删容器（配置无损，start.sh 可重建）
```

## 端口

| 端口 | 协议 | 用途 |
|------|------|------|
| 7880 | TCP | 信号/管理 API（浏览器 ws、Java 后端 API 调用都走它） |
| 7881 | TCP | WebRTC 媒体备选通道 |
| 7882 | UDP | WebRTC 媒体主通道 |

host 网络模式下三个端口直接占用宿主机，与 AgentTeams 其他栈（bridge + 端口映射）不同，**冲突检测在 start.sh 预检里**。当前实际部署为 bridge 映射，7880/7881/7882 映射到 Mac 所有网卡。

## 关键配置（livekit.yaml）

| 项 | 值 | 说明 |
|----|-----|------|
| `rtc.node_ip` | `169.254.210.181` | 本机直连网卡地址。ICE 把它告诉浏览器；若换网络，改这里 |
| `keys.h-agent-voice` | `0626...0e74e` | 与 h-agent 仓库一致，**两端必须相同**，secret 即 Java 的 `LIVEKIT_API_SECRET` |
| `webhook.urls` | `http://169.254.228.221:8081/internal/livekit/webhook` | Java 后端回调地址，**按后端实际 IP 改**（2026-09-20 实测推送返回 200） |

内存上限：compose 里 `LIVEKIT_MEM_LIMIT`（默认 768m），改完 `docker compose up -d` 生效。

## Java 后端接入（h-agent 根目录 .env）

```properties
VOICE_ENABLED=true
LIVEKIT_PUBLIC_URL=ws://169.254.210.181:7880
LIVEKIT_API_URL=http://169.254.210.181:7880
LIVEKIT_API_KEY=h-agent-voice
LIVEKIT_API_SECRET=06261308d3342fb0d75630d70647ed24585bba9e09053bc0133473ff56b0e74e
```

## 验证

```bash
# 1. 服务探活
curl http://127.0.0.1:7880

# 2. 签发 token 试连（需要 livekit-cli 或项目 SDK）
# 完整链路验证按 h-agent runbook：浏览器开语音会话，看 ACTIVE 状态 + 双向音频
```

## 排障

| 症状 | 处理 |
|------|------|
| start.sh 报端口被占用 | host 模式端口独占，`lsof -i :端口` 找占用者 |
| start.sh 报 node_ip 不在网卡上 | 换网络了。`ifconfig` 看当前地址，改 `livekit.yaml` 的 `node_ip` |
| 浏览器能连 ws 但无声音 | 媒体不通：查 UDP 7882 是否被防火墙拦（macOS 设置→网络→防火墙）；确认没走 bridge 网络 |
| Java 收不到 webhook | 后端地址配错或 Java 没起；webhook 事件在 `docker logs livekit` 里有记录 |
| ListDispatch 返回 503 `no response from servers`（约 3 秒超时） | LiveKit 1.13 对**不存在的 room** 查 ListDispatch 会走 psrpc 等 room 实例应答，3 秒超时后返回 503 而非 404。不是服务故障：不要对未创建的 room 预查 ListDispatch，`CreateDispatch` 对不存在的 room 直接可用并隐式建房 |
| 麦克风授权拿不到 | 浏览器要求 HTTPS 页面才给麦克风。本机开发用 `ws://127.0.0.1:7880` + localhost 页面；跨设备需按 runbook 上 Caddy |
