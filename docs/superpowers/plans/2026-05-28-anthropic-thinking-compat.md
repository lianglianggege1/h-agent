# Anthropic Thinking 兼容实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让聊天前后端在保留旧 `<think>...</think>` 展示能力的前提下，同时支持 `anthropic` 模式独立 thinking 的流式展示、成功落库与历史回放。

**架构：** 后端在 `ChatServiceImpl` 中通过 `langchain4j` 的 `onPartialThinking(...)` 发送新的 `reasoning` SSE 事件，并在成功完成时先落 `REASONING` 消息、再落 assistant 回复。前端扩展会话消息类型与 SSE 解析协议，并将聊天页的消息变换逻辑抽到纯函数辅助模块中，使 reasoning 占位、历史 hydration 与旧 `<think>` 兼容都可单测覆盖。

**技术栈：** Java 23, Spring Boot 3.4.0, langchain4j 1.15.0, JUnit 5, Mockito, Next.js 16, React 19, TypeScript, Node `--test`, SSE。

---

## 文件结构

### 后端

- 修改：`backend/src/main/java/com/h/backend/chat/model/ChatSessionMessage.java`
  - 为会话消息快照增加 `messageType`，使 `payload_json` 和业务语义对齐。

- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatSessionMessageDto.java`
  - 历史接口 DTO 增加 `messageType` 字段，前端可显式区分 reasoning / assistant / blocked / user。

- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java`
  - 声明 `appendReasoningMessage(...)`，将 reasoning 持久化职责与 assistant 回复分离。

- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
  - 实现 `appendReasoningMessage(...)`。
  - 扩展 `buildMessage(...)` / `persistMessage(...)` / `toMessageDto(...)`，把 `REASONING` 映射到数据库与 API。
  - 保持旧 blocked / assistant / user 语义不回退。

- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatStreamEvent.java`
  - 保持结构简单，但允许 `type=reasoning` 作为正式事件语义。

- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
  - 使用 `onPartialThinking(...)` 发送 reasoning 事件。
  - 在成功完成时按顺序落库 reasoning 与 assistant。
  - 在 `blocked` / `error` 时丢弃缓冲 reasoning。

- 修改：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`
  - 覆盖 reasoning 消息的持久化与历史 DTO 映射。

- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
  - 覆盖 reasoning 事件流、成功落库顺序、失败不落 reasoning 的回归场景。

- 修改：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`
  - 覆盖控制器对 `reasoning` 事件的透传。

### 前端

- 修改：`frontend/lib/chat-sessions.ts`
  - 扩展 `ChatSessionMessage` 类型，增加 `messageType`。

- 修改：`frontend/lib/http.ts`
  - `apiStream` handler 增加 `onReasoning` 回调，解析 `reasoning` SSE 事件。

- 修改：`frontend/lib/http.test.mjs`
  - 覆盖 `reasoning` 事件解析与现有 `chunk` / `blocked` / `done` 不回归。

- 创建：`frontend/lib/chat-message-state.ts`
  - 放置纯函数：消息类型归一化、历史消息映射、发送时占位插入、reasoning/chunk/blocked/error 流式更新、turn 组合。

- 创建：`frontend/lib/chat-message-state.test.mjs`
  - 对 reasoning 占位更新、blocked/error 保留 reasoning、历史 `REASONING` 组合渲染输入、旧 `<think>` 兼容入口做纯逻辑测试。

- 修改：`frontend/app/chat/page.tsx`
  - 接入新的消息类型与状态辅助函数。
  - 实时显示 reasoning 折叠块。
  - 历史消息按 `messageType` 渲染，并保留旧 `<think>` 解析。

---

### 任务 1：后端历史层支持 `REASONING` 消息类型

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/model/ChatSessionMessage.java`
- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatSessionMessageDto.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`

- [ ] **步骤 1：编写失败的测试**

在 `backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java` 中新增 import：

```java
import java.util.List;
```

在现有测试类中新增两个测试方法：

```java
@Test
void shouldPersistReasoningMessageWithReasoningTypeAndAssistantRole() throws Exception {
    ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
    ChatSessionMessageMapper chatSessionMessageMapper = mock(ChatSessionMessageMapper.class);
    ChatMemorySnapshotService chatMemorySnapshotService = mock(ChatMemorySnapshotService.class);
    SystemPromptService systemPromptService = mock(SystemPromptService.class);
    ObjectMapper objectMapper = mock(ObjectMapper.class);
    ChatSessionServiceImpl service = new ChatSessionServiceImpl(
            chatSessionMapper,
            chatSessionMessageMapper,
            chatMemorySnapshotService,
            systemPromptService,
            objectMapper
    );

    ChatSessionEntity session = new ChatSessionEntity();
    session.setId(11L);
    session.setUserId(1L);
    session.setSessionId("session-1");
    session.setPromptId(22L);
    session.setTitle("新会话");
    session.setStatus("ACTIVE");
    session.setMessageCount(1);
    session.setCreatedAt(LocalDateTime.now());
    session.setUpdatedAt(LocalDateTime.now());

    when(chatSessionMapper.selectBySessionId("session-1")).thenReturn(session);
    when(objectMapper.writeValueAsString(any())).thenReturn("{\"messageType\":\"REASONING\"}");
    doAnswer(invocation -> {
        ChatSessionMessageEntity row = invocation.getArgument(0);
        row.setId(404L);
        return 1;
    }).when(chatSessionMessageMapper).insert(any(ChatSessionMessageEntity.class));

    Long reasoningMessageId = service.appendReasoningMessage(1L, "session-1", "先拆解问题，再给答案");

    assertEquals(404L, reasoningMessageId);

    ArgumentCaptor<ChatSessionMessageEntity> rowCaptor = ArgumentCaptor.forClass(ChatSessionMessageEntity.class);
    verify(chatSessionMessageMapper).insert(rowCaptor.capture());

    ChatSessionMessageEntity reasoningRow = rowCaptor.getValue();
    assertEquals(2, reasoningRow.getSequenceNo());
    assertEquals("REASONING", reasoningRow.getMessageType());
    assertEquals("assistant", reasoningRow.getRoleCode());
    assertEquals("先拆解问题，再给答案", reasoningRow.getContentText());
    assertEquals("{\"messageType\":\"REASONING\"}", reasoningRow.getPayloadJson());
    assertNotNull(reasoningRow.getCreatedAt());

    assertEquals(2, session.getMessageCount());
    verify(chatSessionMapper).updateById(session);
}

