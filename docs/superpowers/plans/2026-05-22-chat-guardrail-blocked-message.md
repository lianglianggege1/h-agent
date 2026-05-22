# Chat Guardrail Blocked Message 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 InputGuardrail 审核不通过从普通错误链路中分离出来，作为 `blocked` 消息在聊天流、当前 UI 和历史记录中展示。

**架构：** 后端新增明确的 blocked 业务语义：`ChatServiceImpl` 识别 guardrail 异常并抛出 `BusinessException(40301, message)`，`ChatController` 将该业务码写成 NDJSON `blocked` 事件。会话历史层新增 `appendBlockedMessage(...)` 和 `blocked` 角色回显；前端流解析新增 `onBlocked`，聊天页新增 `blocked` 气泡渲染，并保留普通 `error` 的现有兜底行为。

**技术栈：** Java 23, Spring Boot 3.4.0, langchain4j 1.11.7, JUnit 5, Mockito, Next.js 16, React 19, TypeScript, NDJSON stream。

---

## 文件结构

### 后端

- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java`
  - 新增 `appendBlockedMessage(Long userId, String sessionId, String blockedMessage)`，让会话历史层可以显式保存平台拦截消息。

- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
  - 新增 `appendBlockedMessage(...)` 实现。
  - 调整 `persistMessage(...)`，让 `blocked` 消息存成 `message_type = SYSTEM`、`role_code = blocked`。
  - 调整 `normalizeRole(...)`，读取历史时返回 `blocked`。

- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
  - 在 `errorRef` 分支中识别 `InputGuardrailException`。
  - guardrail 失败时保存 blocked 消息，结束 run，并抛出 `BusinessException(40301, cleanMessage)`。
  - 普通模型错误继续保持现有 `50003` 行为。

- 修改：`backend/src/main/java/com/h/backend/chat/controller/ChatController.java`
  - `catch` 中识别 `BusinessException` code `40301`。
  - 输出 `ChatStreamEvent("blocked", message)`，而不是 `error`。

- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
  - 增加 guardrail 被转成 blocked 的单元测试。

- 修改：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`
  - 增加 blocked 消息持久化测试。

### 前端

- 修改：`frontend/lib/http.ts`
  - `apiStream` handler 增加 `onBlocked?: (message: string) => void`。
  - 解析 `event.type === "blocked"`，调用 `onBlocked` 后不抛错。

- 修改：`frontend/app/chat/page.tsx`
  - `ChatRole` 增加 `blocked`。
  - 新增 `BlockedMessageContent` 组件。
  - `handleSubmit` 的 `onBlocked` 回调将临时 assistant 占位消息替换为 blocked 消息。
  - 历史消息 hydration 直接支持后端返回的 `blocked` role。

---

