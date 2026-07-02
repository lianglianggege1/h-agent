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

新的 A2A 能力分为两层：

- **使用层采用 LangChain4j 原生风格**：业务代码通过 interface、`@Agent`、`@V`、`AgenticServices.a2aBuilder(...)` 使用远端 agent，就像使用本地 agent。
- **适配层采用 AgentScope 分层思想**：卡片发现、消息转换、事件路由、执行器、请求处理、transport wrapper 等职责拆开，避免手写固定 endpoint 或手拼 JSON-RPC。

客户端标准用法应是 LangChain4j A2A 风格：

```java
public interface A2ACreativeWriter {

    @Agent
    String generateStory(@V("topic") String topic);
}

A2ACreativeWriter creativeWriter = AgenticServices
        .a2aBuilder("http://localhost:8082/a2a/agents/creative-writer", A2ACreativeWriter.class)
        .outputKey("story")
        .build();
```

需要多轮 A2A 上下文时，直接使用 LangChain4j 的 `@A2AContextId`、`@A2ATaskId`：

```java
public interface A2AEchoAgent {

    @A2AClientAgent(
            a2aServerUrl = "http://localhost:8082/a2a/agents/echo",
            outputKey = "response",
            description = "Echo agent for testing contextId/taskId propagation")
    ResultWithAgenticScope<String> echo(
            @V("question") String question,
            @A2AContextId @V("contextId") String contextId,
            @A2ATaskId @V("taskId") String taskId);
}
```

在 workflow 中也应该直接传入这些 interface proxy，而不是让业务代码感知 registry：

```java
StoryWorkflow workflow = AgenticServices.sequenceBuilder(StoryWorkflow.class)
        .subAgents(creativeWriter, audienceEditor, styleEditor)
        .outputKey("story")
        .build();
```

服务端也不应该要求业务手写 `A2AAgentDescriptor` 和 `runner(...)`。标准用法应是给要暴露的 LangChain4j agent interface 或 bean 增加 A2A 暴露元数据，adapter 自动生成 descriptor、agent-card、runner 和 request handler：

```java
@A2AServerAgent(
        id = "creative-writer",
        name = "创意写作者",
        description = "根据主题生成故事初稿",
        outputKey = "story")
public interface CreativeWriterAgent {

    @Agent
    String generateStory(@V("topic") String topic);
}
```

如果不希望侵入已有 interface，也允许用配置绑定已有 bean/method，但这只是补充能力，不是首选体验。Controller 不应该知道 `creative-writer`、`audience-editor`、`style-editor` 这些具体 agent，只暴露统一 A2A 入口。

## LangChain4j 与 AgentScope 取舍

本设计不把 LangChain4j A2A 和 AgentScope A2A 看成二选一，而是分别吸收它们最适合本项目的部分。

LangChain4j `langchain4j-agentic-a2a` 的优势在使用体验：

- 远端 A2A agent 可以通过 `AgenticServices.a2aBuilder(url, Interface.class)` 创建。
- 本地 agent 和远端 A2A agent 都使用 interface、`@Agent`、`@V`、`outputKey`。
- 远端 agent 可以直接参与 `sequenceBuilder`、`loopBuilder`、`supervisorBuilder` 的 `.subAgents(...)`。
- `@A2AContextId`、`@A2ATaskId` 已经表达了 A2A envelope 字段和普通 text part 的区别。
- `ResultWithAgenticScope` 可以把远端返回的 `contextId/taskId` 写回 `AgenticScope`，自然支持多轮调用。

因此，`backend` 作为 A2A client 时，首选 LangChain4j 的使用方式。除非确实需要补充配置、监控或测试边界，不重新发明一套 `RemoteAgentRegistry.require(...)` 风格的业务 API。

AgentScope A2A 的优势在 adapter 架构完整性：

- client 侧把 card resolver、message conversion、event router、memory、interrupt/cancel 分开。
- server 侧有 `AgentScopeA2aServer` 作为 facade，组装 agent-card converter、executor、runner、request handler、transport wrapper、task store、queue manager、registry。
- transport wrapper 与 Web 框架解耦，Controller 只负责把 HTTP 请求交给 wrapper。
- request handler、executor、runner 的边界清晰，后续扩展 streaming、cancel、task 查询时不需要推翻结构。

因此，`other-agents` 作为 A2A server 时，首选 AgentScope 的分层思路。业务入口仍然保持 LangChain4j interface/bean 风格，但内部通过 descriptor、card factory、executor、runner、request handler、transport wrapper 组织。

最终取舍：

```text
使用体验：采用 LangChain4j 风格，让远端 A2A agent 像本地 agent 一样使用。
实现结构：采用 AgentScope 风格，让 A2A adapter 有清晰的 server facade 和协议边界。
```

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

