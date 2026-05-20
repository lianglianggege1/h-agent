# 聊天工具调用轨迹展示设计

## 背景

当前聊天页已经具备基于 `application/x-ndjson` 的流式回复能力，但流中仅传递 `chunk / done / error` 三类事件，前端只能把它们拼成一条 assistant 文本消息。

与此同时，后端当前的 `chat_session_messages` 只稳定承载“用户输入”和“assistant 最终回复”的会话记录，无法完整表达 LangChain4j agent runtime 中的工具调用过程。`chat_memory_snapshots` 中虽然保存了更完整的上下文，但它的职责是 memory snapshot，不适合直接承担面向前端的历史回放职责。

本次需求希望把 agent 与 LLM 的交互过程也展示在前端对话框中，并结合当前正在推进的消息持久化改造，让 `chat_session_messages` 成为 agent runtime 事实记录的主表。前端第一阶段只展示工具相关过程，不展示所有 runtime 中间消息。

## 目标

1. 在聊天页的 assistant 回复下方展示本轮工具调用过程。
2. 默认展示用户友好的步骤摘要，同时支持展开查看原始 `tool request / tool result` 细节。
3. 工具调用过程需要实时流式显示，而不是只在最终回复完成后出现。
4. 工具调用过程需要持久化，刷新页面或打开历史会话时可以回放。
5. `chat_session_messages` 需要能够承载 agent runtime 的完整事实记录，而不再仅保存用户消息和最终回复。
6. 历史回放和实时流式显示需要共用同一套前端视图模型和渲染逻辑。

## 非目标

1. 第一阶段不在聊天页直接展示 `system`、`custom` 或其他非工具类 runtime 中间消息。
2. 第一阶段不重构 `chat_memory_snapshots` 的职责，不把它改造成前端直接读取的数据源。
3. 第一阶段不把工具事件渲染成独立聊天气泡。
4. 第一阶段不引入新的独立“调试面板”或侧边抽屉来承载工具轨迹。
5. 第一阶段不改变当前的聊天分页机制与会话切换入口形态。

## 设计决策

### 展示形态

采用“assistant 主回复 + 下挂工具步骤”的展示方式：

1. assistant 最终回复仍然是主消息体。
2. 工具调用过程作为该 assistant 回复下方的附属区域渲染。
3. 附属区域默认显示简洁步骤，例如“调用 `add` 工具”“返回结果 `5`”。
4. 用户可以展开某一步，查看原始请求参数和原始结果文本。

此方案同时满足“用户友好展示”和“调试原始流可展开查看”的双层需求，也与“挂在 assistant 回复下面”的交互要求一致。

### 数据源职责

后端保留两类数据源，但职责明确区分：

1. `chat_session_messages` 负责保存 agent runtime 的事实事件，用于聊天历史回放和前端视图组装。
2. `chat_memory_snapshots` 继续负责 memory snapshot 的缓存与恢复，用于维持 LangChain4j 记忆窗口，不直接承担前端渲染职责。

## 后端数据模型

### chat_session_messages 的职责提升

`chat_session_messages` 从“对话结果表”升级为“agent runtime 事实表”。一轮用户消息触发的 runtime 中，产生的关键事件都需要按顺序入库。

表内现有字段可以继续复用，但语义需要明确：

1. `role_code`
   - 保留底层角色信息，取值可包括 `user`、`assistant`、`tool`、`system`。
   - 用于保留 LangChain4j runtime 的原始消息身份。
2. `message_type`
   - 用于表达前端和服务层更关注的事件类型。
   - 第一阶段至少包含 `USER_INPUT`、`AI_MESSAGE`、`TOOL_REQUEST`、`TOOL_RESULT`。
3. `content_text`
   - 保存便于检索、标题提取和快速展示的摘要文本。
   - 例如用户消息正文、assistant 最终回复正文、工具名、工具结果摘要。
4. `payload_json`
   - 保存结构化原始数据。
   - 对 `TOOL_REQUEST` 至少包含 `toolCallId`、`toolName`、`arguments`。
   - 对 `TOOL_RESULT` 至少包含 `toolCallId`、`toolName`、`result`。
   - 对 `AI_MESSAGE` 统一保存最终回复原文，并预留原始响应元信息字段，第一阶段至少写入 `finalText`。

### 事件顺序

一轮典型运行时事件顺序如下：

1. `USER_INPUT`
2. `TOOL_REQUEST`
3. `TOOL_RESULT`
4. `AI_MESSAGE`

若同一轮包含多个工具调用，则顺序继续按 `TOOL_REQUEST -> TOOL_RESULT` 成对追加，最终仍以本轮最后一条 `AI_MESSAGE` 作为面向用户的主回复。

### 轮次归属

第一阶段不强制引入新表或额外 turn 表，前端和服务层按顺序消费同一 `session_id` 下的消息序列，并使用“用户输入作为一轮起点”的规则归组：

