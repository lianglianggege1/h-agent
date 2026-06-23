# Domain Agent Pages and Runtime Routing Design

## Background

The project currently has a general chat flow built around `HAssistant`, user-managed `SystemPrompt` records, SSE streaming, persisted chat sessions, and agent run tracking. A new LangChain4j agentic agent has been introduced in `AgentConfig`: `CarRentalAssistant`. It is composed as a sequence with nested conditional emergency experts and uses annotation-based prompts inside each sub-agent service.

The product goal is to add a professional domain-agent experience:

1. Users can enter a dedicated domain Agent Q&A page, filter available domain agents, select one, and ask questions.
2. Users can enter an Agent management/detail area to inspect each Agent's orchestration topology.
3. The frontend can switch between different domain Agents by selecting an `agentId`, while the backend chooses the correct runtime orchestration.
4. The initial implementation only has one domain Agent, but the protocol must support multiple Agents.

The LangChain4j `langchain4j-agentic` package already exposes the actual agent topology through `AgentInstance`, `AgenticSystemTopology`, `ConditionalAgentInstance`, `LoopAgentInstance`, and `AgentMonitor`. The design must reuse these framework structures instead of inventing a separate topology model.

## Goals

1. Add a mobile-friendly `/agents` page for domain Agent Q&A.
2. Add `/me/agents` and `/me/agents/[agentId]` pages for Agent management and topology inspection.
3. Introduce a thin backend `AgentRegistry` for product-level Agent metadata and runtime routing.
4. Generate `AgentTopologyDto` from the real LangChain4j `AgentInstance` tree.
5. Keep the existing SSE chat experience while allowing domain Agent runtimes to execute differently from `HAssistant`.
6. Add real-time domain Agent step events through `AgentListener`, including support for parallel Agents.
7. Keep `promptId` for ordinary chat and avoid overloading it for domain Agent internal annotation prompts.

## Non-Goals

1. Do not implement editable domain Agent prompts in this iteration.
2. Do not show sub-agent raw outputs to end users in the Q&A page.
3. Do not iframe or directly embed `HtmlReportGenerator` HTML in the app.
4. Do not make a full visual node editor for Agent orchestration.
5. Do not allow changing `agentId` inside an existing chat session.

## Routes

### `/agents`

This is the domain Agent Q&A page.

It displays:

1. Search input.
2. Domain/category chips.
3. Agent cards.
4. The active Agent chat area.
5. Bottom message composer.

Selecting an Agent creates or activates a chat session bound to that `agentId`. Switching Agents creates or activates another session instead of mutating the current session.

### `/me/agents`

This is the domain Agent management list.

It displays registered Agents with:

1. Display name.
2. `agentId`.
3. Domain.
4. Tags.
5. Runtime type.
6. Enabled state.
7. Links to topology detail and Q&A.

This iteration is read-only.

### `/me/agents/[agentId]`

This is the Agent topology detail page.

It displays:

1. Agent summary metadata.
2. Runtime type and enabled state.
3. A horizontally scrollable topology tree.
4. State-key legend.
5. Node detail bottom drawer on mobile.
6. A "start Q&A with this Agent" action.

The topology view follows the logic of LangChain4j's `HtmlReportGenerator`: static system topology, not execution history. It shows node type badges such as `SEQUENCE`, `ROUTER`, `LOOP`, `PARALLEL`, `STAR`, and `AI_AGENT`.

## Backend Architecture

### AgentRegistry

The framework does not provide a product-level Agent catalog. `AgenticScopeRegistry` manages runtime scopes for one Agent, and `AgentMonitor` observes a single Agent. Therefore the app needs a thin registry for product metadata and routing.

`AgentRegistry` stores product metadata and a reference to the actual runtime bean:

```java
record AgentDefinition(
        String agentId,
        String displayName,
        String domain,
        List<String> tags,
        String summary,
        Object agentBean,
        AgentRuntimeType runtimeType,
        boolean enabled
) {}
```

Runtime types:

```java
enum AgentRuntimeType {
    STANDARD_STREAMING_CHAT,
    AGENTIC_SYNC
}
```

Initial entries:

1. `standard-chat`: the existing `HAssistant` flow.
2. `car-rental-assistant`: the new `CarRentalAssistant` agentic flow.

### AgentTopologyMapper

`AgentTopologyMapper` converts a framework `AgentInstance` tree into app JSON:

```java
AgentInstance root = (AgentInstance) definition.agentBean();
AgentTopologyDto dto = topologyMapper.from(definition, root);
```

It follows the same source fields as `HtmlReportGenerator.appendTopologyNode(...)`:

