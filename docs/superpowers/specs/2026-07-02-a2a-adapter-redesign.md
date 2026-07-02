# A2A Adapter Redesign

Date: 2026-07-02

## Goal

Redesign the A2A integration between `backend` and `other-agents` using an AgentScope-inspired client/server adapter structure. The existing implementation is treated as transitional code and may be replaced without preserving old endpoint compatibility.

The redesign covers both sides:

- `backend` as the A2A client and LangChain4j workflow orchestrator.
- `other-agents` as the A2A server exposing remote story agents.

The design uses LangChain4j `1.17.0` and `langchain4j-agentic-a2a:1.17.0-beta27`, including the new `contextId` and `taskId` concepts.

## Reference Model

The design follows the structure of AgentScope's A2A extension:

- Client side:
  - `A2aAgent`
  - `AgentCardResolver`
  - `ClientEventHandlerRouter`
  - message conversion utilities
- Server side:
  - agent descriptor/registry
  - agent-card converter
  - agent executor
  - request handler
  - transport wrapper/controller

The implementation will not copy AgentScope types such as `Msg`, `Memory`, `Hook`, or `Flux<Event>`. Instead, it adapts the same boundaries to this project's Spring Boot and LangChain4j `AgenticScope` model.

## Public Endpoints

The old per-agent endpoint layout will be replaced.

New endpoints:

```text
GET  /a2a/agents/{agentId}/.well-known/agent-card.json
POST /a2a/agents/{agentId}
```

Old endpoints will be removed:

```text
/creative-writer/.well-known/agent-card.json
/creative-writer/a2a
/audience-editor/.well-known/agent-card.json
/audience-editor/a2a
/style-editor/.well-known/agent-card.json
/style-editor/a2a
```

## Backend Client Design

Replace the transitional client classes:

- `OtherAgentsA2AClient`
- `A2ARemoteAgentInvoker`
- `A2ARemoteAgentRegistry`

with a new client adapter package:

```text
backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/client/
  A2AAgentCardResolver
  WellKnownA2AAgentCardResolver
  FixedA2AAgentCardResolver
  A2ARemoteAgentDefinition
  A2ARemoteAgentRegistry
  A2ARemoteAgentInvoker
  A2AClientEventHandlerRouter
  A2AMessageMapper
  A2AInvocationIds
```

### Backend Configuration

The remote-agent configuration will use the new endpoint shape and explicit id keys:

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

### Backend Invocation Flow

```text
AgenticScope
  -> A2ARemoteAgentInvoker
  -> A2AMessageMapper
  -> A2A SDK Client.sendMessage
  -> A2AClientEventHandlerRouter
  -> write output/contextId/taskId back to AgenticScope
```

`A2ARemoteAgentInvoker` reads input values from `AgenticScope` using `inputKeys`. It builds an A2A `Message` with text parts, stable context information, metadata, and a new `messageId`.

The event handler router handles:

- `MessageEvent`
- `TaskEvent`
- `TaskUpdateEvent`

It extracts response text from message parts or task artifacts, captures returned `contextId` and `taskId`, and reports terminal task failures as invocation errors.

### Backend Context And Task Rules

- `contextId` is read from `context-id-key`.
- If no `contextId` is present, derive a stable value from the current `AgenticScope.memoryId()`.
- `taskId` is read from `task-id-key`.
- If no `taskId` is present, omit it and let the server create a new task.
- Each outbound request gets a new `messageId`.
- Metadata includes:
  - `memoryId`
  - `sessionId`, when derivable from memory id or available state
  - `agentId`
  - `source=backend`

When the remote response contains `contextId` or `taskId`, the invoker writes them back into `AgenticScope` using `context-id-key` and `task-id-key`.

## Other-Agents Server Design

Replace the existing hand-written `A2AController` and `A2AAgentCardApplicationService` with an AgentScope-inspired server adapter:

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

### Agent Descriptors

Each exposed remote agent is described by an `A2AAgentDescriptor`:

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

The registry owns descriptor lookup by `agentId`. Controllers and request handlers do not inject or branch on concrete agent beans.

### Server Request Flow

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

The controller only handles transport concerns:

- path extraction
- body/header collection
- forwarding to request handler
- returning JSON-RPC response

The request handler owns protocol concerns:

