# Anthropic Thinking 兼容设计

## 背景

当前聊天页的“思考过程”展示依赖 assistant 正文中的 `<think>...</think>` 片段。这个方案在旧链路下可用，但在切换到 `anthropic` 风格流式输出后出现两个问题：

1. 新模型的 thinking 与正式回答是两条独立流，不再天然嵌在 assistant 正文里。
2. 后端当前只透传正式回答 `chunk`，没有把 thinking 增量透传和持久化，因此前端无法展示新模式下的思考过程。

本次需求目标是在保留原有 `<think>` 兼容能力的前提下，同时支持 `anthropic` 模式下独立的 thinking 流式展示、成功后落库、历史回放。

## 目标

1. 兼容旧 assistant 正文中的 `<think>...</think>` 展示逻辑。
2. 支持 `anthropic` 模式下独立 thinking 内容的流式展示。
3. 成功完成时，将 thinking 写入 `chat_session_messages`，作为正式历史的一部分。
4. 前端历史消息可以区分普通 assistant 回复、blocked 消息和 reasoning 消息。
5. thinking 在 `blocked` 或 `error` 场景下可以保留在当前页面，但不写入历史。

## 非目标

1. 不回填或迁移历史 assistant 正文中的 `<think>` 数据。
2. 不修改现有 guardrail 规则本身。
3. 不新增独立运行事件表。
4. 不在本次设计中引入 tool trace、token 使用量或更细粒度 runtime timeline。

## 设计决策

### 1. 采用双轨兼容策略

系统同时支持两种思考过程来源：

- 旧数据：assistant 正文内嵌 `<think>...</think>`，继续由前端解析展示。
- 新数据：thinking 作为独立消息类型落库和回放。

这样可以保证旧会话无需迁移即可继续正常展示，新会话则使用更清晰的数据模型。

### 2. 新增独立消息类型 `REASONING`

`chat_session_messages.message_type` 新增一种正式类型：`REASONING`。

设计约定：

- `message_type = REASONING`
- `role_code = assistant`
- `content_text` 保存纯文本 thinking 内容
- `payload_json` 保存完整消息快照，并显式带上 `messageType=REASONING`

reasoning 仍然归属 assistant turn，但不再混入 assistant 正文字符串。

### 3. 流式协议新增 `reasoning` 事件

聊天流中新增一种 SSE 事件：

- `reasoning`：thinking 增量
- `chunk`：正式回答增量
- `done`：正常完成
- `blocked`：平台拦截
- `error`：真正错误

这样前端可以把思考过程和正式回答分别渲染，而不是继续依赖正文字符串解析。

### 4. thinking 实时展示，成功后才落库

新链路采用如下策略：

- thinking 到达时立即流式显示给用户
- thinking 仅缓存在当前请求上下文中
- 只有当本轮 assistant 成功完成时，reasoning 才写入会话历史

这与常见 agent 产品的处理方式一致，可以兼顾实时体验与历史整洁性。

### 5. 失败时保留当前页面 thinking，但不入历史

若一次回答在 thinking 之后被 `blocked` 或 `error` 中断：

- 当前页面已展示的 thinking 保留
- reasoning 不写入 `chat_session_messages`
- `blocked` 继续按现有逻辑落库为独立 blocked 消息
- `error` 不新增历史消息

这样可以让用户理解刚刚发生了什么，同时避免历史数据出现不完整 reasoning。

## 数据模型

## 数据库

`chat_session_messages` 表结构不新增列，沿用已有字段：

- `message_type`
- `role_code`
- `content_text`
- `payload_json`

本次仅扩展 `message_type` 的取值集合：

- `USER`
- `AI`
- `SYSTEM`
- `REASONING`

语义映射建议如下：

- 用户消息：`message_type=USER`，`role_code=user`
- assistant 回复：`message_type=AI`，`role_code=assistant`
- blocked 消息：`message_type=SYSTEM`，`role_code=blocked`
- reasoning 消息：`message_type=REASONING`，`role_code=assistant`

