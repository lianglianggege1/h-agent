# A2A 适配层重设计

日期：2026-07-02

## 目标

重新设计 `backend` 和 `other-agents` 之间的 A2A 集成。核心目标不是重新发明一套 agent 编程模型，而是让标准 LangChain4j agent 可以通过 A2A 协议被远程调用和远程暴露。

本次重设计覆盖两端：

- `backend`：作为 A2A client 和 LangChain4j workflow 编排方。
- `other-agents`：作为 A2A server，向外暴露已有 LangChain4j agent。

本次不保留旧实现历史包袱。现有过渡性代码可以删除并替换。

依赖版本以当前升级后的版本为准：

- LangChain4j `1.17.0`
- `langchain4j-agentic-a2a:1.17.0-beta27`

`1.17.0-beta27` 已支持 A2A 的 `contextId` 和 `taskId` 概念，本设计需要正式使用它们。

## 核心原则

1. A2A 是协议适配层，不是新的 agent 框架。
2. client 端优先直接使用 LangChain4j A2A client 的现有能力。
3. server 端业务 agent 必须仍然是标准 LangChain4j agent。
4. server adapter 可以参考 AgentScope 的分层，但 descriptor、runner、executor 都是内部结构，不成为业务开发 API。
5. 新增一个远端能力时，业务侧应该只新增或复用一个 LangChain4j agent interface/bean，再选择把它暴露为 A2A。

最终取舍：

```text
Client 使用与实现：采用 LangChain4j A2A client。
Server 业务模型：采用 LangChain4j agent interface/bean。
Server 协议适配：参考 AgentScope 的 server facade、transport wrapper、request handler、executor 分层。
```

## 为什么这样设计

LangChain4j `langchain4j-agentic-a2a` 已经提供 client 集成：

- `AgenticServices.a2aBuilder(url, Interface.class)`
- interface 动态代理
- 自动获取 agent-card
- 构建 A2A SDK `Client`
- 方法参数转 A2A `Message`
- `@A2AContextId`、`@A2ATaskId` 进入 message envelope
- `MessageEvent`、`TaskEvent`、`TaskUpdateEvent` 结果处理
- `ResultWithAgenticScope` 写回远端返回的 `contextId/taskId`
- 与 `sequenceBuilder`、`loopBuilder`、`supervisorBuilder` 的 `.subAgents(...)` 混用

因此 `backend` 不再设计一套 `RemoteAgentRegistry.require(...)` 或手写 JSON-RPC client。它只负责用 Spring 配置集中创建 LangChain4j A2A client agent。

LangChain4j 当前没有提供对应的 A2A server adapter。`langchain4j-agentic-a2a` 的依赖集中在 `a2a-java-sdk-client` 和 JSON-RPC client transport，没有 server request handler、task store、transport wrapper、executor 等服务端结构。

因此 `other-agents` 需要自建 server adapter。该 adapter 借鉴 AgentScope 的架构分层：

- server facade
- agent-card factory
- request handler
- executor
- runner
- transport wrapper
- task store

但这只是内部协议适配结构。业务 agent 不写 `A2AAgentDescriptor`，不写 `A2AAgentRunner`，不继承 A2A 专用接口。

## 使用体验

### Backend 调用远端 agent

远端 agent 在 `backend` 中像本地 agent 一样声明和使用：

```java
public interface A2ACreativeWriter {

    @Agent
    String generateStory(@V("topic") String topic);
}
```

```java
A2ACreativeWriter creativeWriter = AgenticServices
        .a2aBuilder(properties.getCreativeWriterUrl(), A2ACreativeWriter.class)
        .outputKey("story")
        .build();
```

参与 workflow 时直接传入 `.subAgents(...)`：

```java
StoryWorkflow workflow = AgenticServices.sequenceBuilder(StoryWorkflow.class)
        .subAgents(creativeWriter, audienceEditor, styleEditor)
        .outputKey("story")
        .build();
```

多轮 A2A 调用使用 LangChain4j 已有注解：

```java
public interface A2AEchoAgent {

    @A2AClientAgent(
            a2aServerUrl = "http://localhost:8082/a2a/agents/echo",
            outputKey = "response")
    ResultWithAgenticScope<String> echo(
            @V("question") String question,
            @A2AContextId @V("contextId") String contextId,
            @A2ATaskId @V("taskId") String taskId);
}
```