- `message/send`
- unsupported method handling
- JSON-RPC id preservation
- error response construction

The executor owns agent execution concerns:

- extracting text parts by descriptor input order
- passing input to the runner
- constructing the response message
- preserving `contextId` and `taskId`

### Server Agent Card Rules

`A2AAgentCardFactory` builds cards from descriptors and deployment config.

Required card fields:

- `name`: descriptor id or display name
- `description`: descriptor description
- `url`: `{publicUrl}/a2a/agents/{agentId}`
- `provider`: `h-agent other-agents`
- `version`: `0.1.0`
- `capabilities.streaming`: initially `false`
- `defaultInputModes`: `text/plain`
- `defaultOutputModes`: `text/plain`
- `skills`: one skill per descriptor

## Context, Task, And Memory Semantics

Inbound server request handling:

- Use `Message.contextId` as the remote conversation context.
- Use `Message.taskId` when present to continue a remote task.
- Create a new task id when `taskId` is absent.
- Read metadata:
  - `memoryId`
  - `sessionId`
  - `userId`

Remote memory id selection:

1. `metadata.memoryId`
2. `metadata.sessionId`
3. `Message.contextId`

Response messages must include:

- `contextId`
- `taskId`
- new `messageId`
- metadata:
  - `provider=other-agents`
  - `agent=<agentId>`

This mirrors AgentScope's `AgentRequestOptions` idea while using LangChain4j and this project's existing memory model.

## Initial Protocol Scope

The initial implementation supports blocking `message/send`.

The server adapter boundaries should leave room for:

- `message/stream`
- `tasks/get`
- `tasks/cancel`
- task history

Unsupported methods should return standard A2A/JSON-RPC errors instead of falling through to ad hoc exceptions.

## Error Handling

Backend client errors:

- Agent-card resolution failure -> `A2AAgentCardResolveException`
- Remote JSON-RPC error -> `A2ARemoteInvocationException`
- Failed, canceled, or rejected task -> exception with `taskId`, `contextId`, and terminal state
- Response without text parts -> protocol exception
- Missing required input key -> LangChain4j `MissingArgumentException`

Other-agents server errors:

- Unknown `agentId` -> JSON-RPC invalid request error
- Unsupported method -> method not found or unsupported operation error
- Missing required text part -> invalid request error
- Agent execution failure -> JSON-RPC error or A2A agent error message with preserved `contextId` and `taskId`

## Testing Plan

Backend tests:

- Configuration binding for `remote-agents`, including `context-id-key` and `task-id-key`.
- Card resolver URL construction.
- Message mapper:
  - input keys become text parts
  - memory id becomes `contextId`
  - metadata contains `memoryId`, `agentId`, and `source`
- Event handler:
  - `MessageEvent` extracts text/context/task ids
  - `TaskEvent` extracts artifacts/context/task ids
  - failed task state becomes invocation exception
- Registry creates remote agent executors by id.
- A2A story flow uses registry-provided remote agents.

Other-agents tests:

- Descriptor registry lookup and unknown id behavior.
- Agent-card factory emits `/a2a/agents/{agentId}` URLs.
- Request handler preserves JSON-RPC id and dispatches `message/send`.
- Executor maps text parts to descriptor input order.
- Response preserves or creates `contextId` and `taskId`.
- Controller tests for `GET card` and `POST message/send`.
- Old endpoint tests are removed or changed to expect 404.

## Migration

Remove transitional and old fixed-node code:

- backend:
  - `OtherAgentsA2AClient`
  - transitional `A2ARemoteAgentInvoker`
  - transitional `A2ARemoteAgentRegistry`
  - old fixed remote wrapper classes and `A2AAgents`
- other-agents:
  - hand-written fixed endpoint controller methods
  - `A2AAgentCardApplicationService`

Keep:

- `A2AStoryAssistant`
- story workflow shape in `A2AAgentConfig`
- local story-info extraction and style scoring

Replace only the remote-agent adapter and A2A transport layers.

## Out Of Scope

- Streaming responses.
- Task cancellation UI or external cancel API.
- Shared Maven module extraction.
- Authentication and authorization for A2A endpoints.
- Registry publication to an external A2A registry service.
- Non-text parts such as files, images, audio, or data parts.

These are intentionally deferred so the first implementation can land a clean blocking A2A text workflow.
