# Nginx 直连 Spring Boot 的 SSE 传输设计

## 背景

聊天接口 `POST /api/chat/messages/stream` 当前经由 Next.js rewrite 转发到 Spring Boot。Next.js 开发代理默认存在 30 秒上游空闲超时，长时间模型调用可能被代理提前终止。单纯延长该超时可以容纳较慢请求，但无法证明心跳是否实时穿过真实网络，也会延迟静默失活的发现时间。

本设计让统一入口直接将 `/api/*` 转发到 Spring Boot，使 Next.js 退出 SSE 数据链路，并通过立即心跳、周期心跳、客户端无数据 watchdog 和真实网络验证形成完整的连接保活与故障检测机制。

## 目标

- SSE 请求不经过 Next.js 代理。
- 浏览器仍使用同源相对路径 `/api/*`，保留现有 HttpOnly Cookie 认证。
- 请求建立后立即向客户端写出数据，之后每 15 秒发送心跳。
- 模型超过五分钟没有正文时，只要心跳正常，连接仍可持续。
- 网络或服务静默失活时，前端约 50 秒内发现并给出明确提示。
- 使用真实 Nginx、Tomcat 网络链路验证逐帧到达，而不只依赖内存输出流单元测试。

## 非目标

- 本次不实现聊天任务持久化、断线续传或事件补发。
- 本次不将 SSE 改为 WebSocket。
- 本次不改为浏览器跨域直连 Spring Boot，因此生产链路不新增 CORS 配置。
- 本次不删除 Next.js rewrite；它保留为直接访问 `localhost:3000` 时的开发兜底。

## 总体架构

```text
浏览器：http://localhost:8089 或 http://<局域网IP>:8089
        │
        ▼
Nginx :8089
  ├── /api/* ───────► Spring Boot :8081
  └── 其他请求 ─────► Next.js :3000
```

浏览器继续请求 `/api/chat/messages/stream`。通过 Nginx 的 8089 端口访问时，Nginx 根据路径直接选择后端，Next.js 不会收到 `/api/*` 请求。直接访问 `http://localhost:3000` 时，现有 rewrite 仍提供开发兜底。

8089 端口由本项目独占，因此不增加 `/agent-dev` 路径前缀，避免引入 Next.js `basePath` 以及 API 路径重写。Nginx 绑定 `0.0.0.0:8089`；`0.0.0.0` 仅是监听地址，局域网设备使用开发机的实际局域网 IP 访问。

## Nginx 设计

Nginx 使用 Homebrew 安装，机器配置入口为 `/opt/homebrew/etc/nginx/nginx.conf`。仓库同时保存一份可版本管理的配置，机器配置从仓库配置同步或包含它。

`/api/` 路由必须：

- 转发到 `http://127.0.0.1:8081`。
- 使用 HTTP/1.1 上游连接。
- 关闭 `proxy_buffering`、`proxy_request_buffering` 和代理缓存。
- 将 `proxy_read_timeout` 与 `proxy_send_timeout` 设置为 60 秒空闲超时。
- 传递 `Host`、`X-Real-IP`、`X-Forwarded-For` 和 `X-Forwarded-Proto`。

页面路由转发到 `http://127.0.0.1:3000`，并支持 Next.js 开发环境 HMR 所需的连接升级。

60 秒是连续无数据的空闲超时，不是请求总时长。15 秒心跳会持续刷新该超时，因此健康请求可以超过五分钟。

## Spring Boot SSE 设计

后端在订阅业务事件前先输出一个 SSE 注释帧：

```text
:keepalive

```

随后将业务事件流与每 15 秒一次的周期心跳合并。收到 `done`、`error` 或 `blocked` 后终止心跳和响应。每个事件写入 `OutputStream` 后立即调用 `flush()`。

MVC 异步处理显式配置执行器。考虑到 `StreamingResponseBody` 的写入回调会在整个流期间等待完成，执行器使用 Java 虚拟线程，避免固定大小平台线程池在大量长连接下排队。MVC 默认异步总超时继续设置为禁用。

## 前端无数据 watchdog

watchdog 监听底层响应字节，而不是业务正文事件：

- 从发起 `fetch()` 开始监控首次响应。
- 获得响应体后，每次 `reader.read()` 收到任意字节时重置计时。
- `:keepalive` 虽然不触发业务 handler，但仍会重置 watchdog。
- 连续 50 秒没有任何字节时，取消读取并抛出明确的连接无响应错误。
- 正常收到 `done`、`error` 或 `blocked` 时清理计时器。
- 调用方传入的取消信号仍应生效，watchdog 不覆盖用户主动取消。

模型可以长时间不产生 `chunk`；只要 15 秒心跳到达，50 秒 watchdog 就不会触发。

## 故障语义

| 情况 | 预期行为 |
|---|---|
| 模型长时间计算，心跳正常 | 请求持续，不受五分钟总时长限制 |
| 后端明确发送 `error` 或 `blocked` | 前端立即展示服务端消息 |
| 服务端或代理明确关闭连接且没有终态 | 前端立即提示“连接异常中断，请稍后重试” |
| 连接静默失活、没有任何字节 | 前端约 50 秒主动取消并提示连接长时间无响应 |
| 前端 watchdog 未执行或失效 | Nginx 在约 60 秒上游空闲后兜底终止 |
| 用户主动取消 | 立即取消请求，不显示失活提示 |

## 配置与运行方式

- Spring Boot 继续监听 `8081`。
- Next.js 继续监听 `3000`。
- Nginx 监听 `0.0.0.0:8089`。
- 本机日常访问入口改为 `http://localhost:8089`。
- 局域网设备使用 `http://<开发机局域网IP>:8089`。
- `http://localhost:3000` 仅作为绕过 Nginx 的开发兜底。

生产环境沿用相同路径分流原则，并在入口层配置 HTTPS。生产 Cookie 的 `Secure` 属性属于部署安全配置，应在启用 HTTPS 时同步开启，但不纳入本次本地链路改造。

## 测试与验收

### 自动化测试

- 后端单元测试验证第一个 SSE 帧是立即心跳。
- 后端测试验证延迟业务事件期间仍有周期心跳，且终态之后停止。
- 前端测试验证任意原始字节都会重置 watchdog。
- 前端测试验证只有正文缺失但心跳持续时不会超时。
- 前端测试验证连续 50 秒无字节时取消并报错。
- 保留无终态断流测试。

### 配置验证

- 使用 `nginx -t` 验证配置语法。
- 确认 `/api/*` 不会到达 Next.js。
- 确认 Next.js 页面与 HMR 通过 Nginx 正常工作。

### 真实链路验收

通过 `http://localhost/api/chat/messages/stream` 发起请求并记录每帧到达时间：

- 请求建立后立即收到首个心跳或业务帧。
- 业务长时间无输出时，约每 15 秒收到心跳。
- 请求持续超过 60 秒仍保持连接。
- 最终收到 `done`，HTTP 状态为 200。
- 响应体不包含 `Internal Server Error`。

真实链路验收通过后，前端 watchdog 才视为安全启用；如果心跳仍被缓冲，应先修复传输链路，不得通过继续延长 watchdog 掩盖问题。