### DTO 与前端消息模型

历史消息接口不应只返回 `role` 和 `content`，还需要显式返回 `messageType`。

建议接口返回字段至少包含：

- `id`
- `role`
- `messageType`
- `content`
- `createdAt`

前端内部消息模型需要支持以下语义：

- `USER`
- `ASSISTANT`
- `BLOCKED`
- `REASONING`

这里的 `messageType` 用于渲染判断，`role` 继续用于左右布局与兼容现有逻辑。

## 后端设计

### 流式回调链路

`langchain4j` 已支持独立 thinking 回调，因此聊天服务直接使用：

- `onPartialThinking(thinking -> ...)`
- `onPartialResponse(chunk -> ...)`
- `onCompleteResponse(...)`

在一次请求生命周期中维护两个缓冲区：

- `reasoningBuilder`：累积 thinking
- `replyBuilder`：累积正式回答

处理逻辑如下：

1. 用户消息入库。
2. `onPartialThinking` 到达时：
   - 追加到 `reasoningBuilder`
   - 发送 `ChatStreamEvent("reasoning", thinking)`
   - 不落库
3. `onPartialResponse` 到达时：
   - 追加到 `replyBuilder`
   - 发送 `ChatStreamEvent("chunk", chunk)`
4. `onCompleteResponse` 到达时：
   - 校验 `replyBuilder` 非空
   - 若 `reasoningBuilder` 非空，先落一条 `REASONING` 消息
   - 再落一条 assistant 消息
   - 发送 `done`

### 落库顺序

同一轮 assistant 输出的持久化顺序必须固定为：

1. `REASONING`
2. assistant reply

这样历史回放时，前端可以稳定地把 reasoning 和紧随其后的 assistant 回复组合为同一轮输出，而不需要额外猜测关联关系。

### 服务层职责调整

`ChatSessionService` 新增显式方法，例如：

- `appendReasoningMessage(Long userId, String sessionId, String reasoningMessage)`

不建议通过 `appendAssistantMessage` 传额外标志位来兼容 reasoning，因为两类消息虽然都归属于 assistant，但业务语义和持久化规则不同。

### `payload_json` 约定

`payload_json` 继续保存完整消息快照，建议至少包含：

- `id`
- `sequenceNo`
- `role`
- `messageType`
- `content`
- `createdAt`

这样未来如果需要在不改表结构的情况下增加 `complete`、`sourceModel` 等元信息，也有稳定扩展点。

### blocked 与 error 处理

若发生 `blocked`：

1. 已流出的 reasoning 不落库
2. 当前 blocked 消息继续按现有逻辑写入历史
3. 向前端发 `blocked` 事件并结束

若发生 `error`：

1. 已流出的 reasoning 不落库
2. 不新增 assistant 或 reasoning 历史消息
3. 向前端发 `error` 事件并结束

## 前端设计

### 历史消息读取

前端拉取历史消息后，优先根据 `messageType` 渲染：

- `REASONING`：渲染为“思考过程”折叠块
- `ASSISTANT` 或对应后端普通 assistant 类型：渲染正式回答
- `BLOCKED`：渲染平台拦截卡片
- `USER`：渲染用户消息

旧数据兼容规则保留：

- 若一条普通 assistant 消息正文中含 `<think>...</think>`，继续走现有 `parseMessageSegments(...)`
- 新 `REASONING` 类型不再进入 `<think>` 字符串解析逻辑

### 实时流式展示

用户提交消息后，前端为当前轮先创建三段 UI 状态：

1. 用户消息
2. reasoning 占位块
3. assistant 占位块

处理规则如下：

- 收到 `reasoning`：只更新 reasoning 占位块
- 收到 `chunk`：只更新 assistant 占位块
- 收到 `done`：保留两块
- 收到 `blocked` 或 `error`：
  - 保留已展示的 reasoning 块
  - blocked 卡片或错误提示按现有策略处理
  - reasoning 不作为后续历史来源