### 任务 1：后端历史层支持 blocked 消息

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java:31-34`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java:238-401`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`

- [ ] **步骤 1：编写失败的测试**

在 `backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java` 中新增测试方法：

```java
@Test
void shouldPersistBlockedMessageWithBlockedRoleAndSystemType() throws Exception {
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
    when(objectMapper.writeValueAsString(any())).thenReturn("{\"role\":\"blocked\"}");
    doAnswer(invocation -> {
        ChatSessionMessageEntity row = invocation.getArgument(0);
        row.setId(303L);
        return 1;
    }).when(chatSessionMessageMapper).insert(any(ChatSessionMessageEntity.class));

    Long blockedMessageId = service.appendBlockedMessage(1L, "session-1", "系统提醒您：请勿使用暴力");

    assertEquals(303L, blockedMessageId);

    ArgumentCaptor<ChatSessionMessageEntity> rowCaptor = ArgumentCaptor.forClass(ChatSessionMessageEntity.class);
    verify(chatSessionMessageMapper).insert(rowCaptor.capture());

    ChatSessionMessageEntity blockedRow = rowCaptor.getValue();
    assertEquals(11L, blockedRow.getSessionRecordId());
    assertEquals("session-1", blockedRow.getSessionId());
    assertEquals(1L, blockedRow.getUserId());
    assertEquals(2, blockedRow.getSequenceNo());
    assertEquals("SYSTEM", blockedRow.getMessageType());
    assertEquals("blocked", blockedRow.getRoleCode());
    assertEquals("系统提醒您：请勿使用暴力", blockedRow.getContentText());
    assertEquals("{\"role\":\"blocked\"}", blockedRow.getPayloadJson());
    assertNotNull(blockedRow.getCreatedAt());

    assertEquals(2, session.getMessageCount());
    assertEquals("新会话", session.getTitle());
    verify(chatSessionMapper).updateById(session);
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatSessionServiceImplTest test
```

预期：编译失败，错误类似：

```text
cannot find symbol: method appendBlockedMessage(java.lang.Long,java.lang.String,java.lang.String)
```

- [ ] **步骤 3：编写最少实现代码**

在 `backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java` 中加入：

```java
Long appendBlockedMessage(Long userId, String sessionId, String blockedMessage);
```

在 `backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java` 中，在 `appendAssistantMessage(...)` 后加入：

```java
@Override
@Transactional
public Long appendBlockedMessage(Long userId, String sessionId, String blockedMessage) {
    ChatSessionEntity session = requireOwnedSession(userId, sessionId);
    if (!STATUS_ACTIVE.equals(session.getStatus())) {
        throw new BusinessException(40005, "会话已失效，请重新进入聊天页");
    }

    int nextSequence = session.getMessageCount() == null ? 1 : session.getMessageCount() + 1;
    LocalDateTime now = LocalDateTime.now();
    ChatSessionMessage message = buildMessage("blocked", blockedMessage, now, nextSequence);
    Long messageId = persistMessage(session, message);

    session.setMessageCount(nextSequence);
    session.setLastActiveAt(now);
    session.setUpdatedAt(now);
    chatSessionMapper.updateById(session);
    return messageId;
}
```

将 `persistMessage(...)` 中的 message type 赋值改为：

```java
row.setMessageType(switch (message.getRole()) {
    case "assistant" -> "AI";
    case "blocked" -> "SYSTEM";
    default -> "USER";
});
```

将 `normalizeRole(...)` 改为：

```java
private String normalizeRole(String roleCode) {
    return switch (roleCode) {
        case "assistant", "tool", "custom", "system" -> "assistant";
        case "blocked" -> "blocked";
        default -> "user";
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
git add backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java
git commit -m "feat: persist blocked chat messages"
```

### 任务 2：后端聊天流输出 blocked 事件

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java:65-109`
- 修改：`backend/src/main/java/com/h/backend/chat/controller/ChatController.java:40-51`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`

- [ ] **步骤 1：编写失败的测试**

在 `backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java` 中增加 import：

```java
import dev.langchain4j.guardrail.InputGuardrailException;
```

新增测试方法：

```java
@Test
void shouldConvertInputGuardrailFailureToBlockedBusinessException() {
    HAssistant hAssistant = mock(HAssistant.class);
    SystemPromptService systemPromptService = mock(SystemPromptService.class);
    ChatSessionService chatSessionService = mock(ChatSessionService.class);
    AgentRunService agentRunService = mock(AgentRunService.class);
    AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
    InputGuardrailException guardrailException = new InputGuardrailException("系统提醒您：请勿使用暴力");
    FakeTokenStream tokenStream = new FakeTokenStream().emitError(guardrailException);
    ChatServiceImpl chatService = new ChatServiceImpl(
            hAssistant,
            systemPromptService,
            chatSessionService,
            agentRunService,
            agentRunTelemetryService
    );

    when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
    when(chatSessionService.appendUserMessage(1L, "session-1", "杀人")).thenReturn(101L);
    when(chatSessionService.appendBlockedMessage(1L, "session-1", "系统提醒您：请勿使用暴力")).thenReturn(303L);
    AgentRunTelemetryService.TelemetryRun telemetryRun =
            new AgentRunTelemetryService.TelemetryRun(null, "trace-4");
    when(agentRunTelemetryService.startRun("session-1", 1L, 22L)).thenReturn(telemetryRun);
    when(agentRunService.createRun("session-1", 1L, 22L, 101L, "unknown", "trace-4"))
            .thenReturn(new AgentRunService.AgentRunHandle(77L));
    when(hAssistant.streamChat("1:22:session-1", "杀人")).thenReturn(tokenStream);

    BusinessException ex = assertThrows(BusinessException.class,
            () -> chatService.streamChat(1L, 2L, "session-1", "杀人", chunk -> {}));

    assertEquals(40301, ex.getCode());
    assertEquals("系统提醒您：请勿使用暴力", ex.getMessage());
    verify(chatSessionService).appendBlockedMessage(1L, "session-1", "系统提醒您：请勿使用暴力");
    verify(agentRunService).failRun(77L, "系统提醒您：请勿使用暴力");
    verify(agentRunTelemetryService).markFailure(telemetryRun, guardrailException);
    verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
}
```

注意：如果当前测试文件里仍使用 `hAssistant.chat(...)`，需要同步改成生产代码实际方法 `hAssistant.streamChat(...)`，否则测试与接口不一致。

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatServiceImplTest test
```

预期：测试失败，当前实现会抛出 `BusinessException` code `50003`，且不会调用 `appendBlockedMessage(...)`。

- [ ] **步骤 3：编写最少实现代码**

在 `backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java` 中增加 import：

```java
import dev.langchain4j.guardrail.InputGuardrailException;
```

将 `Throwable error = errorRef.get();` 分支改为：

```java
Throwable error = errorRef.get();
if (error != null) {
    if (error instanceof ModelDisabledException) {
        agentRunService.failRun(runHandle.id(), "AI 服务未配置 OPENAI_API_KEY");
        agentRunTelemetryService.markFailure(telemetryRun, error);
        throw new BusinessException(50001, "AI 服务未配置 OPENAI_API_KEY");
    }
    if (error instanceof InputGuardrailException) {
        String blockedMessage = cleanGuardrailMessage(error.getMessage());
        Long blockedMessageId = chatSessionService.appendBlockedMessage(userId, sessionId, blockedMessage);
        agentRunService.failRun(runHandle.id(), blockedMessage);
        agentRunTelemetryService.markFailure(telemetryRun, error);
        throw new BusinessException(40301, blockedMessage);
    }
    agentRunService.failRun(runHandle.id(), error.getMessage() == null ? "AI 服务调用失败" : error.getMessage());
    agentRunTelemetryService.markFailure(telemetryRun, error);
    throw new BusinessException(50003, "AI 服务调用失败");
}
```

在 `ChatServiceImpl` 末尾 `recordToolUsage(...)` 前加入：

```java
private String cleanGuardrailMessage(String message) {
    if (message == null || message.isBlank()) {
        return "平台检测到您的消息不符合使用规范，已自动拦截。";
    }
    String marker = " failed with this message: ";
    int markerIndex = message.indexOf(marker);
    if (markerIndex >= 0) {
        return message.substring(markerIndex + marker.length()).trim();
    }
    return message.trim();
}
```

在 `backend/src/main/java/com/h/backend/chat/controller/ChatController.java` 中增加 import：

```java
import com.h.backend.common.exception.BusinessException;
```

将 catch 改为：

```java
} catch (RuntimeException ex) {
    if (ex instanceof BusinessException businessException && businessException.getCode() == 40301) {
        writeEvent(writer, new ChatStreamEvent("blocked", businessException.getMessage()));
        return;
    }
    writeEvent(writer, new ChatStreamEvent("error", ex.getMessage()));
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatServiceImplTest test
```

预期：`ChatServiceImplTest` 全部通过。

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java backend/src/main/java/com/h/backend/chat/controller/ChatController.java backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java
git commit -m "feat: stream guardrail blocks separately"
```

### 任务 3：前端流解析支持 blocked 事件

**文件：**
- 修改：`frontend/lib/http.ts:33-87`
- 测试：`frontend/lib/*.test.mjs` 中新增或修改对应测试文件

- [ ] **步骤 1：编写失败的测试**

如果已有 `frontend/lib/http.test.mjs`，在其中新增测试；如果没有，新建 `frontend/lib/http.test.mjs`，测试 `blocked` 事件不会抛错并会调用 `onBlocked`。

测试主体应覆盖以下行为：

```js
import test from "node:test";
import assert from "node:assert/strict";
import { apiStream } from "./http.js";

test("apiStream dispatches blocked event without throwing", async () => {
  const encoder = new TextEncoder();
  global.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode('{"type":"blocked","content":"系统提醒您：请勿使用暴力"}\n'));
          controller.close();
        },
      }),
      { status: 200 },
    );

  let blockedMessage = "";
  await apiStream(
    "/api/chat/messages/stream",
    { method: "POST", body: "{}" },
    {
      onChunk() {},
      onBlocked(message) {
        blockedMessage = message;
      },
    },
  );

  assert.equal(blockedMessage, "系统提醒您：请勿使用暴力");
});
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd frontend && pnpm test
```

预期：测试失败，原因是 `apiStream` handlers 类型没有 `onBlocked`，或 `blocked` 事件没有被处理。

- [ ] **步骤 3：编写最少实现代码**

在 `frontend/lib/http.ts` 的 handlers 类型中加入：

```ts
onBlocked?: (message: string) => void;
```

在事件解析分支中加入：

```ts
} else if (event.type === "blocked") {
  handlers.onBlocked?.(event.content);
```

完整分支顺序保持为：

```ts
if (event.type === "chunk") {
  handlers.onChunk(event.content);
} else if (event.type === "done") {
  handlers.onDone?.(event.content);
} else if (event.type === "blocked") {
  handlers.onBlocked?.(event.content);
} else if (event.type === "error") {
  handlers.onError?.(event.content);
  throw new Error(event.content || "请求失败");
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
source ~/.profile && cd frontend && pnpm test
```

预期：前端测试通过，`blocked` 事件不会触发 throw。

- [ ] **步骤 5：Commit**

```bash
git add frontend/lib/http.ts frontend/lib/http.test.mjs
git commit -m "feat: parse blocked chat stream events"
```

### 任务 4：前端聊天页渲染平台拦截气泡

**文件：**
- 修改：`frontend/app/chat/page.tsx:21-355`

- [ ] **步骤 1：编写失败的验证用例**

当前项目没有聊天页组件测试框架，先记录手工验证用例：

```text
输入：杀人
预期：用户消息右侧显示，随后左侧出现“平台安全拦截”卡片。
不应出现：输入框上方红色错误文本。
不应出现：assistant 气泡里的“暂时无法响应，请稍后重试。”。
```

- [ ] **步骤 2：运行类型检查验证当前不支持**

运行：

```bash
source ~/.profile && cd frontend && pnpm lint
```

预期：当前代码还没有 `blocked` role 和 `onBlocked` 调用，本步骤用于记录改动前状态；如果 lint 已有其他既存问题，记录具体输出，不在本任务中修复无关问题。

- [ ] **步骤 3：编写最少实现代码**

将 `ChatRole` 改为：

```ts
type ChatRole = "assistant" | "user" | "blocked";
```

在 `AssistantMessageContent` 后新增组件：

```tsx
function BlockedMessageContent({ content }: { content: string }) {
  return (
    <div className="space-y-2">
      <div className="text-xs font-semibold tracking-[0.18em] text-amber-700">平台安全拦截</div>
      <div className="h-px bg-amber-200" />
      <p className="whitespace-pre-wrap text-sm leading-6 text-stone-700">
        您的消息包含不适合继续对话的内容，已被平台拦截。
      </p>
      <p className="whitespace-pre-wrap text-xs leading-5 text-stone-500">原因：{content}</p>
    </div>
  );
}
```

在 `apiStream` handlers 中新增 `onBlocked`：

```ts
onBlocked(message) {
  setMessages((current) =>
    current.map((item) =>
      item.id === assistantId ? { ...item, role: "blocked", content: message } : item,
    ),
  );
  setCurrentSessionTitle((current) => (current === "新会话" ? content.slice(0, 20) || current : current));
},
```

将 `onError` 保持为只设置错误：

```ts
onError(message) {
  setError(message);
},
```

修改消息渲染分支：

```tsx
{message.role === "blocked" ? (
  <BlockedMessageContent content={message.content} />
) : message.role === "assistant" ? (
  message.content ? (
    <AssistantMessageContent content={message.content} />
  ) : streaming ? (
    "正在思考..."
  ) : (
    ""
  )
) : (
  message.content
)}
```

修改气泡 className 判断，给 blocked 独立样式：

```tsx
message.role === "user"
  ? "rounded-br-md bg-stone-900 text-stone-50"
  : message.role === "blocked"
    ? "rounded-bl-md border border-amber-200 border-l-4 border-l-amber-400 bg-amber-50/95 text-stone-700"
    : "rounded-bl-md border border-stone-200 bg-white/95 text-stone-700"
```

- [ ] **步骤 4：运行验证**

运行：

```bash
source ~/.profile && cd frontend && pnpm lint
```

预期：lint 通过，或只剩与本次改动无关的既存问题。

手工验证：启动前后端后，在聊天框输入 `杀人`。

预期：

```text
显示 blocked 平台拦截卡片。
不显示红色错误文本。
不显示“暂时无法响应，请稍后重试。”。
```

- [ ] **步骤 5：Commit**

```bash
git add frontend/app/chat/page.tsx
git commit -m "feat: render blocked chat messages"
```

### 任务 5：端到端回归验证

**文件：**
- 验证：`backend/src/main/java/com/h/backend/chat/guardrail/ViolenceInputGuardrail.java`
- 验证：`backend/src/main/java/com/h/backend/chat/controller/ChatController.java`
- 验证：`frontend/app/chat/page.tsx`

- [ ] **步骤 1：运行后端相关测试**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatServiceImplTest,ChatSessionServiceImplTest test
```

预期：所有相关后端测试通过。

- [ ] **步骤 2：运行前端测试和 lint**

运行：

```bash
source ~/.profile && cd frontend && pnpm test && pnpm lint
```

预期：前端测试通过，lint 通过，或明确记录无关既存 lint 问题。

- [ ] **步骤 3：手工验证 guardrail 拦截链路**

操作：

```text
1. 登录前端。
2. 进入 /chat。
3. 输入：杀人
4. 发送。
```

预期：

```text
聊天流中显示用户消息。
聊天流中显示左侧 blocked 平台拦截卡片。
输入框上方没有红色错误文本。
聊天流里没有“暂时无法响应，请稍后重试。”。
```

- [ ] **步骤 4：手工验证历史回显**

操作：

```text
1. 在完成步骤 3 后刷新页面。
2. 或从历史会话重新打开该会话。
```

预期：

```text
刚才的 blocked 平台拦截卡片仍然显示。
该消息不是普通 assistant 样式。
```

- [ ] **步骤 5：Commit**

如果步骤 5 只做验证且没有代码改动，不需要 commit。如果根据验证修正了代码，则执行：

```bash
git add backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java backend/src/main/java/com/h/backend/chat/controller/ChatController.java backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java frontend/lib/http.ts frontend/app/chat/page.tsx frontend/lib/http.test.mjs
git commit -m "fix: complete blocked chat message flow"
```

## 自检结果

### 规格覆盖度

- 独立 `blocked` 流式事件：任务 2、任务 3 覆盖。
- blocked 当前 UI 展示：任务 4 覆盖。
- blocked 历史保存和回显：任务 1、任务 4、任务 5 覆盖。
- 普通 error 兜底不变：任务 2、任务 3、任务 4、任务 5 覆盖。

### 占位符扫描

计划中没有未完成占位内容。手工验证步骤包含明确输入和预期输出。

### 类型一致性

- 后端业务码统一使用 `40301`。
- 后端消息角色统一使用字符串 `blocked`。
- 前端 `ChatRole` 包含 `blocked`。
- 流事件类型统一使用 `blocked`。
- 新增服务方法统一命名为 `appendBlockedMessage(Long userId, String sessionId, String blockedMessage)`。