1. `agent.agentId()`
2. `agent.name()`
3. `agent.description()`
4. `agent.topology()`
5. `agent.type()`
6. `agent.outputKey()`
7. `agent.outputType()`
8. `agent.arguments()`
9. `agent.async()`
10. `agent.subagents()`

Special handling:

1. For `ROUTER`, call `agent.as(ConditionalAgentInstance.class).conditionalSubagents()` to map child conditions.
2. For `LOOP`, call `agent.as(LoopAgentInstance.class)` to read max iterations, exit condition, and test timing.
3. `PARALLEL` and `STAR` are rendered through their `AgenticSystemTopology` enum values.

### AgentController

Endpoints:

```text
GET /api/agents
GET /api/agents/{agentId}/topology
```

`GET /api/agents` returns enabled Agent summaries for both `/agents` and `/me/agents`.

`GET /api/agents/{agentId}/topology` returns a topology DTO generated from the registered Agent bean. Unknown or disabled Agents return a business error.

### Chat Runtime Routing

`ChatMessageRequest` gains `agentId`:

```java
record ChatMessageRequest(
        String message,
        String sessionId,
        Long promptId,
        String agentId
) {}
```

`ChatService.streamChat(...)` gains `agentId`:

```java
Flux<ChatStreamEvent> streamChat(
        Long userId,
        Long promptId,
        String agentId,
        String sessionId,
        String userMessage
);
```

Unified flow:

1. Acquire concurrency permit by user and session.
2. Resolve Agent definition from `AgentRegistry`.
3. Assert active session and verify session `agent_id` matches request `agentId`.
4. Resolve `promptId` only for `STANDARD_STREAMING_CHAT`.
5. Append user message.
6. Create `agent_runs`.
7. Execute through the matching executor.
8. Persist final assistant message.
9. Complete or fail the run.

Executors:

1. `HAssistantStreamingExecutor`
   - Uses existing `hAssistant.streamChat(...)`.
   - Emits `reasoning`, `chunk`, `image`, `done`, `blocked`, and `error`.
2. `AgenticSyncExecutor`
   - Executes `CarRentalAssistant.chat(memoryId, userMessage)`.
   - Emits `agent_step` events from `AgentListener`.
   - Emits final answer as `chunk`, then `done`.

The frontend keeps using SSE in both cases. Domain Agent runtime is synchronous internally in this iteration, but the user-facing transport remains streaming.

## Session and Prompt Model

Add `agent_id` to `chat_sessions`:

```sql
ALTER TABLE chat_sessions
ADD COLUMN agent_id VARCHAR(64) NULL;
```

Rules:

1. One session is bound to one `agent_id`.
2. Switching Agent means creating or activating another session.
3. Request `agentId` must match the session's `agent_id`.
4. Ordinary chat continues to use `prompt_id`.
5. Domain Agent sessions may have `prompt_id = NULL`.

`promptId` remains part of ordinary chat. It should not be overloaded for domain Agent internals because current domain Agent prompts are annotation-based and distributed across sub-agent services. Future domain Agent prompt management should use an Agent-specific profile model, for example:

```text
agent_prompt_profiles
- id
- agent_id
- name
- enabled

agent_prompt_overrides
- profile_id
- node_id
- system_message
- user_message_template
```

This makes prompt override naturally align with topology nodes.

## DTOs

### AgentSummaryDto

```java
record AgentSummaryDto(
        String agentId,
        String displayName,
        String domain,
        List<String> tags,
        String summary,
        String runtimeType,
        boolean enabled
) {}
```

### AgentTopologyDto

```java
record AgentTopologyDto(
        AgentSummaryDto agent,
        AgentTopologyNodeDto root,
        List<StateKeyDto> stateKeys
) {}

record AgentTopologyNodeDto(
        String nodeId,
        String name,
        String topology,
        String type,
        String description,
        String returnType,
        String outputKey,
        List<String> inputKeys,
        String condition,
        Boolean async,
        LoopMetaDto loop,
        List<AgentTopologyNodeDto> children
) {}

record LoopMetaDto(
        Integer maxIterations,
        String exitCondition,
        Boolean testExitAtLoopEnd
) {}

record StateKeyDto(
        String key,
        String type,
        String color
) {}
```

### AgentStepPayload

```java
record AgentStepPayload(
        String runId,
        String agentId,
        String invocationId,
        String nodeId,
        String nodeName,
        String topology,
        String status,
        Integer depth,
        Integer sequence
) {}
```

`status` values:

```text
running
completed
failed
```

`invocationId` distinguishes repeated node calls in loops and concurrent calls. `sequence` preserves event arrival order.

### ChatStreamEvent

Add a general payload field while preserving existing event construction:

```java
record ChatStreamEvent(
        String type,
        String content,
        ChatSessionMessageDto message,
        Object payload
) {}
```