1. 遇到一条 `USER_INPUT`，开启新一轮。
2. 收集其后的 `TOOL_REQUEST / TOOL_RESULT / AI_MESSAGE`。
3. 以该轮最后一条 `AI_MESSAGE` 作为主 assistant 回复。
4. 将该轮内的工具事件挂载到这条 assistant 回复下。

第一阶段明确采用顺序归组，不新增显式 turn 字段；只有在未来出现并发工具链或多终稿 assistant 场景时，才单独发起下一轮设计评估 turn 标识。

## 服务层组装规则

### 历史回放模型

面向前端的历史消息接口不应直接暴露“每一行 runtime 记录都对应一条聊天消息”的底层结构，而应由服务层先组装成对话视图消息。

建议把现有 `ChatSessionMessageDto` 扩展为更贴近前端展示的数据结构：

1. `user` 消息继续直接返回。
2. `assistant` 消息除了 `content` 外，新增 `toolTrace[]`。
3. `toolTrace[]` 中的每一项包含：
   - `toolCallId`
   - `toolName`
   - `argumentsText`
   - `resultText`
   - `status`
   - `requestSequenceNo`
   - `resultSequenceNo`

### 分组算法

给定按 `sequence_no` 升序排列的 runtime 记录，服务层按以下规则组装：

1. 读取到 `USER_INPUT` 时，先输出一条用户消息视图。
2. 为当前轮创建一个临时 trace 容器。
3. 读取到 `TOOL_REQUEST` 时，向 trace 容器追加一个步骤，状态为 `running` 或 `success_pending_result`。
4. 读取到 `TOOL_RESULT` 时，根据 `toolCallId` 或顺序匹配到对应步骤，补齐结果，并将状态更新为 `success`。
5. 读取到 `AI_MESSAGE` 时，将其作为主 assistant 回复输出，并把当前 trace 容器挂到该 assistant 回复上。
6. 下一条 `USER_INPUT` 到来时开启新一轮。

### 异常与脏数据降级

需要允许历史数据不完美，但前端仍可渲染：

1. 若存在 `TOOL_REQUEST` 但缺少 `TOOL_RESULT`，则该步骤标记为 `failed` 或 `interrupted`。
2. 若存在工具步骤但缺少最终 `AI_MESSAGE`，服务层应合成一条失败态 assistant 视图消息，避免这轮 trace 丢失。
3. 若存在 `TOOL_RESULT` 但未匹配到 `TOOL_REQUEST`，则以“未知工具步骤”降级展示，不抛出异常。
4. 若存在 `system` 等非目标消息类型，第一阶段忽略其前端展示，但不能破坏当前轮分组。

## 流式接口设计

### 协议

保留现有 `application/x-ndjson`，不新增 SSE 或 WebSocket。这样可以最小化对现有网络层和前端流读取逻辑的改动。

### 事件信封

将当前仅有 `chunk / done / error` 的流事件扩展为更明确的事件类型。第一阶段至少包含：

1. `assistant_chunk`
   - assistant 最终回复的增量文本片段。
2. `tool_request`
   - 本轮工具调用请求。
   - 数据中至少包含 `toolCallId`、`toolName`、`arguments`。
3. `tool_result`
   - 本轮工具调用结果。
   - 数据中至少包含 `toolCallId`、`toolName`、`result`。
4. `assistant_done`
   - assistant 最终完整回复，用于流结束定稿。
5. `error`
   - 本轮运行失败信息。

推荐将当前的简单事件结构升级为统一事件信封，例如：

```json
{
  "type": "tool_request",
  "content": null,
  "payload": {
    "toolCallId": "call_1",
    "toolName": "add",
    "arguments": "{\"a\":2,\"b\":3}"
  }
}
```

第一阶段统一采用 `type + payload` 作为事件消费协议，不再扩展新的仅依赖 `content` 的事件语义；`assistant_chunk` 和 `assistant_done` 的文本也放在 `payload.text` 中。

### 实时推送与持久化时机

每类关键 runtime 事件在产生时同时做两件事：

1. 写入 `ndjson` 流，供前端实时展示。
2. 持久化到 `chat_session_messages`，供刷新和历史回放使用。

时机如下：

1. 收到用户消息后，持久化 `USER_INPUT`。
2. runtime 发起工具调用前，推送并持久化 `TOOL_REQUEST`。
3. 工具执行结束后，推送并持久化 `TOOL_RESULT`。
4. assistant 生成正文时，持续推送 `assistant_chunk`。
5. assistant 完成后，推送 `assistant_done`，并持久化最终 `AI_MESSAGE`。

## 前端视图模型与渲染

### 前端消息模型

当前前端 `ChatMessage` 仅包含 `id / role / content`，不足以同时承载最终回复和工具轨迹。建议扩展为：

1. `id`
2. `role`
3. `content`
4. `toolTrace?`
5. `runtimeState?`

其中：

1. `toolTrace` 仅对 `assistant` 消息存在。
2. `runtimeState` 用于区分 `streaming / completed / failed` 等状态，便于处理中断场景。

