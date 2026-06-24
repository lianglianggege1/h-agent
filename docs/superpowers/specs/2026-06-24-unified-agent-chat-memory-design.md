# Unified Agent Chat and Scoped Memory Design

- Date: 2026-06-24
- Scope: chat session routing, ordinary/domain Agent frontend integration, and LangChain4j memory identity
- Status: design for review

## Background

The app now has two chat experiences:

1. Ordinary chat at `/chat`, backed by `standard-chat`, user-managed `SystemPrompt`, knowledge base, and streaming `HAssistant`.
2. Domain Agent chat at `/agents`, backed by registered domain Agents such as `car-rental-assistant`, annotation-based prompts, agentic orchestration, and child Agent step events.

The session model already stores `agent_id`, and the backend validates that a stream request's `agentId` matches the active session. The frontend is the weak point: `/chat` restores historical sessions but still sends messages with `agentId: "standard-chat"`. A historical domain Agent session can therefore be displayed in the ordinary chat page while the next request is incorrectly treated as ordinary chat.

There is also a memory issue in the agentic runtime. The current domain Agent root memory id is shared by all sub-agents. `RedisChatMemoryStore` resolves that id to one session-level memory snapshot, so multiple child Agents can write unrelated context into the same window. In parallel or STAR topologies this can also create write races and confusing duplicated facts.

## Goals

1. Make `/chat` the unified Q&A entry for ordinary chat and domain Agents.
2. Keep ordinary chat prompt editing and knowledge management available for `standard-chat`.
3. Keep domain Agent topology and orchestration management under `/me/agents`.
4. Restore historical sessions with the correct Agent mode, prompt mode, and send payload.
5. Introduce memory identity v2 with per-Agent and per-sub-agent scopes.
6. Keep shared domain facts in structured state, not duplicated across child Agent chat memories.
7. Support future parallel and STAR Agent execution without cross-thread memory corruption.

## Non-Goals

1. Do not implement editable domain Agent prompts in this iteration.
2. Do not merge `/me/agents` management into `/chat`.
3. Do not change the persisted chat message model for user-visible conversation history.
4. Do not make all child Agents persistent-memory-enabled by default.
5. Do not allow changing the `agentId` of an existing session.

## Product Model

The session is the source of truth.

```text
sessionId -> agentId -> runtimeType -> frontend mode + backend executor
```

Ordinary chat is just one registered Agent:

```text
standard-chat
```

Domain Agents are registered in the same `AgentRegistry`, for example:

```text
car-rental-assistant
```

The frontend should not infer runtime mode from route alone. It should hydrate from `ChatSessionMetaDto.agentId` and Agent catalog metadata.

## Route Design

### `/chat`

Unified chat page for all Agent Q&A.

It displays:

1. Current Agent identity.
2. Session history.
3. Conversation messages.
4. Agent-specific controls.
5. Composer.

When current Agent is `standard-chat`:

1. Show `SystemPrompt` selector.
2. Show knowledge-base link for the selected prompt.
3. Show system prompt management link.
4. Send `promptId` and `agentId = "standard-chat"`.

When current Agent is a domain Agent:

1. Show domain Agent selector or compact current-Agent card.
2. Show topology detail link to `/me/agents/{agentId}`.
3. Hide ordinary `SystemPrompt` selector.
4. Send `promptId = null` and the current session's `agentId`.
5. Show child Agent execution status when `agent_step` SSE events arrive.

### `/agents`

Domain Agent discovery and quick-start page.

This route can remain, but it should no longer own a separate chat implementation. Selecting an Agent should create or activate a session for that `agentId`, then navigate to:

```text
/chat?agentId={agentId}
```

This keeps a focused mobile discovery page while avoiding duplicated chat logic.

### `/me/agents`

Domain Agent management list remains read-only for now. It links to topology details and quick-start actions.

### `/me/agents/{agentId}`

Topology detail page remains the place to inspect static orchestration. It links to `/chat?agentId={agentId}` for Q&A.

## Session API Design

Extend session DTOs with Agent display metadata so the frontend does not need to guess from IDs.

```java
record ChatSessionMetaDto(
        String sessionId,
        String title,
        Long promptId,
        String agentId,
        String agentDisplayName,
        String agentDomain,
        String runtimeType,
        int messageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean archived
) {}
```

Apply the same additions to `ChatSessionSummaryDto` for history cards.

Creation rules:

1. `createSession(promptId, null)` creates ordinary `standard-chat`.
2. `createSession(promptId, "standard-chat")` creates ordinary chat and resolves `promptId`.
3. `createSession(null, domainAgentId)` creates a domain Agent session and stores `prompt_id = null`.
4. Switching Agent always creates or activates a different session. It never mutates an existing session's `agent_id`.

