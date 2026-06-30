# Backend and Other Agents A2A Channel Design

- Date: 2026-06-30
- Scope: A2A channel between `backend` and `other-agents`, unified `/chat` entry, Agent topology rendering, and runtime step marking
- Status: design for review

## Background

The app already uses `/chat` as the unified text entry for ordinary chat and domain Agents. The frontend sends messages to `POST /api/chat/messages/stream`, and the backend resolves the requested `agentId`, validates the active session, creates an Agent run, executes the configured runtime, streams SSE events, and persists user-visible messages.

The backend also already has:

1. `AgentRegistry` as the source of enabled Agent definitions.
2. `AgentRuntimeType` and `ChatAgentExecutor` as the runtime dispatch layer.
3. `AgenticSyncExecutor` for LangChain4j agentic workflows that expose `chat(String memoryId, String message)`.
4. `AgentStepListener` and `AgentStepEventBridge` for `agent_step` SSE events.
5. `AgentTopologyMapper` for converting a LangChain4j `AgentInstance` tree into frontend topology data.

The new `other-agents` project should run as a separate Agent process. The goal is to connect `backend` to `other-agents` through A2A while keeping the frontend unaware of that transport boundary.

## Goals

1. Keep `/chat` as the only user-facing chat entry.
2. Allow a backend Agent workflow to call an Agent hosted by `other-agents` through A2A.
3. Register the A2A-backed workflow as a normal `AgentDefinition`.
4. Preserve existing chat session validation, persistence, concurrency guard, and SSE event model.
5. Keep the orchestration graph drawable in `/me/agents/{agentId}`.
6. Mark the A2A node during execution with existing `agent_step` events.
7. Make the first implementation a small example that proves the transport and graph behavior before expanding to production Agents.

## Non-Goals

1. Do not add a new frontend route or a new chat endpoint for A2A.
2. Do not make the frontend aware of whether a node is local or remote.
3. Do not replace existing domain Agents such as `car-rental-assistant`.
4. Do not introduce a new conversation table for remote Agent messages in the first version.
5. Do not require remote step-level events from `other-agents` in the first version.
6. Do not implement multi-remote-Agent discovery or dynamic marketplace registration in the first version.

## Product Model

The user sees a normal Agent in the Agent catalog:

```text
a2a-story-assistant
```

The frontend uses the existing flow:

```text
/chat?agentId=a2a-story-assistant
```

The runtime shape is:

```text
frontend /chat
  -> backend /api/chat/messages/stream
    -> AgentRegistry resolves a2a-story-assistant
      -> AgenticSyncExecutor executes the backend root workflow
        -> local node extracts or normalizes the request
        -> A2A client node calls other-agents
        -> local node produces the final answer
```

The user-visible assistant message is persisted by `backend`, not by `other-agents`.

## Recommended Example

Use a small writing workflow, inspired by the referenced LangChain4j A2A examples:

```text
A2A Story Assistant
```

The backend workflow:

1. `StoryRequestParser`: extracts `topic`, `style`, and optional `audience` from the user's message.
2. `RemoteCreativeWriter`: A2A client node that sends `topic` to `other-agents`.
3. `StoryResponseComposer`: local node that adapts the remote draft to the user's requested style and returns the final response.

The `other-agents` service:

1. Exposes an A2A AgentCard.
2. Receives A2A messages.
3. Runs a simple creative writer Agent or deterministic demo implementation.
4. Returns a story draft as text.

This example is intentionally simple because it proves the important mechanics:

1. Cross-process Agent call.
2. Backend-owned `/chat` lifecycle.
3. Topology rendering with a remote node.
4. Runtime node marking.

## Architecture

### Backend

Add a backend Agent interface for the A2A-backed root workflow:

```java
public interface A2AStoryAssistant {
    @Agent(name = "A2A故事助手")
    ResultWithAgenticScope<String> chat(
            @MemoryId String memoryId,
            @V("message") String message
    );
}
```

Add a typed A2A client interface for the remote node:

```java
public interface RemoteCreativeWriter {
    @Agent(name = "远端创意写作者", description = "通过 A2A 调用 other-agents 生成故事初稿")
    String generateStory(@V("topic") String topic);
}
```

Build the remote client with LangChain4j A2A support:

```java
RemoteCreativeWriter remoteWriter = AgenticServices
        .a2aBuilder(otherAgentsA2AUrl, RemoteCreativeWriter.class)
        .listener(agentStepListener)
        .outputKey("draft")
        .build();
```

Then compose it into a normal backend workflow:

```java
A2AStoryAssistant assistant = AgenticServices
        .sequenceBuilder(A2AStoryAssistant.class)
        .listener(agentStepListener)
        .subAgents(storyRequestParser, remoteWriter, storyResponseComposer)
        .outputKey("response")
        .build();
```

