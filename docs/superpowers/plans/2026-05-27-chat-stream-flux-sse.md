# Chat Stream Flux SSE 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 `/api/chat/messages/stream` 从 `StreamingResponseBody + NDJSON` 改造为 `Flux + SSE`，保持一次发言一次流返回，并让前端按 SSE 稳定消费聊天增量输出。

**架构：** 后端在现有 Spring MVC 应用中局部引入 Reactor/WebFlux 类型，`ChatService` 直接产出 `Flux<ChatStreamEvent>`，控制器负责映射为 `ServerSentEvent`。前端继续使用 `fetch` 发起 `POST` 请求，但将流解析器从 NDJSON 逐行解析改为 SSE block 解析，页面层继续复用现有消息状态机。

**技术栈：** Spring Boot 3.4、Spring WebFlux 类型、Reactor Flux、ServerSentEvent、Next.js、原生 `fetch`、Node test、JUnit 5、Mockito

---

## 文件结构

### 后端

- 修改：`backend/pom.xml`
  - 增加 WebFlux 依赖，提供 `Flux` 与 `ServerSentEvent` 类型支持。
- 修改：`backend/src/main/java/com/h/backend/chat/controller/ChatController.java`
  - 将接口从 `ResponseEntity<StreamingResponseBody>` 改为 `Flux<ServerSentEvent<ChatStreamEvent>>`。
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatService.java`
  - 将 `streamChat(...)` 签名改为直接返回 `Flux<ChatStreamEvent>`。
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
  - 用响应式 sink 替代 `Consumer<String> + CountDownLatch`，在服务层直接发出 `chunk`、`done`、`blocked`、`error` 事件。
- 可能创建：`backend/src/main/java/com/h/backend/chat/dto/ChatStreamEvent.java`
  - 如果不想把事件 record 留在控制器中，则将事件模型上提为可复用 DTO。
- 修改：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`
  - 从 NDJSON 输出断言改为 SSE 事件流断言。
- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
  - 从返回 `String`/抛异常的测试，迁移为消费 `Flux<ChatStreamEvent>` 的测试。

### 前端

- 修改：`frontend/lib/http.ts`
  - 将 `apiStream` 从 NDJSON 逐行解析改为 SSE block 解析。
- 修改：`frontend/lib/http.test.mjs`
  - 更新流解析测试样例，覆盖 `chunk`、`done`、`blocked`、`error` 的 SSE 格式。
- 修改：`frontend/app/chat/page.tsx`
  - 调整 `onDone` 行为，不再依赖后端回传完整文本覆盖消息。

## 任务 1：补齐后端依赖并锁定响应式接口契约

**文件：**
- 修改：`backend/pom.xml`
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatService.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`

- [ ] **步骤 1：为控制器契约编写失败测试**

在 `backend/src/test/java/com/h/backend/chat/ChatControllerTest.java` 中，将现有基于 `StreamingResponseBody` 的测试替换为基于 `Flux` 的输出断言，先只覆盖控制器层契约。

目标测试形态：

```java
@Test
void shouldExposeTextEventStreamContentType() {
    ChatService chatService = mock(ChatService.class);
    ObjectMapper objectMapper = new ObjectMapper();
    ChatController controller = new ChatController(chatService, objectMapper);
    AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
    ChatMessageRequest request = new ChatMessageRequest("hello", "session-1", 2L);

    when(chatService.streamChat(1L, 2L, "session-1", "hello"))
            .thenReturn(Flux.just(new ChatStreamEvent("done", "")));

    Flux<ServerSentEvent<ChatStreamEvent>> response = controller.streamMessage(principal, request);

    List<ServerSentEvent<ChatStreamEvent>> events = response.collectList().block();
    assertEquals("done", events.getFirst().event());
    assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, controller.getClass()
            .getDeclaredMethod("streamMessage", AuthUserPrincipal.class, ChatMessageRequest.class)
            .getAnnotation(PostMapping.class).produces()[0]);
}
```

- [ ] **步骤 2：运行后端控制器测试，确认因接口签名未迁移而失败**

运行：

```bash
source ~/.profile && mvn -Dtest=ChatControllerTest test
```

预期：

- `ChatControllerTest` 编译失败或测试失败
- 失败原因包含 `Flux` / `ServerSentEvent` / `streamChat` 签名不匹配

- [ ] **步骤 3：增加 WebFlux 依赖并修改服务接口签名**

在 `backend/pom.xml` 中添加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

将 `backend/src/main/java/com/h/backend/chat/service/ChatService.java` 修改为：

```java
public interface ChatService {