`contextId` 和 `taskId` 不作为 text part 发送，而是进入 A2A message envelope。远端返回新值后，LangChain4j client 写回 `AgenticScope`。

### Other-Agents 暴露本地 agent

服务端 agent 仍然按 LangChain4j 标准写法定义：

```java
public interface CreativeWriterAgent {

    @UserMessage("""
            你是一名创意写作者。
            根据给定主题创作故事初稿，篇幅不超过三句话。
            仅返回故事内容，不输出其他任何文字。
            主题：{{topic}}。
            """)
    @Agent(description = "根据主题生成故事初稿", outputKey = "story")
    String generateStory(@V("topic") String topic);
}
```

A2A 暴露只绑定已有 LangChain4j agent bean：

```java
@Bean
CreativeWriterAgent creativeWriterAgent(ChatModel chatModel) {
    return AgenticServices.agentBuilder(CreativeWriterAgent.class)
            .chatModel(chatModel)
            .build();
}
```

```yaml
agents:
  a2a:
    exposed:
      - id: creative-writer
        bean-name: creativeWriterAgent
        method: generateStory
        public-name: 创意写作者
        public-description: 根据主题生成故事初稿
```

也可以提供轻量标注来减少配置，但标注对象是“暴露已有 bean”，不是定义新的 A2A agent：

```java
@A2AExpose(id = "creative-writer")
@Bean
CreativeWriterAgent creativeWriterAgent(ChatModel chatModel) {
    return AgenticServices.agentBuilder(CreativeWriterAgent.class)
            .chatModel(chatModel)
            .build();
}
```

第一版优先支持配置暴露方式，避免在 agent interface 上引入 A2A 专用语义。

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
  LangChain4j A2A client
    AgenticServices.a2aBuilder(...)
    @A2AContextId / @A2ATaskId
    ResultWithAgenticScope
  Spring config
    OtherAgentsA2AProperties
    A2A client bean creation

other-agents
  Standard LangChain4j agents
    CreativeWriterAgent
    AudienceEditorAgent
    StyleEditorAgent
  A2A server adapter
    A2AExposedAgentDefinition
    A2AExposedAgentRegistry
    A2AAgentCardFactory
    A2AAgentServer
    A2ARequestHandler
    A2AAgentExecutor
    LangChain4jAgentMethodInvoker
    A2ATaskStore
    JsonRpcA2ATransportWrapper
    JsonRpcA2AController
    A2AMessageMapper
```

设计边界：

- `backend` 不再保留自研 `OtherAgentsA2AClient` 和 `A2ARemoteAgentInvoker`。
- `backend` 的 A2A client 结构跟随 LangChain4j，不再复制 AgentScope client 分层。
- `other-agents` 的业务 agent 不感知 A2A。
- `other-agents` 的 A2A adapter 只负责协议、路由、task/context、agent-card、错误封装。
- Spring Controller 只做 HTTP 接入，JSON-RPC/A2A 语义由 transport wrapper 和 request handler 处理。

## Backend Client 设计

删除当前过渡性 client 实现：

- `OtherAgentsA2AClient`
- 当前过渡版 `A2ARemoteAgentInvoker`
- 当前过渡版 `A2ARemoteAgentRegistry`
- 旧固定远端 wrapper 类
- 旧 `A2AAgents` 固定接口集合

保留或新增：

```text
backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/
  A2AStoryAssistant
  A2AAgentConfig
  Backend A2A remote agent interfaces

backend/src/main/java/com/h/backend/chat/infrastructure/config/
  OtherAgentsA2AProperties
```

`A2AAgentConfig` 中通过 LangChain4j builder 创建远端 agent：

```java
A2ACreativeWriter creativeWriter = AgenticServices
        .a2aBuilder(properties.getCreativeWriterUrl(), A2ACreativeWriter.class)
        .outputKey("story")
        .build();
```

配置只保存远端 A2A endpoint：

```yaml
agents:
  a2a:
    other-agents:
      creative-writer-url: http://localhost:8082/a2a/agents/creative-writer
      audience-editor-url: http://localhost:8082/a2a/agents/audience-editor
      style-editor-url: http://localhost:8082/a2a/agents/style-editor