Hydration rules:

1. `hydrateSession(...)` sets `currentAgentId` from `open.session.agentId`.
2. `selectedPromptId` is meaningful only when `agentId == "standard-chat"`.
3. The send payload uses the hydrated session Agent, not a route default.

## Frontend State Design

`/chat` should hold:

```ts
currentAgent: AgentSummary | null
selectedPromptId: number | null
sessionId: string | null
messages: UiChatMessage[]
```

It should derive:

```ts
const isStandardChat = currentAgent?.agentId === "standard-chat";
const isDomainAgent = currentAgent && !isStandardChat;
```

Sending should use:

```ts
body: JSON.stringify({
  message: content,
  sessionId,
  promptId: isStandardChat ? selectedPromptId : null,
  agentId: currentAgent.agentId,
})
```

This removes the hard-coded `agentId: "standard-chat"` bug.

The history drawer should show Agent context:

```text
普通聊天 / 默认提示词
租车应急协助 Agent / 租车救援
```

Opening a history session hydrates both messages and Agent mode.

## Backend Runtime Design

The backend routing model stays conceptually correct:

1. Resolve Agent from request `agentId`.
2. Validate active session owns the same `agentId`.
3. Resolve `promptId` only for `STANDARD_STREAMING_CHAT`.
4. Build root execution identity.
5. Execute through the runtime-specific `ChatAgentExecutor`.

The important change is that the root execution identity must be separated from child memory identity.

## Memory Identity v2

### Root Execution ID

The root id is used for:

1. Agent step event routing.
2. Tool event routing.
3. Agent run correlation.
4. High-level execution identity.

Format:

```text
exec:v2:user:{userId}:session:{sessionId}:agent:{agentId}
```

For ordinary chat, the memory id may keep the existing compact format during migration, but new code should use the v2 parser.

### Scoped Memory ID

Child Agent chat memory uses scoped ids:

```text
mem:v2:user:{userId}:session:{sessionId}:agent:{agentId}:scope:{scopeKey}
```

Examples:

```text
mem:v2:user:1:session:sess-001:agent:car-rental-assistant:scope:customer-info-extractor
mem:v2:user:1:session:sess-001:agent:car-rental-assistant:scope:towing-agent
mem:v2:user:1:session:sess-001:agent:car-rental-assistant:scope:fire-agent
```

`scopeKey` must be stable technical metadata, not display text. Use lowercase kebab-case.

Initial car rental scopes:

| Scope | Purpose | Persistent Chat Memory |
| --- | --- | --- |
| `customer-info-extractor` | Multi-turn extraction of customer rescue details | Yes |
| `towing-agent` | Towing response from current structured state | No by default |
| `emergency-extractor` | Extract emergency categories from current message and customer state | No by default |
| `fire-agent` | Fire advice for current emergency | No by default |
| `medical-agent` | Medical advice for current emergency | No by default |
| `police-agent` | Police advice for current emergency | No by default |
| `emergency-response` | Merge current emergency expert outputs | No by default |
| `response-generator` | Final response composition | No by default |

Only nodes that truly need multi-turn private context should use persistent chat memory. Other nodes should consume `AgenticScope` state and produce outputs without writing their own chat window.

### Shared Facts

Shared facts should live in structured state, not in every child Agent memory.

Current shared state:

```text
customerInfo
emergencies
fireEmergency
medicalEmergency
policeEmergency
fireResponse
medicalResponse
policeResponse
emergencyResponse
response
```

Future persistent structured state can be added as:

```text
agent_session_state
- id
- session_id
- agent_id
- state_key
- state_payload_json
- state_version
- created_at
- updated_at
```

This is separate from chat memory and avoids the same facts being written into multiple LLM windows.

## Memory Storage Design

`ChatMemoryContext` should include Agent and scope.

```java
record ChatMemoryContext(
        Long userId,
        Long promptId,
        String sessionId,
        String agentId,
        String memoryScope
) {}
```

For ordinary chat:

```text
agentId = standard-chat
memoryScope = default
```

For domain Agent child memories:

```text
agentId = car-rental-assistant
memoryScope = customer-info-extractor
```

### Database

Extend `chat_memory_snapshots`:

```sql
ALTER TABLE chat_memory_snapshots
ADD COLUMN agent_id VARCHAR(64) NOT NULL DEFAULT 'standard-chat',
ADD COLUMN memory_scope VARCHAR(128) NOT NULL DEFAULT 'default';

CREATE UNIQUE INDEX uk_chat_memory_snapshots_session_scope
ON chat_memory_snapshots(session_id, agent_id, memory_scope);
```

The existing unique constraint on `session_id` must be replaced or relaxed so multiple memory scopes can exist for one session.

### Redis