    Flux<ChatStreamEvent> streamChat(Long userId, Long promptId, String sessionId, String userMessage);
}
```

如果需要新建 DTO，则创建：

```java
package com.h.backend.chat.dto;

public record ChatStreamEvent(String type, String content) {
}
```

- [ ] **步骤 4：运行后端控制器测试，确认依赖与接口签名通过编译**

运行：

```bash
source ~/.profile && mvn -Dtest=ChatControllerTest test
```

预期：

- 编译不再因为缺少 `Flux` / `ServerSentEvent` 报错
- 测试仍可能失败，因为控制器实现尚未迁移

- [ ] **步骤 5：Commit**

```bash
git add backend/pom.xml \
  backend/src/main/java/com/h/backend/chat/service/ChatService.java \
  backend/src/main/java/com/h/backend/chat/dto/ChatStreamEvent.java \
  backend/src/test/java/com/h/backend/chat/ChatControllerTest.java
git commit -m "refactor: prepare chat stream flux contract"
```

如果未创建 `ChatStreamEvent.java`，则从 `git add` 中移除该文件。

## 任务 2：迁移控制器到 `Flux + SSE`

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/controller/ChatController.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`

- [ ] **步骤 1：补充控制器层失败测试，断言 SSE 事件格式**

在 `ChatControllerTest` 中补充两类测试：

```java
@Test
void shouldMapChunkAndDoneEventsToServerSentEvents() {
    when(chatService.streamChat(1L, 2L, "session-1", "hello"))
            .thenReturn(Flux.just(
                    new ChatStreamEvent("chunk", "he"),
                    new ChatStreamEvent("done", "")
            ));

    List<ServerSentEvent<ChatStreamEvent>> events = controller.streamMessage(principal, request)
            .collectList()
            .block();

    assertEquals("chunk", events.get(0).event());
    assertEquals("he", events.get(0).data().content());
    assertEquals("done", events.get(1).event());
    assertEquals("", events.get(1).data().content());
}

@Test
void shouldTrimRequestMessageBeforeDelegatingToService() {
    when(chatService.streamChat(1L, 2L, "session-1", "hello"))
            .thenReturn(Flux.just(new ChatStreamEvent("done", "")));

    controller.streamMessage(principal, new ChatMessageRequest("  hello  ", "session-1", 2L))
            .collectList()
            .block();

    verify(chatService).streamChat(1L, 2L, "session-1", "hello");
}
```

- [ ] **步骤 2：运行控制器测试，确认实现尚未切换而失败**

运行：

```bash
source ~/.profile && mvn -Dtest=ChatControllerTest test
```

预期：

- 失败信息显示 `streamMessage` 返回类型不匹配
- 或控制器仍在输出 `ResponseEntity<StreamingResponseBody>`

- [ ] **步骤 3：实现控制器 SSE 映射**

将 `ChatController` 改为类似：

```java
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> streamMessage(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @Valid @RequestBody ChatMessageRequest request
    ) {
        return chatService.streamChat(
                        principal.userId(),
                        request.promptId(),
                        request.sessionId(),
                        request.message().trim()
                )
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());
    }
}
```

同时删除不再使用的：

- `ResponseEntity`
- `StreamingResponseBody`
- `OutputStreamWriter`
- `writeEvent(...)`
- 控制器内嵌 `ChatStreamEvent`

- [ ] **步骤 4：运行控制器测试，确认控制器迁移完成**

运行：

```bash
source ~/.profile && mvn -Dtest=ChatControllerTest test
```

预期：

- `ChatControllerTest` PASS

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/controller/ChatController.java \
  backend/src/test/java/com/h/backend/chat/ChatControllerTest.java
git commit -m "refactor: switch chat controller to flux sse"
```

## 任务 3：将服务层改为直接产出 `Flux<ChatStreamEvent>`

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`

- [ ] **步骤 1：编写失败测试，固定服务层事件语义**

在 `ChatServiceImplTest` 中新增或改写为基于 `Flux` 的测试，至少覆盖：

