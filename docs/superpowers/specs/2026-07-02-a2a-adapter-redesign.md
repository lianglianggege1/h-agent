# A2A 适配层重设计

日期：2026-07-02

## 目标

重新设计 `backend` 和 `other-agents` 之间的 A2A 集成。新的设计参考 AgentScope A2A 扩展包的 client/server 分层，但适配本项目的 Spring Boot、LangChain4j Agentic workflow 和现有会话记忆模型。

这次重设计同时覆盖两端：

- `backend`：作为 A2A client 和 LangChain4j workflow 编排方。
- `other-agents`：作为 A2A server，向外暴露远端故事创作类 agent。

本次不保留旧实现的历史包袱。现有过渡性代码可以删除并替换。

依赖版本以当前升级后的版本为准：

- LangChain4j `1.17.0`
- `langchain4j-agentic-a2a:1.17.0-beta27`

`1.17.0-beta27` 已经支持 A2A 的 `contextId` 和 `taskId` 概念，这次设计需要正式使用它们。

## 使用体验目标

新的 A2A 能力不仅内部结构要像 AgentScope，使用体验也要尽量接近 AgentScope 文档中的简单方式。

客户端应能做到类似：

```java
A2ARemoteAgent creativeWriter = A2ARemoteAgent.builder()
        .name("creative-writer")
        .agentCardResolver(WellKnownA2AAgentCardResolver.builder()
                .baseUrl("http://localhost:8082")
                .relativeCardPath("/a2a/agents/creative-writer/.well-known/agent-card.json")
                .build())
        .inputKeys("topic")
        .outputKey("story")
        .contextIdKey("creativeWriterContextId")
        .taskIdKey("creativeWriterTaskId")
        .build();
```

在 workflow 中仍然像使用普通子 agent 一样使用：

```java
.subAgents(remoteAgentRegistry.require("creative-writer"))
```

服务端应能做到类似：

```java
@Bean
A2AAgentDescriptor creativeWriterA2A(Agents.CreativeWriter creativeWriter) {
    return A2AAgentDescriptor.builder()
            .id("creative-writer")
            .name("创意写作者")
            .description("根据主题生成故事初稿")
            .inputKeys("topic")
            .outputKey("story")
            .runner(LangChain4jA2AAgentRunner.of(creativeWriter::generateStory))
            .build();
}
```

Controller 不应该知道 `creative-writer`、`audience-editor`、`style-editor` 这些具体 agent，只暴露统一 A2A 入口。

## 参考模型

设计参考 AgentScope 的 A2A 扩展包。

客户端侧参考：

- `A2aAgent`
- `AgentCardResolver`
- `ClientEventHandlerRouter`
- message conversion utilities

服务端侧参考：

- agent descriptor/registry
- agent-card converter
- agent executor
- request handler
- transport wrapper/controller

不会直接复制 AgentScope 的 `Msg`、`Memory`、`Hook`、`Flux<Event>` 等类型。本项目需要把同样的边界适配到：

- Spring Boot Controller
- LangChain4j `AgenticScope`
- LangChain4j agent bean
- 现有 chat memory/session 模型

## 公开端点

旧的按 agent 分散的 URL 会删除：

```text
/creative-writer/.well-known/agent-card.json
/creative-writer/a2a
/audience-editor/.well-known/agent-card.json
/audience-editor/a2a
/style-editor/.well-known/agent-card.json
/style-editor/a2a
```

新的统一端点：

```text
GET  /a2a/agents/{agentId}/.well-known/agent-card.json
POST /a2a/agents/{agentId}
```

不做旧 URL 兼容。旧测试需要删除或改为期望 404。

## 总体架构

```text
backend
  A2A client adapter
    AgentCardResolver
    RemoteAgentRegistry
    RemoteAgentInvoker
    ClientEventHandlerRouter
    MessageMapper

other-agents
  A2A server adapter
    AgentDescriptorRegistry
    AgentCardFactory
    AgentRunner
    AgentExecutor
    RequestHandler
    JsonRpcTransportController
```

设计原则：

- Controller 不再写死具体 agent。
- agent-card 由 descriptor/registry/factory 生成。
- JSON-RPC 由统一 transport controller 接入。
- agent 执行由 `AgentExecutor -> AgentRunner` 完成。
- `backend` 调用远端时正式传递 `contextId`、`taskId`、`messageId` 和 metadata。
- 当前先支持 blocking `message/send`，但边界保留 streaming、cancel、task 查询的扩展位置。

## Backend Client 设计

删除当前过渡性 client 实现：

- `OtherAgentsA2AClient`
- 当前过渡版 `A2ARemoteAgentInvoker`
- 当前过渡版 `A2ARemoteAgentRegistry`

新增 client adapter 包：