### 展示组合

为了贴近常见 agent 产品体验，渲染层应在视觉上把：

- 一个 `REASONING`
- 后续紧邻的一个 assistant 回复

组合为同一轮 assistant 输出，而不是把 reasoning 当成完全独立的普通聊天气泡。

建议将渲染边界抽象为 turn，而不是简单逐条 message 平铺。最小可行形态可以是：

- `user turn`
- `assistant turn { reasoning?, answer? }`
- `blocked turn`

这也能为后续扩展 tool 过程块留出空间。

## 数据流

### 成功路径

1. 用户发送消息。
2. 后端写入用户消息。
3. 后端通过 `onPartialThinking` 流式输出 reasoning。
4. 后端通过 `onPartialResponse` 流式输出正式回答。
5. 响应成功完成后：
   - 若 reasoning 非空，先写入 `REASONING`
   - 再写入 assistant reply
6. 前端保留 reasoning 折叠块与正式回答。

### blocked 路径

1. 用户发送消息。
2. 前端已收到部分 reasoning。
3. 后端发生 guardrail blocked。
4. 已缓冲 reasoning 丢弃，不入历史。
5. blocked 消息按现有逻辑入历史。
6. 前端当前页面保留 reasoning，并展示 blocked 卡片。

### error 路径

1. 用户发送消息。
2. 前端已收到部分 reasoning 或部分正文。
3. 后端发生运行错误。
4. 已缓冲 reasoning 丢弃，不入历史。
5. 不新增 assistant 或 reasoning 历史消息。
6. 前端当前页面保留已展示 reasoning，并继续显示错误提示与现有兜底文案。

## 改动范围

后端重点文件：

- `backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
- `backend/src/main/java/com/h/backend/chat/dto/ChatStreamEvent.java`
- `backend/src/main/java/com/h/backend/chat/controller/ChatController.java`
- `backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java`
- `backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
- `backend/src/main/java/com/h/backend/chat/dto/ChatSessionMessageDto.java`

前端重点文件：

- `frontend/lib/http.ts`
- `frontend/lib/chat-sessions.ts`
- `frontend/app/chat/page.tsx`
- 对应前端测试文件

## 测试策略

### 后端测试

1. 流式成功测试
   - 验证 `onPartialThinking` 与 `onPartialResponse` 都会产生对应 SSE 事件
   - 验证成功完成时先落 reasoning，再落 assistant

2. blocked 回归测试
   - 验证 blocked 场景下 reasoning 不落库
   - 验证 blocked 消息仍正常入历史

3. error 回归测试
   - 验证 error 场景下 reasoning 与 assistant 都不落库

4. 历史接口测试
   - 验证历史消息返回 `messageType`
   - 验证 `REASONING` 不会被归并成普通 user 或普通 assistant 语义

### 前端测试

1. SSE 解析测试
   - 验证 `apiStream` 能正确分发 `reasoning` 事件

2. 聊天页状态测试
   - 验证 reasoning 与 assistant 占位块能分别增量更新
   - 验证 `done` 时两块都被保留
   - 验证 `blocked` 或 `error` 时 reasoning 保留在当前页面

3. 历史回放测试
   - 验证 `REASONING` 类型会显示为思考折叠块
   - 验证旧 `<think>` assistant 消息仍能正常展示

## 成功标准

满足以下条件即认为本次设计完成：

1. 新 `anthropic` thinking 能实时显示在前端“思考过程”区域。
2. 成功完成时，thinking 会写入 `chat_session_messages`，并可在历史会话中回放。
3. `blocked` 和 `error` 场景下，thinking 不进入历史。
4. 旧 `<think>` assistant 数据无需迁移，仍能继续展示。
5. 前后端都以显式 `messageType` 语义处理 reasoning，而不是继续依赖正文字符串技巧。