与 AgentScope 的对齐关系：

```text
AgentScope A2aAgent                  -> LangChain4j interface proxy + A2A client adapter
AgentScope AgentCardResolver         -> A2AAgentCardResolver
AgentScope ClientEventHandlerRouter  -> A2AClientEventHandlerRouter
AgentScope message conversion        -> A2AMessageMapper
AgentScope AgentScopeA2aServer       -> A2AAgentServer facade
AgentScope AgentCardConverter        -> A2AAgentCardFactory
AgentScope AgentExecutor             -> A2AAgentExecutor
AgentScope RequestHandler            -> A2ARequestHandler
AgentScope TransportWrapper          -> JsonRpcA2ATransportWrapper
AgentScope AgentRunner               -> LangChain4jA2AAgentRunner
```

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
    LangChain4j A2A proxy facade
    AgentCardResolver
    A2AClientConfig
    RemoteAgentInvoker
    ClientEventHandlerRouter
    MessageMapper

other-agents
  A2A server adapter
    A2AAgentServer
    AgentDescriptorRegistry
    AgentCardFactory
    AgentRunner
    AgentExecutor
    RequestHandler
    JsonRpcTransportWrapper
    JsonRpcTransportController
```

设计原则：

- 业务代码优先使用 LangChain4j interface/proxy，不直接操作 adapter 内部 registry。
- Controller 不再写死具体 agent。
- agent-card 由注解或配置生成的 descriptor/registry/factory 生成。
- JSON-RPC 由 transport wrapper 处理，Spring Controller 只做 HTTP 接入。
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
  A2AClientConfig
  A2AClientProxyFactory
  A2ARemoteAgentInvoker
  A2AClientEventHandlerRouter
  A2AMessageMapper
  A2AInvocationIds
```

这些类型是 adapter 内部结构。业务代码应优先直接使用：

```java
AgenticServices.a2aBuilder(a2aServerUrl, AgentInterface.class)
```

如果 LangChain4j 默认 builder 已经满足需求，就直接使用默认 builder；本项目只补齐缺失的 AgentScope-style 可测试边界、Spring 配置装配和 server 侧能力，不重复发明一套业务 API。

### Backend 配置

配置采用新的统一 URL。对于强类型 interface agent，`inputKeys` 从 `@V` 参数自动推导，`contextId/taskId` 从 `@A2AContextId`、`@A2ATaskId` 参数自动进入 envelope：

```yaml
agents:
  a2a:
    other-agents:
      creative-writer-url: http://localhost:8082/a2a/agents/creative-writer
      audience-editor-url: http://localhost:8082/a2a/agents/audience-editor
      style-editor-url: http://localhost:8082/a2a/agents/style-editor
```

只在使用 `UntypedAgent` 或纯配置注册远端 agent 时才显式声明 `inputKeys`。

### Backend 调用流程

```text
AgenticScope
  -> LangChain4j A2A interface proxy
  -> A2ARemoteAgentInvoker
  -> A2AMessageMapper
  -> A2A SDK Client.sendMessage
  -> A2AClientEventHandlerRouter
  -> 写回 output/contextId/taskId 到 AgenticScope
```

对于强类型 interface agent，invoker 从被调用方法的参数和注解中读取输入；对于 `UntypedAgent`，才从 `AgenticScope` 中按 `inputKeys` 读取参数。`A2AMessageMapper` 把业务参数转换为 A2A `Message` 的 text parts，并补齐：

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

- `contextId` 从带有 `@A2AContextId` 的方法参数读取；参数同时带有 `@V("contextId")` 时，响应中的值写回同名 `AgenticScope` 状态。
- `taskId` 从带有 `@A2ATaskId` 的方法参数读取；参数同时带有 `@V("taskId")` 时，响应中的值写回同名 `AgenticScope` 状态。
- 如果没有已有 `contextId`，则不传，让服务端创建新 context；必要时可由配置扩展为从当前 `AgenticScope.memoryId()` 派生稳定值。
- 如果没有已有 `taskId`，则不传，让服务端创建新 task。
- 每次 outbound request 都生成新的 `messageId`。
- metadata 包含：
  - `memoryId`
  - `sessionId`，当可以从 memory id 或 scope 状态推导时写入
  - `agentId`
  - `source=backend`

当远端响应包含新的 `contextId/taskId` 时，invoker 写回：

- `@A2AContextId` 参数对应的 `@V` 状态 key
- `@A2ATaskId` 参数对应的 `@V` 状态 key

这让后续同一 workflow 的远端调用可以继续同一个 A2A 上下文和 task。

## Other-Agents Server 设计

删除当前手写固定端点：

- `A2AController` 中的三个固定 POST 方法
- `A2AAgentCardApplicationService`