```text
backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/client/
  A2AAgentCardResolver
  WellKnownA2AAgentCardResolver
  FixedA2AAgentCardResolver
  A2ARemoteAgent
  A2ARemoteAgentDefinition
  A2ARemoteAgentRegistry
  A2ARemoteAgentInvoker
  A2AClientEventHandlerRouter
  A2AMessageMapper
  A2AInvocationIds
```

### Backend 配置

配置采用新的统一 URL，并显式声明 context/task 状态 key：

```yaml
agents:
  a2a:
    remote-agents:
      - id: creative-writer
        base-url: http://localhost:8082
        card-path: /a2a/agents/creative-writer/.well-known/agent-card.json
        input-keys:
          - topic
        output-key: story
        context-id-key: creativeWriterContextId
        task-id-key: creativeWriterTaskId
      - id: audience-editor
        base-url: http://localhost:8082
        card-path: /a2a/agents/audience-editor/.well-known/agent-card.json
        input-keys:
          - story
          - audience
        output-key: story
        context-id-key: audienceEditorContextId
        task-id-key: audienceEditorTaskId
      - id: style-editor
        base-url: http://localhost:8082
        card-path: /a2a/agents/style-editor/.well-known/agent-card.json
        input-keys:
          - story
          - style
        output-key: story
        context-id-key: styleEditorContextId
        task-id-key: styleEditorTaskId
```

### Backend 调用流程

```text
AgenticScope
  -> A2ARemoteAgentInvoker
  -> A2AMessageMapper
  -> A2A SDK Client.sendMessage
  -> A2AClientEventHandlerRouter
  -> 写回 output/contextId/taskId 到 AgenticScope
```

`A2ARemoteAgentInvoker` 从 `AgenticScope` 中按 `inputKeys` 读取参数。`A2AMessageMapper` 把这些参数转换为 A2A `Message` 的 text parts，并补齐：

- `messageId`
- `contextId`
- `taskId`
- metadata

`A2AClientEventHandlerRouter` 处理：

- `MessageEvent`
- `TaskEvent`
- `TaskUpdateEvent`

它负责提取响应文本，捕获返回的 `contextId/taskId`，并把终态失败任务转换为调用异常。

### Backend context/task 规则

- `contextId` 从 `context-id-key` 指向的 `AgenticScope` 状态读取。
- 如果没有已有 `contextId`，则从当前 `AgenticScope.memoryId()` 派生一个稳定值。
- `taskId` 从 `task-id-key` 指向的 `AgenticScope` 状态读取。
- 如果没有已有 `taskId`，则不传，让服务端创建新 task。
- 每次 outbound request 都生成新的 `messageId`。
- metadata 包含：
  - `memoryId`
  - `sessionId`，当可以从 memory id 或 scope 状态推导时写入
  - `agentId`
  - `source=backend`

当远端响应包含新的 `contextId/taskId` 时，invoker 写回：

- `context-id-key`
- `task-id-key`

这让后续同一 workflow 的远端调用可以继续同一个 A2A 上下文和 task。

## Other-Agents Server 设计

删除当前手写固定端点：

- `A2AController` 中的三个固定 POST 方法
- `A2AAgentCardApplicationService`

新增 server adapter 包：

```text
other-agents/src/main/java/com/h/otheragents/a2a/server/
  A2AAgentDescriptor
  A2AAgentDescriptorRegistry
  A2AAgentCardFactory
  A2AAgentRunner
  LangChain4jA2AAgentRunner
  A2AAgentExecutor
  A2ARequestHandler
  JsonRpcA2ATransportController
  A2AMessageMapper
```

### Agent Descriptor

每个要暴露的远端 agent 通过 `A2AAgentDescriptor` 描述：

```java
new A2AAgentDescriptor(
    "creative-writer",
    "创意写作者",
    "根据主题生成故事初稿",
    List.of("topic"),
    "story",
    creativeWriterRunner
)
```

Registry 负责 `agentId -> descriptor` 查询。Controller 和 request handler 不注入具体 agent bean，不写 `switch` 或 `if creative-writer` 分支。

### Server 请求流程

```text
HTTP POST /a2a/agents/{agentId}
  -> JsonRpcA2ATransportController
  -> A2ARequestHandler
  -> A2AAgentDescriptorRegistry
  -> A2AAgentExecutor
  -> A2AAgentRunner
  -> LangChain4j agent bean
  -> A2A Message response
```

Controller 只处理 transport 相关事情：

- 提取 path 中的 `agentId`
- 读取 request body 和 headers
- 转发给 request handler
- 返回 JSON-RPC 响应

Request handler 处理协议语义：

- `message/send`
- unsupported method
- JSON-RPC id 保留
- error response 构造

Executor 处理 agent 执行语义：

- 按 descriptor 的 `inputKeys` 顺序读取 text parts
- 调用 runner
- 构造响应 message
- 保留或创建 `contextId/taskId`

### Agent Card 规则

`A2AAgentCardFactory` 根据 descriptor 和部署配置生成 agent-card。

必填字段：