Register it like any other domain Agent:

```java
new AgentDefinition(
        "a2a-story-assistant",
        "A2A 故事协作 Agent",
        "跨服务协作",
        List.of("A2A", "故事创作", "远端 Agent"),
        "通过 backend 编排并调用 other-agents 的远端写作 Agent",
        a2aStoryAssistant,
        AgentRuntimeType.AGENTIC_SYNC,
        true
)
```

No frontend change is required for message sending if `/chat` already sends the hydrated session `agentId`.

### Other Agents

`other-agents` runs on a separate port, currently intended as:

```yaml
server:
  port: 8082
```

It should expose:

```text
GET /.well-known/agent-card.json
POST /...
```

The exact JSON-RPC/SSE endpoint shape should follow the A2A SDK transport used by the dependency version in the project. The important contract for the backend client is:

1. `AgenticServices.a2aBuilder(baseUrl, RemoteCreativeWriter.class)` can fetch the AgentCard from `baseUrl`.
2. The backend client can send a user `Message`.
3. The remote server responds with either a `MessageEvent` or terminal `TaskEvent`.
4. The response text can be parsed as the method return type.

For the first example, `other-agents` does not need to expose its internal topology to `backend`.

## Runtime Flow

### Send Message

```text
POST /api/chat/messages/stream
body:
{
  "agentId": "a2a-story-assistant",
  "sessionId": "...",
  "promptId": null,
  "message": "写一个赛博朋克风格的月球救援故事"
}
```

### Backend Stream Preparation

1. Resolve `a2a-story-assistant` from `AgentRegistry`.
2. Validate that the active session belongs to `a2a-story-assistant`.
3. Persist the user message.
4. Create `agent_runs` row.
5. Execute through `AgenticSyncExecutor`.

### Agentic Execution

```text
AgenticSyncExecutor
  -> A2AStoryAssistant.chat(memoryId, message)
    -> StoryRequestParser
    -> RemoteCreativeWriter via A2A
    -> StoryResponseComposer
```

### Stream Events

The frontend receives the existing event types:

```text
user_message
agent_step
chunk
done
error
```

Example step sequence:

```json
{"type":"agent_step","payload":{"nodeId":"story-request-parser","status":"running"}}
{"type":"agent_step","payload":{"nodeId":"story-request-parser","status":"completed"}}
{"type":"agent_step","payload":{"nodeId":"remote-creative-writer","status":"running"}}
{"type":"agent_step","payload":{"nodeId":"remote-creative-writer","status":"completed"}}
{"type":"agent_step","payload":{"nodeId":"story-response-composer","status":"running"}}
{"type":"agent_step","payload":{"nodeId":"story-response-composer","status":"completed"}}
{"type":"chunk","content":"...final answer..."}
{"type":"done","message":{...}}
```

If the remote call fails, `AgenticSyncExecutor` should mark the run failed and emit the existing public error event:

```text
AI 服务调用失败
```

Internal logs should include the remote endpoint and A2A task/context identifiers when available.

## Topology Design

The backend root workflow remains a LangChain4j `AgentInstance`, so the existing topology endpoint can keep its current shape:

```text
GET /api/agents/a2a-story-assistant/topology
```

Expected graph:

```text
A2A 故事协作 Agent
  -> 故事请求解析器
  -> 远端创意写作者
  -> 故事回复生成器
```

The remote node should be represented as a normal node with metadata that makes the transport visible on the management page without affecting chat:

```json
{
  "nodeId": "remote-creative-writer",
  "name": "远端创意写作者",
  "topology": null,
  "type": "RemoteCreativeWriter",
  "description": "通过 A2A 调用 other-agents 生成故事初稿",
  "outputKey": "draft",
  "inputKeys": ["topic"],
  "children": []
}
```

First version can show remote-ness through naming and description only. A later enhancement can add an explicit node field:

```java
String transport; // LOCAL, A2A, MCP, HTTP
String endpoint;
```

That enhancement would require updating `AgentTopologyNodeDto`, `AgentTopologyMapper`, and the frontend graph renderer.

## Step Marking Design

`AgentStepListener` should remain the primary mechanism.

For the first version:

1. Attach `agentStepListener` to the A2A client node when building it.
2. The listener emits `running` before the backend starts the A2A call.
3. The listener emits `completed` after the A2A client returns.
4. The listener emits `failed` if the A2A call throws.

This marks the remote node at the boundary level:

```text
backend entered remote call -> backend received remote result
```

It does not attempt to display internal steps from `other-agents`.

Future extension:

