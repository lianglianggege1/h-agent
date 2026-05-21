# Agent Run 持久化分层与 Langfuse 集成设计

## 背景

当前聊天系统已经支持基于 LangChain4j 的 agent 调用与流式回复，但消息持久化仍主要围绕用户可见对话展开。随着工具调用加入，系统出现了一个新的设计问题：

1. `chat_session_messages` 当前适合作为“用户与助手对话记录表”。
2. agent runtime loop 中的工具调用、结果、异常链路并未稳定持久化到一个适合长期业务使用的位置。
3. `chat_memory_snapshots` 虽然能保存更完整的上下文，但它的职责是 memory snapshot，不是业务语义上的运行记录。
4. 同时，团队计划引入 Langfuse 作为监控与追踪平台，用于查看完整 runtime trace。

此前曾探索过“将 runtime loop 直接持久化到 `chat_session_messages` 并展示在前端”的方向，但当前决定放弃该路线。新的核心问题变为：

1. 用户可见消息和 agent runtime 是否应该分层持久化。
2. 在接入 Langfuse 后，本地数据库还需要保留哪些与 runtime 相关的数据。

本设计的结论是：需要分层持久化，但不在本地重复存储完整 runtime 事件流。`chat_session_messages` 保持对话语义，新增 `agent_runs` 作为运行索引层，Langfuse 承接完整 trace 细节。

## 目标

1. 保持 `chat_session_messages` 继续只承载用户与 assistant 最终回复。
2. 新增本地运行索引层，使每次用户提问触发的一次 agent 运行可以被稳定标识和查询。
3. 让本地业务数据与 Langfuse trace 建立稳定关联，而不依赖 Langfuse 充当业务主存储。
4. 避免在本地数据库与 Langfuse 之间重复存储两份完整 runtime loop。
5. 为后续后台排障、运行检索、失败分析和跳转 Langfuse 详情留出清晰扩展点。

## 非目标

1. 当前阶段不在用户聊天面板中展示 runtime loop。
2. 当前阶段不将 tool call、tool result 等 runtime 细节写入 `chat_session_messages`。
3. 当前阶段不新增本地 `agent_run_events` 全量事件表。
4. 当前阶段不重构 `chat_memory_snapshots` 的 snapshot 职责。
5. 当前阶段不依赖 Langfuse API 来还原用户聊天历史。

## 设计决策

### 分层原则

本设计采用三层职责划分：

1. 对话层：`chat_session_messages`
   - 只保存用户消息与 assistant 最终回复。
   - 用于聊天产品的历史消息展示与会话分页。
2. 运行索引层：`agent_runs`
   - 表示“一条用户消息触发的一次 agent 执行”。
   - 保存运行状态、消息关联、工具摘要和 Langfuse trace 关联信息。
3. 观测追踪层：Langfuse
   - 保存完整 runtime trace，包括 tool call、tool result、timing、error chain、token usage 等细节。

### 不重复存完整事件流

本地数据库不追求与 Langfuse 一样保存完整 runtime 事件树。当前阶段只在本地保留：

1. 业务必须的数据
   - 用户消息
   - assistant 最终回复
   - 一次运行与这些消息的关联关系
2. 运行索引摘要
   - run 状态
   - trace 标识
   - 工具数量
   - 工具名摘要
   - 错误摘要

完整的 tool 参数、tool 结果、span 层级、时间拆分等详细信息交由 Langfuse 保存。

## 数据模型

### chat_session_messages

`chat_session_messages` 保持“用户与助手对话记录表”的语义，不增加 tool 级别消息的写入要求。

它继续承担：

1. 用户消息持久化
2. assistant 最终回复持久化
3. 会话分页与历史展示
4. 标题提取与消息计数等现有业务逻辑

### 新增 agent_runs

新增 `agent_runs` 作为本地运行索引表，表示一次完整的 agent 执行。

建议字段如下：

1. `id`
   - 本地 run 主键。
2. `session_id`
   - 业务会话标识，直接保存当前系统中使用的字符串型 `sessionId`。
   - 用于与现有聊天流程保持一致。
3. `user_id`
   - 运行所属用户。
4. `prompt_id`
   - 本次运行所使用的系统提示词标识。
5. `user_message_id`
   - 指向 `chat_session_messages.id` 中本轮用户消息。
6. `assistant_message_id`
   - 指向 `chat_session_messages.id` 中本轮 assistant 最终回复。
   - 允许为空，待最终回复落库后回填。