```java
@Test
void shouldEmitChunkEventsAndDoneEventForSuccessfulStream() {
    when(hAssistant.streamChat("1:22:session-1", "hello"))
            .thenReturn(new FakeTokenStream().emitText("he").emitText("llo"));

    List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-1", "hello")
            .collectList()
            .block();

    assertEquals(List.of(
            new ChatStreamEvent("chunk", "he"),
            new ChatStreamEvent("chunk", "llo"),
            new ChatStreamEvent("done", "")
    ), events);
}

@Test
void shouldEmitBlockedEventWhenGuardrailFails() {
    when(hAssistant.streamChat("1:22:session-guardrail", "杀人"))
            .thenReturn(new FakeTokenStream().emitError(
                    new InputGuardrailException("...系统提醒您：请勿使用暴力")
            ));

    List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-guardrail", "杀人")
            .collectList()
            .block();

    assertEquals(List.of(new ChatStreamEvent("blocked", "系统提醒您：请勿使用暴力")), events);
}

@Test
void shouldEmitErrorEventWhenRuntimeErrorOccurs() {
    when(hAssistant.streamChat("1:22:session-2", "hello"))
            .thenReturn(new FakeTokenStream().emitError(new RuntimeException("boom")));

    List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-2", "hello")
            .collectList()
            .block();

    assertEquals(List.of(new ChatStreamEvent("error", "AI 服务调用失败")), events);
}
```

保留现有对：

- `appendAssistantMessage(...)`
- `appendBlockedMessage(...)`
- `completeRun(...)`
- `failRun(...)`
- `markSuccess(...)`
- `markFailure(...)`

的 verify 断言。

- [ ] **步骤 2：运行服务层测试，确认当前阻塞式实现失败**

运行：

```bash
source ~/.profile && mvn -Dtest=ChatServiceImplTest test
```

预期：

- 失败原因包含 `streamChat` 返回类型已变化
- 或测试断言等不到 `ChatStreamEvent`

- [ ] **步骤 3：实现服务层 Flux 事件流**

将 `ChatServiceImpl#streamChat(...)` 改成 `Flux<ChatStreamEvent>`，核心结构参考：

```java
@Override
public Flux<ChatStreamEvent> streamChat(Long userId, Long promptId, String sessionId, String userMessage) {
    chatSessionService.assertActiveSession(userId, sessionId, promptId);
    Long resolvedPromptId = systemPromptService.resolvePromptId(userId, promptId);
    String memoryId = userId + ":" + resolvedPromptId + ":" + sessionId;
    Long userMessageId = chatSessionService.appendUserMessage(userId, sessionId, userMessage);
    AgentRunTelemetryService.TelemetryRun telemetryRun =
            agentRunTelemetryService.startRun(sessionId, userId, resolvedPromptId);
    AgentRunService.AgentRunHandle runHandle = agentRunService.createRun(
            sessionId, userId, resolvedPromptId, userMessageId, "unknown", telemetryRun.traceId()
    );

    return Flux.create(sink -> {
        StringBuilder replyBuilder = new StringBuilder();

        try {
            hAssistant.streamChat(memoryId, userMessage)
                    .onPartialResponse(chunk -> {
                        replyBuilder.append(chunk);
                        sink.next(new ChatStreamEvent("chunk", chunk));
                    })
                    .onToolExecuted(toolExecution -> recordToolUsage(runHandle.id(), toolExecution))
                    .onCompleteResponse(ignored -> {
                        if (replyBuilder.toString().isBlank()) {
                            agentRunService.failRun(runHandle.id(), "AI 未返回有效内容");
                            agentRunTelemetryService.markFailure(
                                    telemetryRun, new IllegalStateException("AI 未返回有效内容")
                            );
                            sink.next(new ChatStreamEvent("error", "AI 未返回有效内容"));
                            sink.complete();
                            return;
                        }
                        Long assistantMessageId = chatSessionService.appendAssistantMessage(
                                userId, sessionId, replyBuilder.toString()
                        );
                        agentRunService.completeRun(runHandle.id(), assistantMessageId);
                        agentRunTelemetryService.markSuccess(telemetryRun);
                        sink.next(new ChatStreamEvent("done", ""));
                        sink.complete();
                    })
                    .onError(error -> {
                        emitFailureEvent(sink, userId, sessionId, runHandle.id(), telemetryRun, error);
                    })
                    .start();
        } catch (Exception ex) {
            emitFailureEvent(sink, userId, sessionId, runHandle.id(), telemetryRun, ex);
        }
    });
}
```

新增私有方法，例如：