Redis keys include scope:

```text
chat:memory:{userId}:{sessionId}:{agentId}:{memoryScope}
chat:memory:version:{userId}:{sessionId}:{agentId}:{memoryScope}
chat:memory:dirty:{userId}:{sessionId}:{agentId}:{memoryScope}
chat:memory:lock:{sessionId}:{agentId}:{memoryScope}
```

Resident session tracking can remain per user:

```text
chat:memory:resident:{userId}
```

When a session is restored, all snapshots for that session may be lazily restored per scope on first access. There is no need to eagerly load every child Agent memory.

## AgentConfig Pattern

Create a helper for child Agent memory providers.

Conceptual API:

```java
private ChatMemoryProvider scopedMemoryProvider(String scopeKey) {
    return rootMemoryId -> MessageWindowChatMemory.builder()
            .id(memoryIdFactory.scoped(rootMemoryId, scopeKey))
            .maxMessages(10)
            .alwaysKeepSystemMessageFirst(true)
            .chatMemoryStore(redisChatMemoryStore)
            .build();
}
```

Use it only on nodes that should keep persistent private chat memory.

For stateless nodes, do not configure `chatMemoryProvider`. They still receive required data through `@V` parameters and `AgenticScope`.

## Agent Step Events

`AgentStepListener` should continue emitting by root execution id so the frontend can subscribe once per run.

The scoped memory id is an internal storage identity. It should not replace the event routing id.

Parallel child Agents may emit events concurrently. `AgentStepEventBridge` must remain thread-safe and keyed by root execution id.

## Migration and Compatibility

The memory parser should support:

1. Existing ordinary format:
   ```text
   {userId}:{promptId}:{sessionId}
   ```
2. Existing domain format:
   ```text
   {userId}:agent:{agentId}:{sessionId}
   ```
3. New execution format:
   ```text
   exec:v2:user:{userId}:session:{sessionId}:agent:{agentId}
   ```
4. New scoped memory format:
   ```text
   mem:v2:user:{userId}:session:{sessionId}:agent:{agentId}:scope:{scopeKey}
   ```

Existing memory snapshots without `agent_id` and `memory_scope` should be treated as:

```text
agent_id = standard-chat
memory_scope = default
```

Existing domain Agent session-level memory can be ignored or migrated to:

```text
memory_scope = legacy-root
```

The new car rental Agent should not read `legacy-root` by default because it may contain mixed child-Agent context.

## Error Handling

1. If `/chat` opens a session whose `agentId` no longer exists or is disabled, show a recoverable error and offer to create a `standard-chat` session.
2. If a send payload's `agentId` does not match the session, keep the backend `40008` validation and show “会话不属于当前 Agent，请重新创建会话”.
3. If a scoped memory id cannot be parsed, fail fast with a business-safe SSE error and log the invalid id server-side.
4. If a stateless child Agent accidentally declares `@MemoryId` without a memory provider, tests should catch the mismatch.

## Testing Plan

Backend tests:

1. `/chat` stream uses session `agentId` and rejects mismatched request Agent.
2. `ChatSessionMetaDto` and `ChatSessionSummaryDto` include Agent metadata.
3. `RedisChatMemoryStore` parses legacy and v2 memory ids.
4. `ChatMemorySnapshotServiceImpl` stores separate rows for different `memoryScope` values in the same session.
5. Parallel scoped memory writes do not overwrite each other.
6. Car rental Agent config uses scoped memory only for intended nodes.

Frontend tests:

1. Hydrating a domain Agent session sets `currentAgentId` to that domain Agent.
2. Sending from a hydrated domain Agent session uses that `agentId`, not `standard-chat`.
3. Ordinary chat still sends selected `promptId`.
4. Domain Agent mode hides ordinary prompt controls and shows topology entry.
5. History cards display Agent context.

Manual verification:

1. Start ordinary chat, switch prompts, send a message.
2. Start car rental Agent from `/me/agents/{agentId}`, send a message, see child Agent status.
3. Open history from `/chat`, select the car rental session, send another message, verify backend still uses `car-rental-assistant`.
4. Inspect Redis or DB and confirm child Agent scoped memories are separate.

## Implementation Order

1. Add Agent metadata to session DTOs and mapping.
2. Refactor `/chat` state to hydrate `currentAgent`.
3. Route `/agents` quick-start into `/chat`.
4. Remove hard-coded `standard-chat` from `/chat` send payload.
5. Add memory id factory and parser for v2 ids.
6. Extend `ChatMemoryContext`, Redis keys, and snapshot persistence with `agentId + memoryScope`.
7. Add scoped memory provider helper in `AgentConfig`.
8. Restrict persistent chat memory to selected child Agents.
9. Add tests and run focused backend/frontend verification.

