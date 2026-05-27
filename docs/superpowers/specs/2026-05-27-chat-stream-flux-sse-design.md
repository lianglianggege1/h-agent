# Chat Stream Flux SSE 设计

## 背景

当前聊天流接口 `/api/chat/messages/stream` 使用 `Spring MVC + StreamingResponseBody + application/x-ndjson`。

这套实现已经具备基础的流式体验，但有几个明显限制：

1. 控制器需要手工写 `OutputStream` 和 `flush`，事件输出较底层。
2. 服务层通过 `Consumer<String> + CountDownLatch` 协调流和收尾，模型流输出与业务收口耦合较重。
3. 前端按 NDJSON 逐行解析，协议不是标准 SSE 事件流。
4. 项目已经开启 `HTTP/2`，但接口层没有利用更清晰的 `Flux + SSE` 模型。

本次改造目标是将该接口切换为 `POST + Flux + text/event-stream`，保持“一次发言一次流返回”的交互方式，同时让大模型输出可以更自然地经由服务端流式透传到前端。

## 目标

1. 将 `/api/chat/messages/stream` 从 `StreamingResponseBody` 改为 `Flux` 返回。
2. 将返回协议从 `application/x-ndjson` 改为 `text/event-stream`。
3. 保持现有 `POST` 请求形态，不新增长期连接通道。
4. 保持现有聊天业务语义：`chunk`、`done`、`blocked`、`error`。
5. 让大模型输出的增量片段尽快流式返回客户端。
6. 保持现有鉴权、会话校验、审核拦截、消息落库和 agent run 收口能力。

## 非目标

1. 不引入长期 SSE 订阅通道。
2. 不改为 WebSocket。
3. 不做 NDJSON 与 SSE 的双协议兼容。
4. 不将整个后端应用迁移为纯 WebFlux 架构。
5. 不改变现有聊天请求体结构。

## 设计决策

### 1. 保持单次请求单次流返回

本次改造仍然采用“一次发言对应一次流响应”。

原因如下：

1. 当前聊天接口已经是 `POST` 请求并依赖请求体。
2. 用户目标已经收敛为标准聊天流，不需要长期稳定通道承载多次发言。
3. 这能在保留现有业务边界的前提下，最小成本切换到 `Flux + SSE`。

### 2. 接口改为 `POST + text/event-stream`

控制器接口保持原路径 `/api/chat/messages/stream`，返回类型改为响应式流。

建议形态：

```java
@PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<ChatStreamEvent>> streamMessage(...)
```

保留 `POST` 的原因：

1. 请求体中需要提交 `message`、`sessionId`、`promptId`。
2. `POST` 更符合“提交一条聊天消息并获取回复流”的语义。
3. 比强行改成 `GET` 更贴近当前项目结构。

### 3. 使用标准 SSE 事件封装

控制器输出使用 `ServerSentEvent<ChatStreamEvent>`。

建议的事件格式：

```text
event: chunk
data: {"type":"chunk","content":"你好"}

event: done
data: {"type":"done","content":""}
```

说明：

1. `event` 字段用于前端按标准 SSE 类型分发。
2. `data` 中继续保留 `type` 和 `content`，避免前后端语义割裂。
3. `done` 事件不再重复携带完整回复文本，只表示流结束。

### 4. 服务层直接产出 `Flux<ChatStreamEvent>`

当前 `ChatService` 签名：

```java
String streamChat(Long userId, Long promptId, String sessionId, String userMessage, Consumer<String> onChunk);
```

建议改为：

```java
Flux<ChatStreamEvent> streamChat(Long userId, Long promptId, String sessionId, String userMessage);
```

核心原则：

1. 服务层直接生成事件流。
2. 控制器只负责将事件流包装为 SSE。
3. 事件边界在服务层统一定义，不再由控制器手工拼装输出。

### 5. 模型输出尽快透传客户端

服务层应在模型产生片段时立即发出 `chunk` 事件，而不是等完整回复生成后统一返回。

建议的数据流：

1. 请求进入服务层后，先做会话校验、`promptId` 解析、用户消息落库、agent run 初始化。
2. 创建 `Flux.create(...)` 或等价的响应式 sink。
3. 模型 `onPartialResponse(chunk)` 回调时：
   - 将 `chunk` 追加到 `replyBuilder`
   - 立即发出 `ChatStreamEvent("chunk", chunk)`
4. 模型正常结束时：
   - 校验回复是否为空
   - 写入 assistant message
   - 完成 agent run
   - 发出 `ChatStreamEvent("done", "")`
   - 结束流

这样可以保证“模型一边输出，客户端一边看到”，同时仍然保留持久化和业务收口。

### 6. 流中异常统一映射为业务事件

为了保持前端处理稳定性，建议在响应已经开始后，不把异常直接暴露为连接级失败，而是统一转成业务事件后结束流。

规则如下：

1. 流开始前失败
   - 例如鉴权失败、参数校验失败
   - 继续使用普通 HTTP 错误响应

2. 流开始后失败
   - `InputGuardrailException` / `OutputGuardrailException`
     - 发出 `blocked`
     - 写入 blocked message
     - 标记 run 失败
     - 正常结束流
   - 其他运行时异常
     - 发出 `error`
     - 标记 run 失败
     - 正常结束流

### 7. 保留现有四种事件语义

本次改造继续使用以下事件类型：

1. `chunk`：模型增量文本片段
2. `done`：正常完成，不携带最终全文
3. `blocked`：平台审核拦截
4. `error`：模型或系统异常

这样前端状态机只需要做最小调整，不需要重新定义整套消息协议。

### 8. 前端继续使用 `fetch`，不切 `EventSource`