```

第一版不扩展 LangChain4j client。尤其不承诺自定义 outbound metadata，除非后续确认 LangChain4j A2A client 提供可插拔 hook 或我们明确实现一个兼容 wrapper。

## Other-Agents Server 设计

### 暴露定义

`A2AExposedAgentDefinition` 是 adapter 内部结构，由配置或 `@A2AExpose` 从已有 LangChain4j agent bean 生成：

```text
A2AExposedAgentDefinition
  id
  beanName
  method
  publicName
  publicDescription
  inputKeys
  outputKey
```

`inputKeys` 从目标方法参数上的 `@V` 读取。`outputKey` 优先从 `@Agent(outputKey = "...")` 读取，配置可覆盖。`publicName/publicDescription` 用于生成 agent-card，优先来自配置，其次来自 `@Agent(description = "...")` 或方法名。

### Server Facade

`A2AAgentServer` 对应 AgentScope 的 `AgentScopeA2aServer` 思路，但适配本项目的 LangChain4j agent bean。

它不监听端口，负责组装：

- exposed agent registry
- agent-card factory
- request handler
- agent executor
- task store
- transport wrapper

Spring Web 只注入 `A2AAgentServer` 或 `JsonRpcA2ATransportWrapper`。

### 请求流程

```text
HTTP POST /a2a/agents/{agentId}
  -> JsonRpcA2AController
  -> JsonRpcA2ATransportWrapper
  -> A2ARequestHandler
  -> A2AExposedAgentRegistry
  -> A2AAgentExecutor
  -> LangChain4jAgentMethodInvoker
  -> existing LangChain4j agent bean method
  -> A2A Task/Message response
