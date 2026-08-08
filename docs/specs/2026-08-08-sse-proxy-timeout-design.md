# SSE 代理超时修复设计

## 背景

聊天流接口通过 Next.js rewrite 从 `http://localhost:3000/api/*` 转发到后端 `http://localhost:8081/api/*`。后端已经使用 `WebMvcAsyncConfig#setDefaultTimeout(-1)` 关闭 Spring MVC 默认异步超时，并每 15 秒生成一次 SSE 心跳。

真实复现显示：当领域智能体的同步模型调用总耗时超过约 30 秒时，浏览器收到 `500 Internal Server Error`，而后端模型稍后仍正常返回。Next.js 16 当前 rewrite 代理实现默认使用 30 秒 `proxyTimeout`，超时后返回该 500 响应。

## 目标

- 允许聊天流在首个上游响应到达前运行最多 5 分钟。
- 保留现有同源 `/api/*` 访问方式、认证 Cookie 和 rewrite 架构。
- 保留后端 15 秒心跳与现有异步配置。
- 流在未收到业务终态时被关闭，前端应明确报告连接异常，不能静默结束。
- 不修改 `ChatModelConfig` 和模型 thinking 行为。

## 非目标

- 不把浏览器改为直接请求 8081。
- 不新增自定义 Next Route Handler 代理。
- 不重构领域智能体或模型调用流程。
- 不修改模型服务本身的 120 秒调用超时。

## 设计

### Next.js 代理

在 `frontend/next.config.ts` 的 `experimental` 配置中设置：

```ts
proxyTimeout: 300_000
```

现有 rewrite 保持不变。该配置覆盖 Next.js 开发代理默认 30 秒限制，同时保留有限上限，避免无界挂起。

### 前端流终态

`apiStream` 在解析 SSE 时记录是否收到以下任一终态：

- `done`
- `error`
- `blocked`

读取流自然结束时，如果没有收到任何终态，则调用错误处理并抛出稳定错误 `连接异常中断，请稍后重试`。心跳注释帧继续被忽略，不改变业务事件处理。

### 后端

后端不改行为：

- `WebMvcAsyncConfig#setDefaultTimeout(-1)` 继续生效。
- `chat.stream.heartbeat-interval: 15s` 保持不变。
- `ChatController` 继续在每个 SSE 帧后调用 `flush()`。

## 测试

1. 前端回归测试先构造一个只发送普通事件后关闭、但没有 `done/error/blocked` 的 SSE 流，确认当前实现错误地静默成功。
2. 实现终态检测后，确认该测试抛出稳定错误并通知 `onError`。
3. 保留并运行已有心跳测试，确认注释帧不会影响消息解析。
4. 校验 Next 配置中的代理超时为 300000 毫秒。
5. 运行前端测试、lint、build 与相关后端测试。
6. 重启 Next 开发服务器后，使用已复现的领域模型请求验证超过 30 秒时不再由 Next 代理返回 500。

## 成功标准

- 超过 30 秒但不超过 5 分钟的聊天请求不会因 Next rewrite 默认超时返回 500。
- 正常流仍能收到原有事件并完成。
- 没有业务终态的异常断流会显示明确错误。
- 用户现有 `ChatModelConfig` 修改保持原样。