`agent_step` example:

```java
new ChatStreamEvent(
        "agent_step",
        "正在执行：客户信息提取",
        null,
        payload
)
```

## Agent Step Events

Domain Agent process visibility uses `AgentListener`; no raw sub-agent outputs are shown.

Because parallel Agents are in scope, event routing must be cross-thread safe. Do not use `ThreadLocal`.

Use a memory-id keyed bridge:

```java
class AgentStepEventBridge {
    void register(String memoryId, Consumer<AgentStepPayload> emitter);
    void emit(Object memoryId, AgentStepPayload payload);
    void unregister(String memoryId);
}
```

The listener emits:

1. `beforeAgentInvocation`: `running`
2. `afterAgentInvocation`: `completed`
3. `onAgentInvocationError`: `failed`

The bridge routes by `agenticScope.memoryId()`, so parallel sub-agents and child threads can emit into the same SSE stream.

Parallel behavior:

```text
ParallelAgent running
SafetyAgent running
CostAgent running
PolicyAgent running
CostAgent completed
SafetyAgent completed
PolicyAgent completed
ParallelAgent completed
```

The frontend must not assume one current step. It maintains a step map/list keyed by `invocationId`.

## Frontend State and Rendering

Frontend stream handling gains `agent_step`:

```ts
type AgentStep = {
  invocationId: string;
  nodeId: string;
  nodeName: string;
  topology: string;
  status: "running" | "completed" | "failed";
  depth: number | null;
  sequence: number;
};
```

Message state stores pending and completed Agent steps for the assistant turn.

Rendering rules:

1. Show a collapsible "执行过程" section in assistant turns.
2. Multiple steps can be `running` at the same time.
3. `completed` steps remain visible.
4. `failed` steps are highlighted.
5. Final assistant answer appears after `chunk`.
6. Steps do not display sub-agent outputs.

Topology rendering rules:

1. Use a horizontally scrollable tree on mobile.
2. Use topology badges and colored left borders.
3. Use `condition` labels for router children.
4. Use loop tags for `LOOP`.
5. Let node tap open a bottom drawer with full node details.

## Error Handling

1. Unknown or disabled `agentId`: return a business error.
2. Session `agent_id` mismatch: return a business error and ask the user to create a new session.
3. Topology generation failure: show an error state on the detail page; Q&A remains unaffected.
4. Domain Agent execution failure:
   - User message remains persisted.
   - `agent_runs` is marked `FAILED`.
   - A `failed` `agent_step` is emitted if the failed node is known.
   - SSE emits `error`.
5. Partial parallel failure follows framework behavior:
   - If the framework throws, the run fails.
   - If the framework recovers and returns a final answer, show failed step state and final answer.

## Testing

Backend tests:

1. `AgentRegistry`
   - Lists enabled Agents.
   - Rejects unknown Agents.
2. `AgentTopologyMapper`
   - Maps sequence children.
   - Maps router conditions.
   - Maps loop metadata.
   - Preserves `PARALLEL` and `STAR` topology enum values.
3. `AgentStepEventBridge`
   - Routes events by memory ID.
   - Does not cross streams for concurrent memory IDs.
   - Stops emitting after unregister.
4. `ChatSessionService`
   - Creates sessions with `agentId`.
   - Rejects mismatched request/session `agentId`.
5. `ChatServiceImpl`
   - Keeps existing `HAssistant` SSE behavior.
   - Emits `agent_step`, final `chunk`, and `done` for domain Agent runtime.

Frontend tests:

1. Agent API client parses list and topology responses.
2. Stream parser handles `agent_step`.
3. Chat state can add and update parallel running steps.
4. Existing `chunk`, `reasoning`, `image`, `blocked`, `done`, and `error` behavior remains compatible.

Manual verification:

1. `/chat` ordinary prompt-based chat still works.
2. `/agents` can start a car-rental Agent session.
3. Domain Agent Q&A shows process steps and final response.
4. `/me/agents/[agentId]` shows a topology tree generated from `AgentInstance`.
5. Parallel or simulated parallel step events do not overwrite each other.

## Implementation Order

1. Add `chat_sessions.agent_id` migration and entity/DTO plumbing.
2. Add Agent DTOs and `agentId` to chat/session request DTOs.
3. Implement `AgentRegistry` with `standard-chat` and `car-rental-assistant`.
4. Implement `AgentTopologyMapper`.
5. Add `AgentController`.
6. Implement `AgentStepEventBridge` and `AgentListener` integration.
7. Split chat execution into standard streaming and agentic sync executors.
8. Extend frontend stream handling for `agent_step`.
9. Add `/agents`.
10. Add `/me/agents` and `/me/agents/[agentId]`.
11. Run regression checks for existing `/chat`.