新增 server adapter 包：

```text
other-agents/src/main/java/com/h/otheragents/a2a/server/
  A2AServerAgent
  A2AAgentServer
  A2AAgentDescriptor
  A2AAgentDescriptorRegistry
  A2AAgentCardFactory
  A2AAgentRunner
  LangChain4jA2AAgentRunner
  A2AAgentExecutor
  A2ARequestHandler
  A2ATransportWrapper
  JsonRpcA2ATransportWrapper
  JsonRpcA2ATransportController
  A2AMessageMapper
```

### Server 暴露模型

服务端业务入口是 LangChain4j agent interface 或 agent bean，不是手写 descriptor。`@A2AServerAgent` 负责声明 A2A 暴露元数据：

```java
@A2AServerAgent(
        id = "creative-writer",
        name = "创意写作者",
        description = "根据主题生成故事初稿",
        outputKey = "story")
public interface CreativeWriterAgent {

    @Agent
    String generateStory(@V("topic") String topic);
}
```

adapter 启动时扫描带有 `@A2AServerAgent` 的 agent bean 或 interface 元数据，自动生成内部 `A2AAgentDescriptor`：

- `id/name/description/outputKey` 来自 `@A2AServerAgent`。
- `inputKeys` 来自目标方法参数上的 `@V`。
- runner 由 `LangChain4jA2AAgentRunner` 自动包裹目标 bean/method。
- agent-card 由 `A2AAgentCardFactory` 生成。

`A2AAgentDescriptor`、`A2AAgentDescriptorRegistry`、`A2AAgentRunner` 是内部模块。只有在注解无法表达的高级场景下，才允许手动注册 descriptor。Controller 和 request handler 不注入具体 agent bean，不写 `switch` 或 `if creative-writer` 分支。

### Server Facade

新增 `A2AAgentServer`，对应 AgentScope 的 `AgentScopeA2aServer`。它不监听端口，负责组装：

- descriptor registry
- agent-card factory
- agent executor
- request handler
- transport wrapper
- task store 和 queue manager 的初始实现

Spring Web 只通过 `JsonRpcA2ATransportController` 调用 `A2AAgentServer` 暴露的 wrapper。后续如果换 transport 或增加 registry 发布，不需要改业务 agent。

### Server 请求流程

```text
HTTP POST /a2a/agents/{agentId}
  -> JsonRpcA2ATransportController
  -> JsonRpcA2ATransportWrapper
  -> A2ARequestHandler
  -> A2AAgentDescriptorRegistry
  -> A2AAgentExecutor
  -> A2AAgentRunner
  -> LangChain4j agent bean
  -> A2A Message response
```

Controller 只处理 HTTP 接入：

- 提取 path 中的 `agentId`
- 读取 request body 和 headers
- 转发给 `JsonRpcA2ATransportWrapper`
- 返回 JSON-RPC 响应

Transport wrapper 处理 JSON-RPC 传输语义：

- parse request
- method routing
- JSON-RPC id 保留
- transport-level error mapping
- blocking/streaming transport 分流预留

Request handler 处理协议语义：

- `message/send`
- unsupported method
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

- LangChain4j A2A interface proxy：能通过 `AgenticServices.a2aBuilder(url, Interface.class)` 创建远端 agent
- `@A2AContextId`、`@A2ATaskId`：参数进入 envelope，响应值写回 `AgenticScope`
- card resolver：well-known agent-card 解析正确
- message mapper：
  - `@V` 参数转成 text parts
  - context/task 参数不进入 text parts
  - metadata 包含 `memoryId`、`agentId`、`source`
- event handler：
  - `MessageEvent` 提取 text/context/task ids
  - `TaskEvent` 提取 artifacts/context/task ids
  - failed task state 转成 invocation exception
- A2A story flow：远端节点以 interface proxy 形式参与 `.subAgents(...)`

Other-agents 测试：

- `@A2AServerAgent` 扫描：从 LangChain4j agent bean 自动生成 descriptor
- descriptor registry：内部注册、查询、未知 id 行为
- agent-card factory：生成 `/a2a/agents/{agentId}` URL
- server facade：能组装 request handler、executor、transport wrapper
- transport wrapper：保留 JSON-RPC id，分发 `message/send`
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
- `A2AAgentConfig` 中故事创作 workflow 的整体结构，但远端 agent 创建改为 `AgenticServices.a2aBuilder(url, Interface.class)`
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
- 新增远端 agent 时，服务端优先只需新增或标注一个 LangChain4j agent interface/bean；客户端优先只需新增一个本地 interface 并通过 `AgenticServices.a2aBuilder(url, Interface.class)` 创建。
- 对使用方而言，远端 A2A agent 可以像本地 workflow sub-agent 一样参与 `.subAgents(...)`。