```java
private void emitFailureEvent(
        FluxSink<ChatStreamEvent> sink,
        Long userId,
        String sessionId,
        Long runId,
        AgentRunTelemetryService.TelemetryRun telemetryRun,
        Throwable error
) {
    if (error instanceof ModelDisabledException) {
        agentRunService.failRun(runId, "AI 服务未配置 OPENAI_API_KEY");
        agentRunTelemetryService.markFailure(telemetryRun, error);
        sink.next(new ChatStreamEvent("error", "AI 服务未配置 OPENAI_API_KEY"));
        sink.complete();
        return;
    }
    if (error instanceof InputGuardrailException || error instanceof OutputGuardrailException) {
        String cleanMessage = cleanGuardrailMessage(error.getMessage());
        chatSessionService.appendBlockedMessage(userId, sessionId, cleanMessage);
        agentRunService.failRun(runId, cleanMessage);
        agentRunTelemetryService.markFailure(telemetryRun, error);
        sink.next(new ChatStreamEvent("blocked", cleanMessage));
        sink.complete();
        return;
    }
    agentRunService.failRun(runId, error.getMessage() == null ? "AI 服务调用失败" : error.getMessage());
    agentRunTelemetryService.markFailure(telemetryRun, error);
    sink.next(new ChatStreamEvent("error", "AI 服务调用失败"));
    sink.complete();
}
```

- [ ] **步骤 4：运行服务层测试，确认事件流与业务收口都正常**

运行：

```bash
source ~/.profile && mvn -Dtest=ChatServiceImplTest test
```

预期：

- `ChatServiceImplTest` PASS

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java \
  backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java