虽然返回协议切换为 SSE，但前端不建议改用 `EventSource`。

原因如下：

1. 当前接口是 `POST`，`EventSource` 天然只支持 `GET`。
2. 当前请求依赖 JSON 请求体，`fetch` 更适合。
3. 当前认证方式是 `credentials: "include"`，继续使用 `fetch` 更顺手。

因此前端应继续采用：

1. `fetch(...)`
2. `response.body.getReader()`
3. 手工解析 SSE message block

### 9. 前端解析从 NDJSON 改为 SSE block

当前前端通过按行拆分 NDJSON 解析事件。

改造后建议：

1. 按空行拆分 SSE block
2. 读取每个 block 中的 `event:` 行
3. 读取 `data:` 行并执行 `JSON.parse`
4. 以 `event` 为主、`data.type` 为辅进行分发

对应处理方式：

1. `chunk`
   - 追加到 assistant 占位消息
2. `done`
   - 结束当前流，不覆盖消息内容
3. `blocked`
   - 将 assistant 占位消息改为 blocked 消息
4. `error`
   - 设置错误状态并结束当前流

## 架构与职责

### 后端控制器

职责：

1. 接收聊天流请求
2. 调用 `ChatService` 获取 `Flux<ChatStreamEvent>`
3. 将事件映射成 `ServerSentEvent<ChatStreamEvent>`
4. 输出 `text/event-stream`

不再负责：

1. 手工写 `OutputStream`
2. 手工 `flush`
3. 手工序列化 NDJSON 行

### 后端服务层

职责：

1. 校验 session 与 prompt
2. 写入用户消息
3. 启动模型流式输出
4. 生成 `chunk`、`done`、`blocked`、`error` 事件
5. 负责 assistant message 落库与 agent run 收口

### 前端流消费层

职责：

1. 发起 `POST` 请求
2. 读取 SSE 响应流
3. 按事件类型回调 `onChunk`、`onDone`、`onBlocked`、`onError`

### 前端页面层

职责：

1. 保持当前输入和消息列表状态管理
2. `chunk` 时追加 assistant 文本
3. `done` 时结束 loading，不再覆盖完整文本
4. `blocked` 时转换为 blocked 气泡
5. `error` 时保留现有错误提示逻辑

## 数据流

### 正常回复

1. 前端 `POST /api/chat/messages/stream`
2. 后端校验并写入用户消息
3. 服务层启动模型流
4. 模型每次产生片段时，服务层发出 `chunk`
5. 控制器将 `chunk` 包装为 SSE 发送到前端
6. 模型完成后，服务层写入 assistant message
7. 服务层发出 `done`
8. 流结束

### 平台拦截

1. 前端发送消息
2. 后端开始流式处理
3. 审核或输出守护触发拦截
4. 服务层写入 blocked message
5. 服务层发出 `blocked`
6. 流正常结束

### 普通错误

1. 前端发送消息
2. 模型调用或运行时发生异常
3. 服务层标记 run 失败
4. 服务层发出 `error`
5. 流正常结束

## 依赖与框架边界

本次改造允许在当前项目中补充 WebFlux 相关依赖，但不将整个应用切换为纯 WebFlux 架构。

推荐边界：

1. 保留现有 `spring-boot-starter-web`
2. 增加 WebFlux 所需依赖以支持 `Flux` 与 SSE 返回
3. 仅在聊天流接口和相关服务中引入 Reactor 模型

这样可以避免为单个流接口重构整站配置。

## 改动范围

需要关注的后端文件：

1. `backend/pom.xml`
2. `backend/src/main/java/com/h/backend/chat/controller/ChatController.java`
3. `backend/src/main/java/com/h/backend/chat/service/ChatService.java`
4. `backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
5. 相关后端测试

需要关注的前端文件：

1. `frontend/lib/http.ts`
2. `frontend/lib/http.test.mjs`
3. `frontend/app/chat/page.tsx`

## 错误处理

### 请求前错误

包括：

1. 未认证
2. 参数不合法
3. 会话不存在或不匹配

处理方式：

1. 返回普通 HTTP 错误
2. 不进入 SSE 流

### 流中 blocked

处理方式：

1. 输出 `blocked`
2. 不再输出 `error`
3. 正常结束流

### 流中 error

处理方式：

1. 输出 `error`
2. 由前端保留现有错误提示
3. 正常结束流

## 测试策略

### 后端测试

1. 控制器测试
   - 验证返回 `Content-Type` 为 `text/event-stream`
   - 验证 `chunk`、`done`、`blocked`、`error` 的 SSE 格式

2. 服务层测试
   - 正常流：`chunk -> done`
   - 审核拦截：`blocked`
   - 普通异常：`error`
   - 空回复：错误收口

3. 回归测试
   - 保证 assistant message 持久化不退化
   - 保证 agent run 完成与失败状态不退化

### 前端测试

1. SSE 解析测试
   - 多个 `chunk` block
   - `done` block
   - `blocked` block
   - `error` block

2. 页面行为测试
   - `chunk` 能正确增量拼接
   - `done` 不再覆盖完整文本
   - `blocked` 会切换消息角色
   - `error` 保留现有兜底行为

## 成功标准

满足以下条件即可认为改造完成：

1. `/api/chat/messages/stream` 改为 `POST + Flux + SSE`。
2. 前端可稳定消费 `text/event-stream` 响应。
3. 模型增量输出会尽快流式显示到聊天界面。
4. `done` 事件不再重复发送完整回复文本。
5. `blocked` 与 `error` 语义保持清晰且行为稳定。
6. 会话持久化与 agent run 收口能力保持正常。