7. `status`
   - 建议枚举值为 `RUNNING`、`SUCCEEDED`、`FAILED`。
8. `model_name`
   - 本次运行实际使用的模型名。
9. `langfuse_trace_id`
   - 本地运行与 Langfuse trace 的桥接字段。
10. `tool_count`
   - 本次运行调用工具的总次数。
11. `tool_names_json`
   - 本次运行涉及的工具名列表摘要。
12. `error_message`
   - 失败时的摘要错误信息。
13. `started_at`
   - 运行开始时间。
14. `completed_at`
   - 运行结束时间，成功或失败都填写。
15. `created_at`
16. `updated_at`

### 关联关系

`agent_runs` 与现有消息表的关联关系如下：

1. 一条用户消息对应一次 agent run。
2. 一次 agent run 最终产出一条 assistant 最终回复。
3. `agent_runs.user_message_id` 指向对应用户消息。
4. `agent_runs.assistant_message_id` 指向对应 assistant 最终回复。
5. `agent_runs.session_id` 与当前聊天会话的 `sessionId` 保持一致。
6. `agent_runs.langfuse_trace_id` 指向外部完整 trace。

这一设计允许系统从多个入口定位同一次运行：

1. 从会话查最近运行。
2. 从用户消息查运行。
3. 从 assistant 回复查运行。
4. 从本地 run 跳转到 Langfuse trace。

## 写入时机

### 总体顺序

一次用户提问触发的写入顺序建议固定如下：

1. 写入用户消息到 `chat_session_messages`
2. 创建 `agent_runs`
3. 启动 agent runtime，并把完整 trace 发送到 Langfuse
4. 在运行过程中持续更新 `agent_runs` 摘要字段
5. 完成时写入 assistant 最终回复到 `chat_session_messages`
6. 回填 `agent_runs.assistant_message_id` 并完成状态更新

### 详细流程

#### 1. 用户消息落库

在 agent 开始执行前，先将用户输入写入 `chat_session_messages`，并得到 `user_message_id`。

这样做的原因：

1. 业务上，一次 run 是围绕某条用户消息展开的。
2. 先有用户消息，再有 run 记录，关联关系更自然。
3. 即使后续运行失败，也保留了用户输入这一事实。

#### 2. 创建 agent run

创建 `agent_runs` 记录，写入：

1. `session_id`
2. `user_id`
3. `prompt_id`
4. `user_message_id`
5. `status = RUNNING`
6. `model_name`
7. `started_at`

此时允许以下字段为空：

1. `assistant_message_id`
2. `langfuse_trace_id`
3. `completed_at`
4. `error_message`

#### 3. 执行过程中更新 run 摘要

运行期间，本地不记录完整事件树，但允许持续维护 `agent_runs` 的摘要字段：

1. 识别到工具调用时，递增 `tool_count`
2. 将工具名加入 `tool_names_json`
3. 如果 Langfuse trace 在运行开始后才可获得，则回填 `langfuse_trace_id`
4. 如果发生异常，更新 `error_message`

#### 4. assistant 最终回复完成

在最终回复生成完成后：

1. 将 assistant 最终文本写入 `chat_session_messages`
2. 得到 `assistant_message_id`
3. 回填到 `agent_runs.assistant_message_id`
4. 更新：
   - `status = SUCCEEDED`
   - `completed_at`

#### 5. 失败场景

如果运行中途失败：

1. `agent_runs` 必须更新为 `FAILED`
2. 写入 `error_message`
3. 写入 `completed_at`

是否额外在 `chat_session_messages` 中写入一条失败态 assistant 提示消息，属于产品交互语义，不是本设计的强制要求。第一阶段只要求：

1. 用户消息已存在
2. 失败的 run 能被本地索引到
3. 可通过 `langfuse_trace_id` 回查详细原因

## Langfuse 集成边界

### Langfuse 负责的内容

Langfuse 承担完整运行观测与追踪职责，保存以下细节：

1. 完整 trace tree
2. tool call 原始参数
3. tool result 原始输出
4. nested spans
5. token usage
6. latency / timing
7. prompt / completion 观测信息
8. 原始异常链路

### 本地必须保留的内容

即使已经接入 Langfuse，本地业务库仍必须保留：

1. 用户消息
2. assistant 最终回复
3. 一次运行与这两类消息的关联关系
4. 运行状态
5. `langfuse_trace_id`
6. 工具摘要
7. 错误摘要