```

职责拆分：

- `JsonRpcA2AController`：处理 HTTP path、headers、body。
- `JsonRpcA2ATransportWrapper`：解析 JSON-RPC、保留 id、封装 transport-level error。
- `A2ARequestHandler`：识别 `message/send`，校验 A2A 请求，调用 executor。
- `A2AAgentExecutor`：处理 task/context 生命周期，调用 method invoker。
- `LangChain4jAgentMethodInvoker`：把 A2A text parts 映射成 `@V` 参数，反射调用已有 LangChain4j agent bean。
- `A2AMessageMapper`：负责 A2A `Message`、text parts、artifact、status message 的转换。

### Agent Card

`A2AAgentCardFactory` 根据 exposed definition 生成 agent-card：

- `name`：`publicName` 或 `id`
- `description`：`publicDescription`
- `url`：`{publicUrl}/a2a/agents/{agentId}`
- `provider`：`h-agent other-agents`
- `version`：`0.1.0`
- `capabilities.streaming`：第一版为 `false`
- `defaultInputModes`：`text/plain`
- `defaultOutputModes`：`text/plain`
- `skills`：从 exposed method 生成一个 skill

### Task、Context 和 Memory

服务端入站规则：

- `Message.contextId` 作为 A2A 会话上下文。
- `Message.taskId` 存在时表示继续已有 task。
- `Message.taskId` 不存在时创建新 task。
- 第一版使用 in-memory `A2ATaskStore` 保存 task id、context id、状态和最近一次响应。

调用 LangChain4j agent bean 时：

- 如果目标方法有 `@MemoryId` 参数，传入 `contextId`；没有 `contextId` 时使用新建 task 的 context id。
- 普通 `@V` 参数按 exposed definition 的 `inputKeys` 从 text parts 映射。
- 第一版仅支持 text part。

响应规则：

- 返回 A2A `Task`，保证包含 `id` 和 `contextId`。
- task 完成时包含 text artifact。
- 执行失败时 task 状态为 failed，并在 status message 中写入错误摘要。
- 如果 LangChain4j agent 返回 `ResultWithAgenticScope`，第一版只取 `result()` 作为输出；scope 中的额外状态不进入 A2A 响应。

## 初始协议范围

第一版只实现 blocking `message/send`。

保留未来扩展点：

- `message/stream`
- `tasks/get`
- `tasks/cancel`
- task history
- push notification
- 非文本 part

不支持的方法返回标准 JSON-RPC/A2A 错误，不做临时字符串错误。

## 错误处理

Backend client 侧沿用 LangChain4j A2A client 行为：

- agent-card 读取失败：由 LangChain4j/A2A SDK 抛出异常。
- 远端 task failed/canceled/rejected：由 LangChain4j A2A client 转为调用异常。
- 缺少必填 input key：沿用 LangChain4j 参数解析行为。

Other-agents server 侧：

- 未知 `agentId`：返回 JSON-RPC invalid request 或 A2A agent not found 错误。
- 不支持的 method：返回 method not found。
- text parts 与 `@V` 参数不匹配：返回 invalid request。
- agent bean 调用失败：返回 failed task，保留 `taskId/contextId`。
- JSON 解析失败：返回 JSON-RPC parse error。

## 测试计划

Backend 测试：

- 配置绑定：三个远端 A2A endpoint URL。
- `A2AAgentConfig`：通过 `AgenticServices.a2aBuilder(url, Interface.class)` 创建远端 agent。
- A2A story workflow：远端 agent proxy 可以参与 `.subAgents(...)`。
- context/task 多轮：使用测试用 A2A echo server 或 server adapter 测试，验证 `@A2AContextId`、`@A2ATaskId` 能写回并复用。

Other-agents 测试：

- exposed definition：从配置绑定已有 LangChain4j agent bean/method。
- registry：按 agent id 查询 exposed definition，未知 id 行为正确。
- agent-card factory：生成 `/a2a/agents/{agentId}` URL 和基础 skill 信息。
- transport wrapper：保留 JSON-RPC id，分发 `message/send`。
- request handler：处理 blocking `message/send` 和 unsupported method。
- executor：创建/继续 task，保留 context id。
- method invoker：按 `@V` 参数顺序映射 text parts 并调用已有 agent bean。
- memory：当目标方法有 `@MemoryId` 时传入 context id。
- controller：`GET card` 和 `POST message/send`。
- 旧 endpoint：删除或改为 404。

端到端测试：

- `backend` 使用 LangChain4j A2A client 调用 `other-agents` 暴露的 creative-writer。
- `audience-editor` 和 `style-editor` 使用同一套 server adapter 暴露。
- A2A story flow 完整返回最终 story。

## 迁移策略

删除旧代码：

- backend：
  - `OtherAgentsA2AClient`
  - 当前过渡版 `A2ARemoteAgentInvoker`
  - 当前过渡版 `A2ARemoteAgentRegistry`
  - 旧固定远端 wrapper 类
  - 旧固定 `A2AAgents`
- other-agents：
  - 手写固定 endpoint controller 方法
  - `A2AAgentCardApplicationService`

保留：

- `A2AStoryAssistant`
- `A2AAgentConfig` 中故事创作 workflow 的整体结构
- `other-agents` 中已有 LangChain4j agent interface 和 bean
- 本地故事信息提取
- 本地风格评分

替换范围聚焦 A2A client 装配和 other-agents 的 A2A server adapter。

## 不在本次范围

- 修改 LangChain4j 源码
- 自研 LangChain4j A2A client 替代品
- streaming response
- task cancellation UI 或外部 cancel API
- 抽取共享 Maven module
- A2A endpoint 鉴权
- 注册到外部 A2A registry
- 文件、图片、音频、data part 等非文本 part
- 把 `AgenticScope` 全量序列化进 A2A 响应

## 验收标准

- backend 调用远端 agent 时使用 `AgenticServices.a2aBuilder(url, Interface.class)`。
- backend 不再手写 JSON-RPC `RestClient` 请求体。
- other-agents 的业务 agent 仍然是标准 LangChain4j agent，不引入 A2A 专用 agent 编程模型。
- other-agents 不再为每个 agent 写固定 Controller 方法。
- 新 URL `/a2a/agents/{agentId}` 可完成 blocking `message/send`。
- agent-card 使用 `/a2a/agents/{agentId}/.well-known/agent-card.json`。
- `contextId/taskId` 能从 backend 传到 other-agents，并由服务端返回后被 LangChain4j client 写回 `AgenticScope`。
- 新增远端能力时，服务端只需复用或新增 LangChain4j agent bean 并配置暴露；客户端只需新增本地 interface 并通过 LangChain4j A2A builder 创建。
- 对使用方而言，远端 A2A agent 可以像本地 workflow sub-agent 一样参与 `.subAgents(...)`。