- `name`：descriptor id 或展示名
- `description`：descriptor 描述
- `url`：`{publicUrl}/a2a/agents/{agentId}`
- `provider`：`h-agent other-agents`
- `version`：`0.1.0`
- `capabilities.streaming`：初始为 `false`
- `defaultInputModes`：`text/plain`
- `defaultOutputModes`：`text/plain`
- `skills`：每个 descriptor 一个 skill

## Context、Task 和 Memory 语义

服务端入站处理规则：

- `Message.contextId` 作为远端 A2A 会话上下文。
- `Message.taskId` 存在时表示继续远端 task。
- `Message.taskId` 不存在时创建新的 task id。
- 从 metadata 读取：
  - `memoryId`
  - `sessionId`
  - `userId`

远端 agent memory id 选择顺序：

1. `metadata.memoryId`
2. `metadata.sessionId`
3. `Message.contextId`

响应 message 必须包含：

- `contextId`
- `taskId`
- 新的 `messageId`
- metadata：
  - `provider=other-agents`
  - `agent=<agentId>`

这个模型参考 AgentScope 的 `AgentRequestOptions`，但落到本项目时使用 LangChain4j 和现有 memory/session 体系。

## 初始协议范围

第一版只实现 blocking `message/send`。

Server adapter 的边界需要保留未来扩展点：

- `message/stream`
- `tasks/get`
- `tasks/cancel`
- task history

不支持的方法返回标准 A2A/JSON-RPC 错误，不做临时字符串错误。

## 错误处理

Backend client 侧：

- agent-card 读取失败：抛出 `A2AAgentCardResolveException`
- 远端 JSON-RPC error：抛出 `A2ARemoteInvocationException`
- task failed/canceled/rejected：异常中包含 `taskId`、`contextId`、终态状态
- 响应没有 text parts：抛出协议异常
- 缺少必填 input key：沿用 LangChain4j `MissingArgumentException`

Other-agents server 侧：

- 未知 `agentId`：返回 JSON-RPC invalid request error
- 不支持的 method：返回 method not found 或 unsupported operation error
- text parts 不足：返回 invalid request error
- agent 执行失败：返回 JSON-RPC error 或 A2A agent error message，同时保留 `contextId/taskId`

## 测试计划

Backend 测试：

- 配置绑定：`remote-agents`、`context-id-key`、`task-id-key`
- card resolver：`base-url + card-path` 组合正确
- message mapper：
  - input keys 转成 text parts
  - memory id 转成 `contextId`
  - metadata 包含 `memoryId`、`agentId`、`source`
- event handler：
  - `MessageEvent` 提取 text/context/task ids
  - `TaskEvent` 提取 artifacts/context/task ids
  - failed task state 转成 invocation exception
- registry：按 id 创建远端 agent executor
- A2A story flow：远端节点来自 registry

Other-agents 测试：

- descriptor registry：注册、查询、未知 id 行为
- agent-card factory：生成 `/a2a/agents/{agentId}` URL
- request handler：保留 JSON-RPC id，分发 `message/send`
- executor：按 descriptor input order 映射 text parts
- response：保留或创建 `contextId/taskId`
- controller：`GET card` 和 `POST message/send`
- 旧 endpoint 测试删除或改为 404

## 迁移策略

删除旧代码：

- backend：
  - `OtherAgentsA2AClient`
  - 当前过渡版 `A2ARemoteAgentInvoker`
  - 当前过渡版 `A2ARemoteAgentRegistry`
  - 旧固定远端 wrapper 类
  - `A2AAgents`
- other-agents：
  - 手写固定 endpoint controller 方法
  - `A2AAgentCardApplicationService`

保留：

- `A2AStoryAssistant`
- `A2AAgentConfig` 中故事创作 workflow 的整体结构
- 本地故事信息提取
- 本地风格评分

替换范围只聚焦远端 agent adapter 和 A2A transport 层。

## 不在本次范围

- streaming response
- task cancellation UI 或外部 cancel API
- 抽取共享 Maven module
- A2A endpoint 鉴权
- 注册到外部 A2A registry
- 文件、图片、音频、data part 等非文本 part

这些能力暂缓。第一版目标是先落地一个清晰、可测试、支持 `contextId/taskId` 的 blocking text A2A 工作流。

## 验收标准

- backend 调用远端 agent 时不再手写 JSON-RPC `RestClient` 请求体。
- other-agents 不再为每个 agent 写固定 Controller 方法。
- 新 URL `/a2a/agents/{agentId}` 可完成 `message/send`。
- agent-card 使用 `/a2a/agents/{agentId}/.well-known/agent-card.json`。
- `contextId/taskId` 能从 backend 传到 other-agents，并从响应写回 backend 的 `AgenticScope`。
- 新增远端 agent 时，服务端只需新增 descriptor；客户端只需新增配置或 builder 注册。
- 对使用方而言，远端 A2A agent 可以像本地 workflow sub-agent 一样参与 `.subAgents(...)`。