原因如下：

1. Langfuse 是 observability 平台，不应承担业务主存储职责。
2. 聊天历史不应依赖外部 tracing 平台 API 才能工作。
3. 本地系统需要稳定、低成本地回答“这条消息对应哪次运行、是否失败、是否值得追查”。
4. 即使 Langfuse 暂时不可用，本地业务关联也应保留。

### 为什么这不算重复持久化

本设计中的本地数据与 Langfuse 数据职责不同：

1. 本地存的是“索引与摘要”
2. Langfuse 存的是“完整 trace 与细节”

两者的轻微重叠仅限于：

1. `tool_count`
2. `tool_names_json`
3. `error_message`

这类摘要字段是本地业务检索所必需的，不构成“重复维护两份完整事件流”。

## 演进策略

### 第一阶段不新增 agent_run_events

当前阶段不新增本地 `agent_run_events` 表，原因是：

1. 已有 Langfuse 承担完整 trace 细节
2. 当前主要问题是“本地丢失运行索引”，不是“缺少第二份完整事件日志”
3. 过早把 tool 细节再全量写入本地，会增加重复度和维护成本

### 何时再考虑新增 agent_run_events

只有在出现以下需求之一时，再设计本地事件表：

1. 需要长期保留关键运行事件，不受 Langfuse retention 影响
2. 需要内部审计或合规导出
3. 需要在自有后台中直接查看关键步骤，而不是跳转 Langfuse
4. 需要进行离线批量分析，且不希望依赖 Langfuse 导出或 API

届时可以基于 `agent_runs.id` 平滑扩展出 `agent_run_events`，而无需推翻当前分层设计。

## 错误处理

1. 用户消息已落库，但 run 初始化失败
   - 允许存在只有用户消息、没有 run 的极端脏数据
   - 应记录服务日志，并尽量让 run 创建与消息写入保持在同一业务流程中
2. run 已创建，但 Langfuse trace 初始化失败
   - `agent_runs` 仍然保留
   - `langfuse_trace_id` 可为空
   - `error_message` 记录摘要信息
3. run 已开始，但模型或工具执行失败
   - `agent_runs.status = FAILED`
   - 保留 `tool_count`、`tool_names_json`、`error_message`
4. assistant 最终消息落库失败
   - `agent_runs` 不应误标为成功
   - 应保留失败状态，避免出现“run 成功但没有 assistant 消息”的错误语义

## 测试与验证

### 后端

1. 用户消息与 run 创建测试
   - 用户消息成功写入后，能正确创建 `agent_runs`
2. 成功链路测试
   - run 从 `RUNNING` 变为 `SUCCEEDED`
   - `assistant_message_id` 被正确回填
   - `completed_at` 被正确写入
3. 失败链路测试
   - 运行失败时，`status = FAILED`
   - `error_message` 与 `completed_at` 被正确写入
4. 工具摘要测试
   - 有工具调用时，`tool_count` 和 `tool_names_json` 被正确更新
5. Langfuse 关联测试
   - `langfuse_trace_id` 能正确写入与回填

### 业务验证

1. 现有聊天历史功能不受影响
   - `chat_session_messages` 仍只返回用户与 assistant 最终消息
2. 从一条 assistant 回复可以反查对应 run
3. 从 run 可以拿到 `langfuse_trace_id`
4. 失败对话能在本地快速定位，不必先依赖 Langfuse 查询

## 涉及模块

预计主要修改范围如下：

1. 后端
   - `backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
   - `backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
   - 新增 `agent_runs` 对应的 entity / mapper / service
   - 调整聊天发送流程中的 run 创建、assistant 消息回填与失败状态更新逻辑
2. Langfuse 接入
   - 新增 Langfuse trace 初始化与关联逻辑
   - 在 agent runtime 中更新本地 run 摘要

## 成功标准

满足以下条件即可视为本需求完成：

1. `chat_session_messages` 继续只承担用户与 assistant 最终对话记录职责。
2. 每次用户提问都可以在本地找到一条对应的 `agent_run` 记录。
3. `agent_run` 能关联：
   - `session_id`
   - `user_message_id`
   - `assistant_message_id`
   - `langfuse_trace_id`
4. 成功与失败运行都能在本地被快速检索和判断。
5. 完整 runtime loop 由 Langfuse 承接，而不是强行写回 `chat_session_messages`。
6. 后续如果需要本地事件审计，可以在当前设计基础上增量演进，而不是推翻重做。