### assistant 消息渲染

assistant 消息区域拆成两部分：

1. 主回复正文
   - 继续使用当前文本展示逻辑。
   - 如存在 `<think>` 片段，沿用现有折叠展示方式。
2. 工具调用轨迹区
   - 仅当 `toolTrace.length > 0` 时渲染。
   - 默认展示步骤列表，例如“步骤 1：调用 add”“步骤 2：返回结果 5”。
   - 每一步允许展开，查看原始参数和原始结果文本。

### 流式态更新

前端发送消息后，仍先插入一条空的 assistant 占位消息，但该消息不再只维护 `content`，还需要维护 `toolTrace`。

流式事件到来时按以下方式更新：

1. `tool_request`
   - 在当前 assistant 占位消息的 `toolTrace` 中追加步骤。
2. `tool_result`
   - 匹配对应 `toolCallId` 的步骤，写入结果并更新状态。
3. `assistant_chunk`
   - 继续追加主回复文本。
4. `assistant_done`
   - 用完整文本定稿 assistant 内容，并将状态切为 `completed`。
5. `error`
   - 将当前 assistant 消息标记为失败，并保留已收到的工具轨迹。

### 历史回放复用同一组件

历史消息列表与实时占位消息都应使用同一套 `assistant` 渲染组件。差异只体现在数据来源：

1. 实时消息来自流式事件增量更新。
2. 历史消息来自服务层已组装好的 `assistant + toolTrace` 结构。

这样可以避免维护两套展示逻辑，降低后续行为漂移风险。

## 兼容性与迁移

### 历史数据兼容

旧数据中可能只有 `user` 与 `assistant` 最终消息，没有工具轨迹。该类消息继续按纯文本聊天历史展示，不需要补历史迁移。

### 分阶段落地

本需求可以按以下顺序实现：

1. 后端把 runtime 工具事件写入 `chat_session_messages`。
2. 流式接口支持 `tool_request / tool_result` 实时推送。
3. 前端在当前会话中实时渲染工具轨迹。
4. 历史消息接口改为返回组装后的 `assistant + toolTrace` 结构。
5. 前端历史回放复用同一渲染逻辑。

虽然推荐按此顺序实现，但最终交付标准仍要求实时与历史两端都可用。

## 错误处理

1. 工具请求已发出但执行失败：
   - 推送 `error` 或专门的失败态结果。
   - 历史消息中该步骤状态为 `failed`。
2. 工具成功但 assistant 最终回复失败：
   - 保留已完成的工具轨迹。
   - 当前轮生成失败态 assistant 消息。
3. 读取历史消息时发现脏数据：
   - 服务层做降级组装，不向前端抛出结构异常。
4. 当前流式响应被中断：
   - 已展示的工具步骤保留。
   - assistant 主消息显示“本轮响应中断”或后端错误文案。

## 测试与验证

### 后端

1. 服务层分组测试
   - `USER_INPUT -> TOOL_REQUEST -> TOOL_RESULT -> AI_MESSAGE` 能正确组装为用户消息 + assistant 消息附 trace。
2. 多工具测试
   - 一轮内多个工具调用时，顺序和归属正确。
3. 中断测试
   - 缺少 `TOOL_RESULT` 或缺少最终 `AI_MESSAGE` 时，仍能返回可渲染结构。
4. 流式事件测试
   - runtime 事件能按预期顺序输出 `tool_request / tool_result / assistant_chunk / assistant_done`。

### 前端

1. 流式消息更新测试
   - `tool_request` 能生成步骤。
   - `tool_result` 能补齐结果。
   - `assistant_chunk` 与 `assistant_done` 能正确定稿内容。
2. 历史回放渲染测试
   - 已组装的 `toolTrace` 能挂在 assistant 消息下正确显示。
3. 错误态测试
   - 中断或失败步骤能正确显示失败状态，不影响主消息渲染。
4. 兼容旧数据测试
   - 无 `toolTrace` 的历史 assistant 消息仍可正常展示。

## 涉及模块

预计主要修改范围如下：

1. 后端
   - `backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
   - `backend/src/main/java/com/h/backend/chat/controller/ChatController.java`
   - `backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
   - `backend/src/main/java/com/h/backend/chat/dto/*`
   - `backend/src/main/java/com/h/backend/chat/model/*`
   - 新增 runtime 事件载荷和历史消息组装相关类
2. 前端
   - `frontend/lib/http.ts`
   - `frontend/app/chat/page.tsx`
   - 新增 assistant trace 展示组件

## 成功标准

满足以下条件即可视为本需求完成：

1. 当前对话中，assistant 回复下方可以实时看到工具调用步骤。
2. 用户可以展开查看原始工具请求参数和原始工具结果。
3. 刷新页面或重新进入会话后，工具步骤仍可从历史消息中回放出来。
4. 没有工具调用的普通对话仍保持现有体验，不出现多余 UI。
5. `chat_session_messages` 可以完整承载本轮 agent runtime 的关键事实记录。