@Test
void shouldExposeReasoningMessageTypeWhenReadingHistory() throws Exception {
    ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
    ChatSessionMessageMapper chatSessionMessageMapper = mock(ChatSessionMessageMapper.class);
    ChatMemorySnapshotService chatMemorySnapshotService = mock(ChatMemorySnapshotService.class);
    SystemPromptService systemPromptService = mock(SystemPromptService.class);
    ObjectMapper objectMapper = new ObjectMapper();
    ChatSessionServiceImpl service = new ChatSessionServiceImpl(
            chatSessionMapper,
            chatSessionMessageMapper,
            chatMemorySnapshotService,
            systemPromptService,
            objectMapper
    );

    ChatSessionEntity session = new ChatSessionEntity();
    session.setId(11L);
    session.setUserId(1L);
    session.setSessionId("session-1");
    session.setPromptId(22L);
    session.setStatus("ACTIVE");
    session.setMessageCount(2);

    ChatSessionMessageEntity reasoningRow = new ChatSessionMessageEntity();
    reasoningRow.setId(501L);
    reasoningRow.setSessionRecordId(11L);
    reasoningRow.setSessionId("session-1");
    reasoningRow.setUserId(1L);
    reasoningRow.setSequenceNo(1);
    reasoningRow.setMessageType("REASONING");
    reasoningRow.setRoleCode("assistant");
    reasoningRow.setContentText("先列约束");
    reasoningRow.setPayloadJson("{\"messageType\":\"REASONING\"}");
    reasoningRow.setCreatedAt(LocalDateTime.now());

    ChatSessionMessageEntity answerRow = new ChatSessionMessageEntity();
    answerRow.setId(502L);
    answerRow.setSessionRecordId(11L);
    answerRow.setSessionId("session-1");
    answerRow.setUserId(1L);
    answerRow.setSequenceNo(2);
    answerRow.setMessageType("AI");
    answerRow.setRoleCode("assistant");
    answerRow.setContentText("最终答案");
    answerRow.setPayloadJson("{\"messageType\":\"ASSISTANT\"}");
    answerRow.setCreatedAt(LocalDateTime.now());

    when(chatSessionMapper.selectList(any())).thenReturn(List.of());
    when(chatSessionMapper.selectBySessionId("session-1")).thenReturn(session);
    when(chatSessionMessageMapper.selectPageBySessionRecordId(11L, 20, null))
            .thenReturn(List.of(answerRow, reasoningRow));

    ChatSessionMessagesPageDto page = service.getSessionMessages(1L, "session-1", 20, null);

    assertEquals("REASONING", page.messages().get(0).messageType());
    assertEquals("assistant", page.messages().get(0).role());
    assertEquals("AI", page.messages().get(1).messageType());
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatSessionServiceImplTest test
```

预期：编译失败，错误类似：

```text
cannot find symbol: method appendReasoningMessage(java.lang.Long,java.lang.String,java.lang.String)
cannot find symbol: method messageType()
```

- [ ] **步骤 3：编写最少实现代码**

在 `backend/src/main/java/com/h/backend/chat/model/ChatSessionMessage.java` 中新增字段与访问器：

```java
private String messageType;

public String getMessageType() {
    return messageType;
}

public void setMessageType(String messageType) {
    this.messageType = messageType;
}
```

将 `backend/src/main/java/com/h/backend/chat/dto/ChatSessionMessageDto.java` 改为：

```java
public record ChatSessionMessageDto(
        String id,
        String role,
        String messageType,
        String content,
        LocalDateTime createdAt
) {
}
```

在 `backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java` 中加入：

```java
Long appendReasoningMessage(Long userId, String sessionId, String reasoningMessage);
```

在 `backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java` 中：

1. 新增 reasoning 持久化方法：

```java
@Override
@Transactional
public Long appendReasoningMessage(Long userId, String sessionId, String reasoningMessage) {
    ChatSessionEntity session = requireOwnedSession(userId, sessionId);
    if (!STATUS_ACTIVE.equals(session.getStatus())) {
        throw new BusinessException(40005, "会话已失效，请重新进入聊天页");
    }

    int nextSequence = session.getMessageCount() == null ? 1 : session.getMessageCount() + 1;
    LocalDateTime now = LocalDateTime.now();
    ChatSessionMessage message = buildMessage("assistant", "REASONING", reasoningMessage, now, nextSequence);
    Long messageId = persistMessage(session, message);

    session.setMessageCount(nextSequence);
    session.setLastActiveAt(now);
    session.setUpdatedAt(now);
    chatSessionMapper.updateById(session);
    return messageId;
}
```

2. 将 `buildMessage(...)` 改为：

```java
private ChatSessionMessage buildMessage(
        String role,
        String messageType,
        String content,
        LocalDateTime createdAt,
        int sequenceNo
) {
    ChatSessionMessage message = new ChatSessionMessage();
    message.setId(UUID.randomUUID().toString());
    message.setSequenceNo(sequenceNo);
    message.setRole(role);
    message.setMessageType(messageType);
    message.setContent(content);
    message.setCreatedAt(createdAt);
    return message;
}
```

3. 将三个写消息调用点分别改成：

```java
ChatSessionMessage message = buildMessage("user", "USER", userMessage, now, nextSequence);
ChatSessionMessage message = buildMessage("blocked", "SYSTEM", blockedMessage, now, nextSequence);
ChatSessionMessage message = buildMessage("assistant", "AI", assistantMessage, now, nextSequence);
```

4. 将 `persistMessage(...)` 中的赋值改为：

```java
row.setMessageType(message.getMessageType());
```

5. 将 `toMessageDto(...)` 改为：

```java
private ChatSessionMessageDto toMessageDto(ChatSessionMessageEntity row) {
    String normalizedMessageType = normalizeMessageType(row.getMessageType(), row.getRoleCode());
    return new ChatSessionMessageDto(
            row.getId() == null ? UUID.randomUUID().toString() : String.valueOf(row.getId()),
            normalizeRole(row.getRoleCode()),
            normalizedMessageType,
            row.getContentText() == null ? "" : row.getContentText(),
            row.getCreatedAt()
    );
}
```

6. 在同文件中新增：

```java
private String normalizeMessageType(String messageType, String roleCode) {
    if (messageType != null && !messageType.isBlank()) {
        return messageType;
    }
    return switch (roleCode) {
        case "assistant", "tool", "custom", "system" -> "AI";
        case "blocked" -> "SYSTEM";
        default -> "USER";
    };
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatSessionServiceImplTest test
```

预期：`ChatSessionServiceImplTest` 全部通过。

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/model/ChatSessionMessage.java backend/src/main/java/com/h/backend/chat/dto/ChatSessionMessageDto.java backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java
git commit -m "feat: persist reasoning chat messages"
```

### 任务 2：后端聊天流透传 `reasoning` 事件并按顺序落库

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`

- [ ] **步骤 1：编写失败的测试**

在 `backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java` 中新增测试：

```java
@Test
void shouldEmitReasoningEventsAndPersistReasoningBeforeAssistantReply() {
    HAssistant hAssistant = mock(HAssistant.class);
    SystemPromptService systemPromptService = mock(SystemPromptService.class);
    ChatSessionService chatSessionService = mock(ChatSessionService.class);
    AgentRunService agentRunService = mock(AgentRunService.class);
    AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
    FakeTokenStream tokenStream = new FakeTokenStream()
            .emitThinking("先明确目标。")
            .emitThinking("再列实现步骤。")
            .emitText("最终")
            .emitText("答案");
    ChatServiceImpl chatService = new ChatServiceImpl(
            hAssistant,
            systemPromptService,
            chatSessionService,
            agentRunService,
            agentRunTelemetryService
    );

    when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
    when(chatSessionService.appendUserMessage(1L, "session-1", "hello")).thenReturn(101L);
    when(chatSessionService.appendReasoningMessage(1L, "session-1", "先明确目标。再列实现步骤。")).thenReturn(201L);
    when(chatSessionService.appendAssistantMessage(1L, "session-1", "最终答案")).thenReturn(202L);
    AgentRunTelemetryService.TelemetryRun telemetryRun =
            new AgentRunTelemetryService.TelemetryRun(null, "trace-reasoning");
    when(agentRunTelemetryService.startRun("session-1", 1L, 22L)).thenReturn(telemetryRun);
    when(agentRunService.createRun("session-1", 1L, 22L, 101L, "unknown", "trace-reasoning"))
            .thenReturn(new AgentRunService.AgentRunHandle(55L));
    when(hAssistant.streamChat("1:22:session-1", "hello")).thenReturn(tokenStream);

    List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-1", "hello")
            .collectList()
            .block();

    assertEquals(List.of(
            new ChatStreamEvent("reasoning", "先明确目标。"),
            new ChatStreamEvent("reasoning", "再列实现步骤。"),
            new ChatStreamEvent("chunk", "最终"),
            new ChatStreamEvent("chunk", "答案"),
            new ChatStreamEvent("done", "")
    ), events);
    InOrder inOrder = inOrder(chatSessionService, agentRunService, agentRunTelemetryService);
    inOrder.verify(chatSessionService).appendUserMessage(1L, "session-1", "hello");
    inOrder.verify(chatSessionService).appendReasoningMessage(1L, "session-1", "先明确目标。再列实现步骤。");
    inOrder.verify(chatSessionService).appendAssistantMessage(1L, "session-1", "最终答案");
    verify(agentRunService).completeRun(55L, 202L);
    verify(agentRunTelemetryService).markSuccess(telemetryRun);
}

@Test
void shouldNotPersistReasoningWhenRuntimeErrorOccursAfterThinking() {
    HAssistant hAssistant = mock(HAssistant.class);
    SystemPromptService systemPromptService = mock(SystemPromptService.class);
    ChatSessionService chatSessionService = mock(ChatSessionService.class);
    AgentRunService agentRunService = mock(AgentRunService.class);
    AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
    RuntimeException runtimeException = new RuntimeException("boom");
    FakeTokenStream tokenStream = new FakeTokenStream()
            .emitThinking("先分析")
            .emitError(runtimeException);
    ChatServiceImpl chatService = new ChatServiceImpl(
            hAssistant,
            systemPromptService,
            chatSessionService,
            agentRunService,
            agentRunTelemetryService
    );

    when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
    when(chatSessionService.appendUserMessage(1L, "session-2", "hello")).thenReturn(111L);
    AgentRunTelemetryService.TelemetryRun telemetryRun =
            new AgentRunTelemetryService.TelemetryRun(null, "trace-error");
    when(agentRunTelemetryService.startRun("session-2", 1L, 22L)).thenReturn(telemetryRun);
    when(agentRunService.createRun("session-2", 1L, 22L, 111L, "unknown", "trace-error"))
            .thenReturn(new AgentRunService.AgentRunHandle(66L));
    when(hAssistant.streamChat("1:22:session-2", "hello")).thenReturn(tokenStream);

    List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-2", "hello")
            .collectList()
            .block();

    assertEquals(List.of(
            new ChatStreamEvent("reasoning", "先分析"),
            new ChatStreamEvent("error", "AI 服务调用失败")
    ), events);
    verify(chatSessionService, never()).appendReasoningMessage(any(), any(), any());
    verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
}
```

在同文件的 import 区增加：

```java
import org.mockito.InOrder;
```

在 `backend/src/test/java/com/h/backend/chat/ChatControllerTest.java` 中新增测试：

```java
@Test
void shouldExposeReasoningEventFromChatService() {
    ChatService chatService = mock(ChatService.class);
    ChatController controller = new ChatController(chatService);
    AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
    ChatMessageRequest request = new ChatMessageRequest("hello", "session-1", 2L);

    when(chatService.streamChat(1L, 2L, "session-1", "hello"))
            .thenReturn(Flux.just(new ChatStreamEvent("reasoning", "先看约束")));

    List<ServerSentEvent<ChatStreamEvent>> events = controller.streamMessage(principal, request)
            .collectList()
            .block(Duration.ofSeconds(1));

    assertNotNull(events);
    assertEquals("reasoning", events.getFirst().event());
    assertEquals("先看约束", events.getFirst().data().content());
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatServiceImplTest,ChatControllerTest test
```

预期：测试失败，现有实现不会发出 `reasoning` 事件，也不会调用 `appendReasoningMessage(...)`。

- [ ] **步骤 3：编写最少实现代码**

在 `backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java` 中：

1. 在 `Flux.create(...)` 内新增缓冲区：

```java
StringBuilder reasoningBuilder = new StringBuilder();
StringBuilder replyBuilder = new StringBuilder();
```

2. 在 `hAssistant.streamChat(memoryId, userMessage)` 链上新增：

```java
.onPartialThinking(thinking -> {
    reasoningBuilder.append(thinking);
    sink.next(new ChatStreamEvent("reasoning", thinking));
})
```

3. 将 `onCompleteResponse(...)` 中的成功分支改为：

```java
.onCompleteResponse(ignored -> {
    String reply = replyBuilder.toString();
    if (reply.isBlank()) {
        IllegalStateException error = new IllegalStateException("AI 未返回有效内容");
        agentRunService.failRun(runHandle.id(), error.getMessage());
        agentRunTelemetryService.markFailure(telemetryRun, error);
        sink.next(new ChatStreamEvent("error", "AI 未返回有效内容"));
        sink.complete();
        return;
    }
    String reasoning = reasoningBuilder.toString();
    if (!reasoning.isBlank()) {
        chatSessionService.appendReasoningMessage(userId, sessionId, reasoning);
    }
    Long assistantMessageId = chatSessionService.appendAssistantMessage(
            userId,
            sessionId,
            reply
    );
    agentRunService.completeRun(runHandle.id(), assistantMessageId);
    agentRunTelemetryService.markSuccess(telemetryRun);
    sink.next(new ChatStreamEvent("done", ""));
    sink.complete();
})
```

4. 保持 `emitFailureEvent(...)` 不变，只确认其中没有新增 reasoning 落库代码。

在 `backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java` 的 `FakeTokenStream` 中新增字段和方法：

```java
private final List<String> thinkings = new ArrayList<>();
private Consumer<String> partialThinkingHandler;

FakeTokenStream emitThinking(String thinking) {
    this.thinkings.add(thinking);
    return this;
}
```

实现新的回调接口：

```java
@Override
public TokenStream onPartialThinking(Consumer<String> partialThinkingHandler) {
    this.partialThinkingHandler = partialThinkingHandler;
    return this;
}
```

并在 `start()` 中于文本输出前插入：

```java
for (String thinking : thinkings) {
    if (partialThinkingHandler != null) {
        partialThinkingHandler.accept(thinking);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatServiceImplTest,ChatControllerTest test
```

预期：`ChatServiceImplTest` 与 `ChatControllerTest` 全部通过。

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java backend/src/test/java/com/h/backend/chat/ChatControllerTest.java
git commit -m "feat: stream reasoning chat events"
```

### 任务 3：前端传输层支持 `messageType` 与 `reasoning` 事件

**文件：**
- 修改：`frontend/lib/chat-sessions.ts`
- 修改：`frontend/lib/http.ts`
- 修改：`frontend/lib/http.test.mjs`

- [ ] **步骤 1：编写失败的测试**

在 `frontend/lib/http.test.mjs` 中新增测试：

```javascript
test("apiStream dispatches reasoning events without affecting chunk flow", async () => {
  const originalFetch = globalThis.fetch;
  const events = [];

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              "event: reasoning\n" +
                'data: {"type":"reasoning","content":"先拆约束"}\n\n' +
                "event: chunk\n" +
                'data: {"type":"chunk","content":"最终答案"}\n\n' +
                "event: done\n" +
                'data: {"type":"done","content":""}\n\n',
            ),
          );
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );

  try {
    await apiStream("/api/chat/messages/stream", { method: "POST" }, {
      onReasoning(value) {
        events.push(["reasoning", value]);
      },
      onChunk(value) {
        events.push(["chunk", value]);
      },
      onDone() {
        events.push(["done", ""]);
      },
    });
    assert.deepEqual(events, [
      ["reasoning", "先拆约束"],
      ["chunk", "最终答案"],
      ["done", ""],
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
```

在 `frontend/lib/chat-sessions.ts` 中，先让类型断言失败，增加一个只读编译约束常量：

```typescript
type ChatSessionMessageType = "USER" | "AI" | "SYSTEM" | "REASONING";

const _chatSessionMessageTypeCheck: ChatSessionMessageType = "REASONING";
void _chatSessionMessageTypeCheck;
```

然后把 `ChatSessionMessage` 暂时声明成尚未包含 `messageType` 的现状，等待步骤 3 修复。

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd frontend && npm test
```

预期：`apiStream dispatches reasoning events without affecting chunk flow` 失败，错误类似：

```text
TypeError: handlers.onReasoning is not a function
```

- [ ] **步骤 3：编写最少实现代码**

将 `frontend/lib/chat-sessions.ts` 改为：

```typescript
export type ChatSessionMessageType = "USER" | "AI" | "SYSTEM" | "REASONING";

export type ChatSessionMessage = {
  id: string;
  role: "assistant" | "blocked" | "user";
  messageType: ChatSessionMessageType;
  content: string;
  createdAt: string;
};
```

将 `frontend/lib/http.ts` 的 handler 类型扩展为：

```typescript
handlers: {
  onReasoning?: (value: string) => void;
  onChunk: (value: string) => void;
  onDone?: (value: string) => void;
  onBlocked?: (message: string) => void;
  onError?: (message: string) => void;
},
```

并在 `flushBlocks(...)` 中加入分支：

```typescript
if (eventType === "reasoning") {
  handlers.onReasoning?.(payload.content);
} else if (eventType === "chunk") {
  handlers.onChunk(payload.content);
} else if (eventType === "done") {
  handlers.onDone?.(payload.content);
} else if (eventType === "blocked") {
  handlers.onBlocked?.(payload.content);
} else if (eventType === "error") {
  handlers.onError?.(payload.content);
  throw new Error(payload.content || "请求失败");
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
source ~/.profile && cd frontend && npm test
```

预期：`frontend/lib/http.test.mjs` 全部通过。

- [ ] **步骤 5：Commit**

```bash
git add frontend/lib/chat-sessions.ts frontend/lib/http.ts frontend/lib/http.test.mjs
git commit -m "feat: parse reasoning stream events"
```

### 任务 4：提取前端消息状态纯逻辑并覆盖 reasoning 流式更新

**文件：**
- 创建：`frontend/lib/chat-message-state.ts`
- 创建：`frontend/lib/chat-message-state.test.mjs`

- [ ] **步骤 1：编写失败的测试**

创建 `frontend/lib/chat-message-state.test.mjs`：

```javascript
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  applyAssistantChunk,
  applyBlockedState,
  applyReasoningChunk,
  buildPendingAssistantTurn,
  toRenderableTurns,
} from "./chat-message-state.ts";

test("buildPendingAssistantTurn creates user, reasoning, and assistant placeholders", () => {
  const { userMessage, reasoningMessage, assistantMessage } = buildPendingAssistantTurn("你好", 100);

  assert.equal(userMessage.role, "user");
  assert.equal(reasoningMessage.messageType, "REASONING");
  assert.equal(reasoningMessage.content, "");
  assert.equal(assistantMessage.messageType, "AI");
  assert.equal(assistantMessage.content, "");
});

test("applyReasoningChunk appends only reasoning content", () => {
  const { reasoningMessage, assistantMessage } = buildPendingAssistantTurn("你好", 100);
  const next = applyReasoningChunk([reasoningMessage, assistantMessage], reasoningMessage.id, "先分析");

  assert.equal(next[0].content, "先分析");
  assert.equal(next[1].content, "");
});

test("applyBlockedState keeps reasoning content and converts assistant placeholder to blocked", () => {
  const { reasoningMessage, assistantMessage } = buildPendingAssistantTurn("你好", 100);
  const withReasoning = applyReasoningChunk([reasoningMessage, assistantMessage], reasoningMessage.id, "先分析");
  const blocked = applyBlockedState(withReasoning, assistantMessage.id, "命中安全规则");

  assert.equal(blocked[0].messageType, "REASONING");
  assert.equal(blocked[0].content, "先分析");
  assert.equal(blocked[1].role, "blocked");
  assert.equal(blocked[1].messageType, "SYSTEM");
  assert.equal(blocked[1].content, "命中安全规则");
});

test("toRenderableTurns groups reasoning before assistant reply", () => {
  const turns = toRenderableTurns([
    { id: "1", role: "assistant", messageType: "REASONING", content: "先列约束", createdAt: "" },
    { id: "2", role: "assistant", messageType: "AI", content: "最终答案", createdAt: "" },
  ]);

  assert.deepEqual(turns, [
    {
      kind: "assistant",
      reasoning: "先列约束",
      answer: "最终答案",
      blocked: null,
      id: "2",
    },
  ]);
});

test("toRenderableTurns leaves legacy think-tag assistant content untouched", () => {
  const turns = toRenderableTurns([
    {
      id: "legacy-1",
      role: "assistant",
      messageType: "AI",
      content: "<think>旧思考</think>旧答案",
      createdAt: "",
    },
  ]);

  assert.equal(turns[0].kind, "assistant");
  assert.equal(turns[0].reasoning, null);
  assert.equal(turns[0].answer, "<think>旧思考</think>旧答案");
});
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd frontend && node --test lib/chat-message-state.test.mjs
```

预期：失败，错误类似：

```text
Error [ERR_MODULE_NOT_FOUND]: Cannot find module './chat-message-state.ts'
```

- [ ] **步骤 3：编写最少实现代码**

创建 `frontend/lib/chat-message-state.ts`：

```typescript
import type { ChatSessionMessage, ChatSessionMessageType } from "./chat-sessions";

export type UiChatMessage = {
  id: string;
  role: "assistant" | "blocked" | "user";
  messageType: ChatSessionMessageType;
  content: string;
  createdAt?: string;
};

export type RenderableTurn =
  | {
      kind: "user";
      id: string;
      content: string;
    }
  | {
      kind: "assistant";
      id: string;
      reasoning: string | null;
      answer: string;
      blocked: null;
    }
  | {
      kind: "blocked";
      id: string;
      reasoning: string | null;
      answer: "";
      blocked: string;
    };

export function buildPendingAssistantTurn(content: string, seed: number) {
  return {
    userMessage: {
      id: `user-${seed}`,
      role: "user",
      messageType: "USER",
      content,
    } satisfies UiChatMessage,
    reasoningMessage: {
      id: `reasoning-${seed}`,
      role: "assistant",
      messageType: "REASONING",
      content: "",
    } satisfies UiChatMessage,
    assistantMessage: {
      id: `assistant-${seed}`,
      role: "assistant",
      messageType: "AI",
      content: "",
    } satisfies UiChatMessage,
  };
}

export function applyReasoningChunk(messages: UiChatMessage[], reasoningId: string, chunk: string) {
  return messages.map((message) =>
    message.id === reasoningId ? { ...message, content: `${message.content}${chunk}` } : message,
  );
}

export function applyAssistantChunk(messages: UiChatMessage[], assistantId: string, chunk: string) {
  return messages.map((message) =>
    message.id === assistantId ? { ...message, content: `${message.content}${chunk}` } : message,
  );
}

export function applyBlockedState(messages: UiChatMessage[], assistantId: string, blockedMessage: string) {
  return messages.map((message) =>
    message.id === assistantId
      ? { ...message, role: "blocked", messageType: "SYSTEM", content: blockedMessage }
      : message,
  );
}

export function toUiChatMessage(message: ChatSessionMessage): UiChatMessage {
  return {
    id: message.id,
    role: message.role,
    messageType: message.messageType,
    content: message.content,
    createdAt: message.createdAt,
  };
}

export function toRenderableTurns(messages: UiChatMessage[]): RenderableTurn[] {
  const turns: RenderableTurn[] = [];

  for (let index = 0; index < messages.length; index += 1) {
    const current = messages[index];
    if (current.role === "user") {
      turns.push({ kind: "user", id: current.id, content: current.content });
      continue;
    }
    if (current.role === "blocked") {
      turns.push({
        kind: "blocked",
        id: current.id,
        reasoning: null,
        answer: "",
        blocked: current.content,
      });
      continue;
    }
    if (current.messageType === "REASONING") {
      const next = messages[index + 1];
      if (next && next.role === "assistant" && next.messageType === "AI") {
        turns.push({
          kind: "assistant",
          id: next.id,
          reasoning: current.content || null,
          answer: next.content,
          blocked: null,
        });
        index += 1;
        continue;
      }
      turns.push({
        kind: "assistant",
        id: current.id,
        reasoning: current.content || null,
        answer: "",
        blocked: null,
      });
      continue;
    }
    turns.push({
      kind: "assistant",
      id: current.id,
      reasoning: null,
      answer: current.content,
      blocked: null,
    });
  }

  return turns;
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
source ~/.profile && cd frontend && node --test lib/chat-message-state.test.mjs
```

预期：`frontend/lib/chat-message-state.test.mjs` 全部通过。

- [ ] **步骤 5：Commit**

```bash
git add frontend/lib/chat-message-state.ts frontend/lib/chat-message-state.test.mjs
git commit -m "feat: add chat reasoning state helpers"
```

### 任务 5：聊天页接入 reasoning 状态与历史展示

**文件：**
- 修改：`frontend/app/chat/page.tsx`
- 复用：`frontend/lib/chat-message-state.ts`
- 复用：`frontend/lib/chat-sessions.ts`

- [ ] **步骤 1：编写失败的测试**

在 `frontend/lib/chat-message-state.test.mjs` 中追加两个与页面集成相关的测试：

```javascript
import { applyAssistantChunk, toUiChatMessage } from "./chat-message-state.ts";

test("applyAssistantChunk appends only assistant content", () => {
  const { reasoningMessage, assistantMessage } = buildPendingAssistantTurn("你好", 100);
  const next = applyAssistantChunk([reasoningMessage, assistantMessage], assistantMessage.id, "最终答案");

  assert.equal(next[0].content, "");
  assert.equal(next[1].content, "最终答案");
});

test("toUiChatMessage preserves reasoning message type from history payload", () => {
  const uiMessage = toUiChatMessage({
    id: "history-1",
    role: "assistant",
    messageType: "REASONING",
    content: "先看上下文",
    createdAt: "",
  });

  assert.equal(uiMessage.messageType, "REASONING");
  assert.equal(uiMessage.role, "assistant");
});
```

这一步会在页面接线前保证新 helper 足够支撑 `page.tsx` 改造。

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd frontend && npm test
```

预期：如果步骤 3 尚未完成页面接线，测试本身会通过，但聊天页仍未实现 reasoning 展示。此时继续执行步骤 3；不要在这里提前 commit。

- [ ] **步骤 3：编写最少实现代码**

将 `frontend/app/chat/page.tsx` 顶部 import 区补充为：

```typescript
import {
  applyAssistantChunk,
  applyBlockedState,
  applyReasoningChunk,
  buildPendingAssistantTurn,
  toRenderableTurns,
  toUiChatMessage,
  type UiChatMessage,
} from "@/lib/chat-message-state";
```

将本地 `ChatMessage` 类型删除，状态改为：

```typescript
const [messages, setMessages] = useState<UiChatMessage[]>([]);
```

将 `hydrateSession(...)` 中的映射改为：

```typescript
setMessages(messagePage.messages.map(toUiChatMessage));
```

将 `handleLoadOlderMessages()` 中的映射改为：

```typescript
const olderMessages = detail.messages.map(toUiChatMessage);
```

将 `handleSubmit(...)` 的占位初始化改为：

```typescript
const seed = Date.now();
const { userMessage, reasoningMessage, assistantMessage } = buildPendingAssistantTurn(content, seed);

setMessages((current) => [...current, userMessage, reasoningMessage, assistantMessage]);
```

将 `apiStream(...)` handlers 改为：

```typescript
{
  onReasoning(chunk) {
    setMessages((current) => applyReasoningChunk(current, reasoningMessage.id, chunk));
  },
  onChunk(chunk) {
    setMessages((current) => applyAssistantChunk(current, assistantMessage.id, chunk));
  },
  onBlocked(message) {
    setMessages((current) => applyBlockedState(current, assistantMessage.id, message));
  },
  onDone() {
    setCurrentSessionTitle((current) => (current === "新会话" ? content.slice(0, 20) || current : current));
  },
  onError(message) {
    setError(message);
  },
}
```

将 `catch` 中的 assistant 兜底替换改为：

```typescript
setMessages((current) =>
  current.map((item) =>
    item.id === assistantMessage.id && !item.content
      ? { ...item, content: "暂时无法响应，请稍后重试。" }
      : item,
  ),
);
```

在渲染区将 `messages.map(...)` 改为：

```typescript
{toRenderableTurns(messages).map((turn) => (
  <article
    key={turn.id}
    className={`flex ${turn.kind === "user" ? "justify-end" : "justify-start"}`}
  >
    <div
      className={[
        "max-w-[85%] rounded-[1.5rem] px-4 py-3 text-sm leading-6 shadow-sm",
        turn.kind === "user"
          ? "rounded-br-md bg-stone-900 text-stone-50"
          : turn.kind === "blocked"
            ? "rounded-bl-md border border-amber-200 bg-amber-50/95 text-amber-900"
            : "rounded-bl-md border border-stone-200 bg-white/95 text-stone-700",
      ].join(" ")}
    >
      {turn.kind === "user" ? (
        turn.content
      ) : turn.kind === "blocked" ? (
        <div className="space-y-3">
          {turn.reasoning ? (
            <details className="rounded-2xl border border-stone-200 bg-stone-50/90 px-3 py-2 text-stone-600">
              <summary className="cursor-pointer list-none text-xs font-medium tracking-[0.18em] text-stone-500">
                思考过程
              </summary>
              <div className="mt-2 whitespace-pre-wrap text-xs leading-6 text-stone-500">{turn.reasoning}</div>
            </details>
          ) : null}
          <BlockedMessageContent content={turn.blocked} />
        </div>
      ) : turn.answer ? (
        <div className="space-y-3">
          {turn.reasoning ? (
            <details className="rounded-2xl border border-stone-200 bg-stone-50/90 px-3 py-2 text-stone-600" open={!turn.answer}>
              <summary className="cursor-pointer list-none text-xs font-medium tracking-[0.18em] text-stone-500">
                思考过程
              </summary>
              <div className="mt-2 whitespace-pre-wrap text-xs leading-6 text-stone-500">{turn.reasoning}</div>
            </details>
          ) : null}
          <AssistantMessageContent content={turn.answer} />
        </div>
      ) : turn.reasoning ? (
        <details className="rounded-2xl border border-stone-200 bg-stone-50/90 px-3 py-2 text-stone-600" open>
          <summary className="cursor-pointer list-none text-xs font-medium tracking-[0.18em] text-stone-500">
            思考中...
          </summary>
          <div className="mt-2 whitespace-pre-wrap text-xs leading-6 text-stone-500">{turn.reasoning}</div>
        </details>
      ) : streaming ? (
        "正在思考..."
      ) : (
        ""
      )}
    </div>
  </article>
))}
```

并保留 `AssistantMessageContent` 里的 `<think>` 解析逻辑不动，作为旧数据兼容。

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
source ~/.profile && cd frontend && npm test
```

预期：`frontend/lib/http.test.mjs` 与 `frontend/lib/chat-message-state.test.mjs` 全部通过。

- [ ] **步骤 5：Commit**

```bash
git add frontend/app/chat/page.tsx frontend/lib/chat-message-state.ts frontend/lib/chat-message-state.test.mjs
git commit -m "feat: render chat reasoning turns"
```

### 任务 6：全链路回归验证与收尾

**文件：**
- 验证：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
- 验证：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`
- 验证：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`
- 验证：`frontend/lib/http.test.mjs`
- 验证：`frontend/lib/chat-message-state.test.mjs`

- [ ] **步骤 1：运行后端聚合测试**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatServiceImplTest,ChatSessionServiceImplTest,ChatControllerTest test
```

预期：三组测试全部通过，无新增编译错误。

- [ ] **步骤 2：运行前端聚合测试**

运行：

```bash
source ~/.profile && cd frontend && npm test
```

预期：`frontend/lib/http.test.mjs` 与 `frontend/lib/chat-message-state.test.mjs` 全部通过。

- [ ] **步骤 3：查看最终 diff**

运行：

```bash
git diff --stat HEAD~4..HEAD
```

预期：能看到后端 reasoning 持久化、SSE 事件扩展、前端消息状态 helper 与聊天页渲染接线的完整变更统计。

- [ ] **步骤 4：最终 Commit（如前面任务已逐步 commit，此步只在需要补收尾时执行）**

```bash
git status --short
git commit -m "chore: finalize anthropic thinking compatibility" --allow-empty
```

预期：如果前面任务已经全部 commit，这里生成一个空收尾提交；如果不需要额外提交，可跳过该命令并在执行记录中注明“前序任务已完整提交，无需额外 commit”。

## 自检

### 规格覆盖度

- 双轨兼容：任务 5 保留 `AssistantMessageContent` 的 `<think>` 解析；任务 1/2/3/4/5 建立新 `REASONING` 链路。
- 独立消息类型：任务 1 扩展 `messageType` 到数据库快照、DTO 和历史接口。
- `reasoning` SSE：任务 2 和任务 3 覆盖后端透传与前端解析。
- 成功后落库：任务 2 验证成功路径先 reasoning 后 assistant。
- `blocked` / `error` 不落 reasoning：任务 2 和任务 4 覆盖失败场景。
- 前端历史展示与流式占位：任务 4/5 覆盖。

### 占位符扫描

- 计划中未使用 `TODO`、`待定`、`后续实现`、`补充细节` 等占位措辞。
- 每个涉及代码变更的步骤都给出了实际代码片段。
- 每个测试步骤都提供了明确命令与预期结果。

### 类型一致性

- 后端统一使用 `messageType` 作为 API/快照字段名，数据库列仍为 `message_type`。
- 前端统一使用 `"USER" | "AI" | "SYSTEM" | "REASONING"` 作为消息类型。
- 流式事件统一使用字符串字面量 `reasoning`，不与数据库 `REASONING` 常量混用。