1. Let `other-agents` emit A2A task metadata or custom events.
2. Map remote child steps into backend `agent_step` payloads.
3. Prefix remote child node IDs to avoid collisions:

```text
remote-creative-writer/outline-agent
remote-creative-writer/draft-agent
```

## Memory and Context

Backend remains the owner of user-facing chat memory and session state.

For first version:

1. Backend passes the current user message and extracted state to the remote Agent.
2. Remote Agent can be stateless.
3. Backend persists only the final assistant response.
4. Backend may store returned A2A `contextId` and `taskId` inside `AgenticScope` if the A2A method includes annotated parameters.

For multi-turn remote continuity, define an A2A client method with context propagation:

```java
ResultWithAgenticScope<String> generateStory(
        @V("topic") String topic,
        @A2AContextId @V("a2aContextId") String contextId,
        @A2ATaskId @V("a2aTaskId") String taskId
);
```

The remote context IDs should not replace backend `sessionId`. They are transport-level continuation IDs.

Recommended storage rule:

```text
backend sessionId: user-visible conversation identity
A2A contextId/taskId: remote transport continuation identity
```

If persistent remote context is needed later, store it in an Agent session state table keyed by:

```text
userId + sessionId + agentId + remoteNodeId
```

## Configuration

Add backend configuration:

```yaml
agents:
  a2a:
    other-agents:
      base-url: http://localhost:8082
      enabled: true
      timeout: 60s
```

Use configuration rather than hard-coding `localhost:8082` in Agent builders.

For local development:

```text
backend:      http://localhost:8080 or existing backend port
other-agents: http://localhost:8082
frontend:     unchanged
```

## Error Handling

### Remote Service Unavailable

If `other-agents` is down:

1. Backend A2A client builder may fail during startup if the AgentCard is fetched eagerly.
2. Prefer lazy construction or disabled registration when `agents.a2a.other-agents.enabled=false`.
3. For development, failing fast is acceptable if the example Agent is enabled by default.

Recommended production behavior:

1. If remote AgentCard cannot be fetched, register the Agent as disabled.
2. `/api/agents` does not list disabled Agents.
3. If a session references a now-disabled A2A Agent, existing disabled-Agent error handling applies.

### Remote Execution Failure

If an A2A call fails during execution:

1. Emit `agent_step` with `failed` for the remote node.
2. Mark `agent_runs.status = failed`.
3. Mark telemetry failure.
4. Emit a public `error` SSE event.
5. Do not persist a partial assistant message unless a complete final answer exists.

### Timeout

The first version can rely on the A2A client timeout if exposed by the dependency. If not exposed, wrap the call in a bounded executor or add a client-side transport timeout when implementing.

## Observability

Add logs around the remote boundary:

```text
runId
sessionId
agentId
remoteNodeId
remoteBaseUrl
a2aContextId
a2aTaskId
durationMs
status
```

Telemetry should continue to use the backend run as the parent run. Remote execution may initially appear as a single child step rather than a nested trace.

## Implementation Plan

1. Confirm the exact A2A dependency artifacts and server API available in `backend` and `other-agents`.
2. Implement `other-agents` A2A creative writer server.
3. Add backend A2A client interface and configuration properties.
4. Add backend `A2AStoryAssistant` workflow bean.
5. Register `a2a-story-assistant` in `AgentDefinitionConfig`.
6. Verify `/api/agents` lists the new Agent.
7. Verify `/api/agents/a2a-story-assistant/topology` renders the remote node.
8. Verify `/chat?agentId=a2a-story-assistant` streams `agent_step`, `chunk`, and `done`.
9. Add focused tests for registry, topology mapping, and executor failure behavior.

## Acceptance Criteria

1. Starting `other-agents` and `backend` allows a user to chat with `a2a-story-assistant` from `/chat`.
2. The frontend request still goes through `POST /api/chat/messages/stream`.
3. The user message and assistant response are persisted in the existing chat message tables.
4. The Agent run is created and completed or failed in the existing run tables.
5. The topology endpoint shows the A2A-backed node as part of the workflow.
6. During execution, the remote node is marked running and completed or failed through `agent_step`.
7. Stopping `other-agents` produces a controlled backend error event and failed run.

## Open Questions

1. Should `a2a-story-assistant` be enabled by default in local development, or gated by `agents.a2a.other-agents.enabled`?
2. Should the first version use a deterministic remote demo response to avoid model-key dependency, or use a real `ChatModel` in `other-agents`?
3. Should remote A2A `contextId/taskId` be persisted immediately, or kept only in ephemeral `AgenticScope` until multi-turn remote continuity is required?
4. Should the topology DTO add an explicit `transport` field now, or should the first version rely on node naming and description?
