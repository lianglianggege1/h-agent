# realtime-voice

`realtime-voice` 是 h-agent 的实时语音对话服务。它以独立 Python Worker 运行，负责：

- 通过 LiveKit 接收和发送实时音频；
- 使用 STT 将用户语音转换为文本；
- 在进程内运行 LangGraph 对话编排；
- 将 LangGraph 的流式文本交给 TTS 播放；
- 通过 FastAPI 承载与前端联动的 HTTP 接口；
- 后续通过工具层调用现有 Spring Boot 业务接口。

它不提供前端页面，也不替代 `backend`。浏览器继续由 `frontend` 提供，并通过 WebRTC 直接连接 LiveKit。

## 运行边界

```text
Browser <-> LiveKit <-> realtime-voice
   |                        |
   v                        v
frontend                 backend:8081
```

生产模式下运行两个独立进程：

| 进程 | 默认端口 | 用途 |
| --- | --- | --- |
| FastAPI | `8090` | 前端配置、后续 Token 和会话联动 API |
| LiveKit Worker | `8091` | Agent Worker 健康检查 |

LiveKit Worker 主动连接 LiveKit，`8091` 不承载浏览器语音流量。两个端口都避免与 Spring Boot 的 `8081` 冲突。

## 本地开发

要求 Python 3.11 至 3.14，并推荐使用 `uv`。

```bash
cd realtime-voice
cp .env.example .env.local
uv sync
uv run --module livekit.agents download-files
```

填写 `.env.local` 中的 LiveKit 与模型密钥后，可选择以下模式：

```bash
# 在终端直接测试语音 Agent
uv run realtime-voice console

# 连接 LiveKit，使用开发日志
uv run realtime-voice dev

# 生产 Worker
uv run realtime-voice start

# FastAPI（另开一个终端）
uv run realtime-voice-api
```

开发 FastAPI 时可以使用热更新：

```bash
uv run uvicorn realtime_voice.api:app --host 127.0.0.1 --port 8090 --reload
```

当前 HTTP 接口：

```text
GET /healthz   服务健康状态
GET /v1/config 前端可读取的公开语音配置
GET /docs      OpenAPI 调试页面
```

`/v1/config` 只返回公开的 Agent 名称，不返回 LiveKit Secret、模型密钥或内部后端地址。生产环境建议由 Next.js/Nginx 反向代理到 FastAPI，不直接在浏览器启用宽泛 CORS。

## 测试和静态检查

```bash
uv run pytest
uv run ruff check .
uv run ruff format --check .
```

测试不连接 LiveKit、OpenAI 或 Spring Boot，不需要真实密钥。

## 当前 LangGraph

当前图保持最小、低延迟结构：

```text
START -> assistant -> END
```

`assistant` 节点调用可配置的 LangChain Chat Model。LiveKit 的 `LLMAdapter` 以 `messages` 模式消费图输出，保留流式首 Token 能力。后续可以在图中增加：

- 用户与会话上下文加载；
- 调用 Spring Boot 的领域工具；
- 知识库检索；
- Harness 协作 Agent 路由；
- 记忆与检查点；
- 人工确认和高风险操作保护。

不要在低延迟语音主链路中串行加入不必要的长任务。长任务应先播报确认语，再异步执行或转交后台任务。

## 容器运行

```bash
docker build -t h-agent-realtime-voice .

# LiveKit Worker 容器
docker run --rm --env-file .env.local h-agent-realtime-voice

# FastAPI 容器（同一镜像，不同启动命令）
docker run --rm --env-file .env.local -p 8090:8090 \
  h-agent-realtime-voice uv run --no-sync realtime-voice-api
```

容器中已包含 Python 运行时、依赖和 LiveKit 所需的本地模型文件。

## 尚未接入的部分

当前提交只建立独立 Worker 与 LangGraph 的可运行骨架。端到端浏览器通话还需要后续完成：

1. 在 FastAPI 中接入现有用户鉴权并签发 LiveKit 房间 Token；
2. 将 `frontend/app/call` 从浏览器 SpeechRecognition/HTTP TTS 切换到 LiveKit Client；
3. 把 LiveKit participant、h-agent 用户和聊天会话关联起来；
4. 将语音转写和 Agent 回复持久化到现有聊天消息模型；
5. 为 LangGraph 增加调用 Spring Boot 的鉴权工具。