git commit -m "refactor: emit chat stream events from service"
```

## 任务 4：将前端流解析从 NDJSON 切换为 SSE

**文件：**
- 修改：`frontend/lib/http.ts`
- 修改：`frontend/lib/http.test.mjs`

- [ ] **步骤 1：先写失败测试，固定 SSE 解析行为**

在 `frontend/lib/http.test.mjs` 中把 NDJSON 样例替换为 SSE block，至少补充这些测试：

```javascript
test("apiStream dispatches chunk and done events from sse blocks", async () => {
  const originalFetch = globalThis.fetch;
  const chunks = [];
  let doneCalled = false;

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              "event: chunk\\n" +
              'data: {"type":"chunk","content":"he"}\\n\\n' +
              "event: chunk\\n" +
              'data: {"type":"chunk","content":"llo"}\\n\\n' +
              "event: done\\n" +
              'data: {"type":"done","content":""}\\n\\n'
            )
          );
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );

  try {
    await apiStream("/api/chat/messages/stream", { method: "POST" }, {
      onChunk(value) { chunks.push(value); },
      onDone() { doneCalled = true; },
    });
    assert.deepEqual(chunks, ["he", "llo"]);
    assert.equal(doneCalled, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
```

保留并改写现有 `blocked`、`error` 测试，使输入变成：

```text
event: blocked
data: {"type":"blocked","content":"系统提醒您：请勿使用暴力"}

```

- [ ] **步骤 2：运行前端流解析测试，确认当前 NDJSON 解析失败**

运行：

```bash
source ~/.profile && npm test --prefix frontend
```

预期：

- `frontend/lib/http.test.mjs` FAIL
- 失败原因是当前解析器不能识别 `event:` / `data:` block

- [ ] **步骤 3：实现 SSE 解析器**

将 `frontend/lib/http.ts` 中的 `apiStream` 核心解析逻辑改为：

```typescript
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const blocks = buffer.split("\\n\\n");
    buffer = blocks.pop() ?? "";

    for (const rawBlock of blocks) {
      const block = rawBlock.trim();
      if (!block) continue;

      const lines = block.split("\\n");
      const eventLine = lines.find((line) => line.startsWith("event:"));
      const dataLine = lines.find((line) => line.startsWith("data:"));
      if (!dataLine) continue;

      const eventName = eventLine?.slice("event:".length).trim();
      const payload = JSON.parse(dataLine.slice("data:".length).trim()) as {
        type: string;
        content: string;
      };
      const eventType = eventName || payload.type;

      if (eventType === "chunk") {
        handlers.onChunk(payload.content);
      } else if (eventType === "done") {
        handlers.onDone?.(payload.content);
      } else if (eventType === "blocked") {
        handlers.onBlocked?.(payload.content);
      } else if (eventType === "error") {
        handlers.onError?.(payload.content);
        throw new Error(payload.content || "请求失败");
      }
    }
  }
```

- [ ] **步骤 4：运行前端流解析测试，确认 SSE 解析通过**

运行：

```bash
source ~/.profile && npm test --prefix frontend
```

预期：

- `frontend/lib/http.test.mjs` PASS

- [ ] **步骤 5：Commit**

```bash
git add frontend/lib/http.ts frontend/lib/http.test.mjs
git commit -m "refactor: parse chat stream as sse"
```

## 任务 5：调整聊天页面收口逻辑并做端到端回归

**文件：**
- 修改：`frontend/app/chat/page.tsx`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
- 测试：`frontend/lib/http.test.mjs`

- [ ] **步骤 1：为页面层行为补充最小失败断言**

如果当前仓库没有页面级自动化测试，本任务只做代码内行为收敛，并通过现有 `http` 测试与后端测试保底。先在 `frontend/app/chat/page.tsx` 中定位 `onDone` 的旧逻辑：

```typescript
onDone(finalContent) {
  setMessages((current) =>
    current.map((message) =>
      message.id === assistantId && message.role === "assistant"
        ? { ...message, content: finalContent }
        : message,
    ),
  );
}
```

将其视为待修改点，计划中的失败验证依赖后续人工检查：

- 如果 `done` 不再带完整文本，上述逻辑会在结束时把消息覆盖为空字符串。

- [ ] **步骤 2：运行现有测试，确认页面层逻辑尚未兼容新的 `done` 语义**

运行：

```bash
source ~/.profile && npm test --prefix frontend
source ~/.profile && mvn -Dtest=ChatControllerTest,ChatServiceImplTest test
```

预期：

- 自动化测试可能已经通过
- 但需要根据代码确认 `onDone(finalContent)` 仍会覆盖消息内容，这是待修复点

- [ ] **步骤 3：将页面层 `done` 行为改为纯收口，不覆盖内容**

将 `frontend/app/chat/page.tsx` 中的 `onDone` 改为类似：

```typescript
onDone() {
  setCurrentSessionTitle((current) =>
    current === "新会话" ? content.slice(0, 20) || current : current,
  );
}
```

保留：

- `onChunk` 负责增量拼接
- `onBlocked` 负责切换为 blocked 消息
- `onError` 负责错误提示

确保不再依赖 `done` 事件中的完整文本。

- [ ] **步骤 4：运行回归测试并做本地构建检查**

运行：

```bash
source ~/.profile && npm test --prefix frontend
source ~/.profile && mvn -Dtest=ChatControllerTest,ChatServiceImplTest test
source ~/.profile && npm run build --prefix frontend
source ~/.profile && mvn -q -DskipTests compile -f backend/pom.xml
```

预期：

- 前端测试 PASS
- 两个后端测试类 PASS
- Next.js 构建通过
- 后端编译通过

- [ ] **步骤 5：Commit**

```bash
git add frontend/app/chat/page.tsx \
  frontend/lib/http.ts \
  frontend/lib/http.test.mjs \
  backend/src/main/java/com/h/backend/chat/controller/ChatController.java \
  backend/src/main/java/com/h/backend/chat/service/ChatService.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java \
  backend/src/test/java/com/h/backend/chat/ChatControllerTest.java \
  backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java \
  backend/pom.xml
git commit -m "feat: stream chat responses via flux sse"
```

## 自检

### 规格覆盖度

- `POST + Flux + SSE`：任务 1、2、3
- 服务层直接产出事件流：任务 3
- 保持 `chunk` / `done` / `blocked` / `error`：任务 2、3、4
- 前端继续 `fetch + POST`：任务 4
- `done` 不再携带完整文本：任务 3、5
- 保持会话持久化与 agent run 收口：任务 3

未发现规格遗漏。

### 占位符扫描

- 未使用 “TODO”“待定”“后续实现”“类似任务 N” 等占位语。
- 每个任务均包含明确文件、命令和代码方向。

### 类型一致性

- 统一使用 `ChatStreamEvent`
- `ChatService#streamChat(...)` 统一返回 `Flux<ChatStreamEvent>`
- 控制器统一映射为 `ServerSentEvent<ChatStreamEvent>`
- 前端统一消费 `chunk`、`done`、`blocked`、`error`

## 执行交接

计划已完成并保存到 `docs/superpowers/plans/2026-05-27-chat-stream-flux-sse.md`。两种执行方式：

**1. 子代理驱动（推荐）** - 每个任务调度一个新的子代理，任务间进行审查，快速迭代

**2. 内联执行** - 在当前会话中使用 executing-plans 执行任务，批量执行并设有检查点

选哪种方式？
