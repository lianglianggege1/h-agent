# 聊天流式心跳与并发闸门 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让聊天 SSE 在 agent 前置阻塞时仍能稳定发 heartbeat，并为多用户、多会话场景增加立即拒绝式并发闸门，同时保持前端断开后 agent 继续执行并沿用现有落库结构。

**架构：** 保留现有 `/api/chat/messages/stream` 路径和 SSE 协议。控制器继续负责 heartbeat，但将 heartbeat 间隔配置化以便测试；服务层新增本地并发闸门与虚拟线程执行器，把 agent 主流程从订阅线程中移走。agent 成功或失败都通过现有 `chat_session_messages` 与 `agent_run` 收口。

**技术栈：** Spring Boot 3.4、Reactor Flux、Java 23 虚拟线程、JUnit 5、Mockito

---

## 文件结构

- 创建：`backend/src/main/java/com/h/backend/chat/config/ChatStreamProperties.java`
  - 统一管理 heartbeat、用户级并发、全局并发配置
- 创建：`backend/src/main/java/com/h/backend/chat/config/ChatStreamAsyncConfig.java`
  - 提供虚拟线程 `ExecutorService`
- 创建：`backend/src/main/java/com/h/backend/chat/service/ChatStreamConcurrencyGuard.java`
  - 定义会话级、用户级、全局级闸门接口
- 创建：`backend/src/main/java/com/h/backend/chat/service/impl/InMemoryChatStreamConcurrencyGuard.java`
  - 基于 JVM 内存实现立即拒绝式并发闸门
- 修改：`backend/src/main/java/com/h/backend/chat/controller/ChatController.java`
  - 从固定常量改为读取 heartbeat 配置
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
  - 使用并发闸门和虚拟线程执行 agent
- 修改：`backend/src/main/resources/application.yml`
  - 提供默认配置值
- 修改：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`
  - 覆盖 heartbeat 配置化后的保活行为
- 创建：`backend/src/test/java/com/h/backend/chat/ChatStreamConcurrencyGuardTest.java`
  - 覆盖会话级、用户级、全局级立即拒绝逻辑
- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
  - 覆盖虚拟线程执行、闸门拒绝、完成后释放名额

## 任务 1：让 heartbeat 可配置且可测试

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/config/ChatStreamProperties.java`
- 修改：`backend/src/main/java/com/h/backend/chat/controller/ChatController.java`
- 修改：`backend/src/main/resources/application.yml`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`

- [ ] **步骤 1：编写失败的测试**

在 `backend/src/test/java/com/h/backend/chat/ChatControllerTest.java` 新增一个可注入 heartbeat 间隔的测试，先要求控制器在 200ms 间隔下先发 heartbeat 再发 `done`：

```java
@Test
void shouldEmitHeartbeatBeforeDelayedDoneEvent() {
    ChatService chatService = mock(ChatService.class);
    ChatStreamProperties properties = new ChatStreamProperties();
    properties.setHeartbeatInterval(Duration.ofMillis(200));
    ChatController controller = new ChatController(chatService, properties);
    AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
    ChatMessageRequest request = new ChatMessageRequest("hello", "session-1", 2L);

    when(chatService.streamChat(1L, 2L, "session-1", "hello"))
            .thenReturn(Flux.just(new ChatStreamEvent("done", ""))
                    .delaySubscription(Duration.ofMillis(450)));

    List<ServerSentEvent<ChatStreamEvent>> events = controller.streamMessage(principal, request)
            .take(2)
            .collectList()
            .block(Duration.ofSeconds(2));

    assertNotNull(events);
    assertEquals("keepalive", events.get(0).comment());
    assertEquals("done", events.get(1).event());
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`source ~/.profile && mvn -Dtest=ChatControllerTest#shouldEmitHeartbeatBeforeDelayedDoneEvent test`

预期：FAIL，报错构造器不匹配或 heartbeat 仍使用固定常量。

- [ ] **步骤 3：编写最少实现代码**

创建 `ChatStreamProperties`：

```java
@ConfigurationProperties(prefix = "chat.stream")
public class ChatStreamProperties {

    private Duration heartbeatInterval = Duration.ofSeconds(15);
    private int maxConcurrentPerUser = 2;
    private int maxConcurrentGlobal = 100;

    // getters / setters
}
```

修改 `ChatController`：

```java
private final Duration heartbeatInterval;

public ChatController(ChatService chatService, ChatStreamProperties properties) {
    this.chatService = chatService;
    this.heartbeatInterval = properties.getHeartbeatInterval();
}

Flux<ServerSentEvent<ChatStreamEvent>> heartbeats = Flux.interval(heartbeatInterval)
        .map(tick -> ServerSentEvent.<ChatStreamEvent>builder()
                .comment("keepalive")
                .build());
```

在 `application.yml` 增加：

```yaml
chat:
  stream:
    heartbeat-interval: 15s
    max-concurrent-per-user: 2
    max-concurrent-global: 100
```

- [ ] **步骤 4：运行测试验证通过**

运行：`source ~/.profile && mvn -Dtest=ChatControllerTest test`

预期：PASS，heartbeat 测试稳定通过。

- [ ] **步骤 5：Commit**

```bash
source ~/.profile && git add \
  backend/src/main/java/com/h/backend/chat/config/ChatStreamProperties.java \
  backend/src/main/java/com/h/backend/chat/controller/ChatController.java \
  backend/src/main/resources/application.yml \
  backend/src/test/java/com/h/backend/chat/ChatControllerTest.java
source ~/.profile && git commit -m "refactor: make chat stream heartbeat configurable"
```

## 任务 2：实现立即拒绝式并发闸门

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/service/ChatStreamConcurrencyGuard.java`
- 创建：`backend/src/main/java/com/h/backend/chat/service/impl/InMemoryChatStreamConcurrencyGuard.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatStreamConcurrencyGuardTest.java`

- [ ] **步骤 1：编写失败的测试**

在 `backend/src/test/java/com/h/backend/chat/ChatStreamConcurrencyGuardTest.java` 覆盖三种拒绝逻辑：

```java
@Test
void shouldRejectSecondRunForSameSession() {
    InMemoryChatStreamConcurrencyGuard guard = new InMemoryChatStreamConcurrencyGuard(2, 100);

    ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
    ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-1", 1L);

    assertTrue(first.acquired());
    assertFalse(second.acquired());
    assertEquals("当前会话正在处理中", second.message());
    first.release();
}

@Test
void shouldRejectWhenUserLimitExceeded() {
    InMemoryChatStreamConcurrencyGuard guard = new InMemoryChatStreamConcurrencyGuard(1, 100);

    ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
    ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-2", 1L);

    assertTrue(first.acquired());
    assertFalse(second.acquired());
    assertEquals("当前系统繁忙，请稍后再试", second.message());
    first.release();
}

@Test
void shouldRejectWhenGlobalLimitExceeded() {
    InMemoryChatStreamConcurrencyGuard guard = new InMemoryChatStreamConcurrencyGuard(10, 1);

    ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
    ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-2", 2L);

    assertTrue(first.acquired());
    assertFalse(second.acquired());
    assertEquals("当前系统繁忙，请稍后再试", second.message());
    first.release();
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`source ~/.profile && mvn -Dtest=ChatStreamConcurrencyGuardTest test`

预期：FAIL，类或方法不存在。

- [ ] **步骤 3：编写最少实现代码**

接口：

```java
public interface ChatStreamConcurrencyGuard {

    Permit tryAcquire(String sessionId, Long userId);

    interface Permit {
        boolean acquired();
        String message();
        void release();
    }
}
```

实现核心：

```java
public Permit tryAcquire(String sessionId, Long userId) {
    synchronized (monitor) {
        if (activeSessions.contains(sessionId)) {
            return rejected("当前会话正在处理中");
        }
        if (activeUsers.getOrDefault(userId, 0) >= maxConcurrentPerUser) {
            return rejected("当前系统繁忙，请稍后再试");
        }
        if (activeGlobal >= maxConcurrentGlobal) {
            return rejected("当前系统繁忙，请稍后再试");
        }
        activeSessions.add(sessionId);
        activeUsers.merge(userId, 1, Integer::sum);
        activeGlobal++;
        return acquiredPermit(sessionId, userId);
    }
}
```

释放核心：

```java
private void release(String sessionId, Long userId) {
    synchronized (monitor) {
        activeSessions.remove(sessionId);
        activeUsers.computeIfPresent(userId, (key, count) -> count > 1 ? count - 1 : null);
        activeGlobal--;
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`source ~/.profile && mvn -Dtest=ChatStreamConcurrencyGuardTest test`

预期：PASS，三种拒绝逻辑稳定通过。

- [ ] **步骤 5：Commit**

```bash
source ~/.profile && git add \
  backend/src/main/java/com/h/backend/chat/service/ChatStreamConcurrencyGuard.java \
  backend/src/main/java/com/h/backend/chat/service/impl/InMemoryChatStreamConcurrencyGuard.java \
  backend/src/test/java/com/h/backend/chat/ChatStreamConcurrencyGuardTest.java
source ~/.profile && git commit -m "feat: add chat stream concurrency guard"
```

## 任务 3：将 agent 主流程移入虚拟线程并接入并发闸门

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/config/ChatStreamAsyncConfig.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`

- [ ] **步骤 1：编写失败的测试**

在 `backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java` 新增两个测试。

第一个测试要求闸门拒绝时直接返回错误事件：

```java
@Test
void shouldEmitErrorWhenConcurrencyGuardRejectsRun() {
    ChatStreamConcurrencyGuard guard = mock(ChatStreamConcurrencyGuard.class);
    when(guard.tryAcquire("session-1", 1L)).thenReturn(new RejectedPermit("当前会话正在处理中"));

    ChatServiceImpl chatService = new ChatServiceImpl(
            hAssistant,
            systemPromptService,
            chatSessionService,
            agentRunService,
            agentRunTelemetryService,
            Runnable::run,
            guard
    );

    List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-1", "hello")
            .collectList()
            .block();

    assertEquals(List.of(new ChatStreamEvent("error", "当前会话正在处理中")), events);
    verifyNoInteractions(hAssistant);
}
```

第二个测试要求 agent 执行不再阻塞订阅线程，并在完成后释放 permit：

```java
@Test
void shouldRunAgentOnExecutorAndReleasePermitAfterCompletion() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    RecordingPermit permit = new RecordingPermit();
    ChatStreamConcurrencyGuard guard = mock(ChatStreamConcurrencyGuard.class);
    when(guard.tryAcquire("session-1", 1L)).thenReturn(permit);

    BlockingTokenStream tokenStream = new BlockingTokenStream()
            .emitText("hello")
            .releaseAfterStart();
    when(hAssistant.streamChat("1:22:session-1", "hello")).thenReturn(tokenStream);

    ChatServiceImpl chatService = new ChatServiceImpl(
            hAssistant,
            systemPromptService,
            chatSessionService,
            agentRunService,
            agentRunTelemetryService,
            executor,
            guard
    );

    List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-1", "hello")
            .collectList()
            .block(Duration.ofSeconds(2));

    assertEquals(List.of(
            new ChatStreamEvent("chunk", "hello"),
            new ChatStreamEvent("done", "")
    ), events);
    assertTrue(permit.released);
    executor.shutdownNow();
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`source ~/.profile && mvn -Dtest=ChatServiceImplTest test`

预期：FAIL，构造器参数不匹配，或 permit 未释放。

- [ ] **步骤 3：编写最少实现代码**

创建虚拟线程执行器：

```java
@Configuration
public class ChatStreamAsyncConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService chatStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
```

修改 `ChatServiceImpl` 构造器注入：

```java
private final ExecutorService chatStreamExecutor;
private final ChatStreamConcurrencyGuard concurrencyGuard;
```

在 `streamChat()` 开头获取 permit：

```java
ChatStreamConcurrencyGuard.Permit permit = concurrencyGuard.tryAcquire(sessionId, userId);
if (!permit.acquired()) {
    return Flux.just(new ChatStreamEvent("error", permit.message()));
}
```

把现有主流程包进执行器：

```java
return Flux.create(sink -> chatStreamExecutor.submit(() -> {
    try {
        // 保留现有 assertActiveSession / appendUserMessage / startRun / hAssistant.streamChat 流程
    } catch (Exception ex) {
        emitFailureEvent(sink, userId, sessionId, runId, telemetryRun, ex);
    } finally {
        permit.release();
    }
}));
```

注意：`permit.release()` 必须放在最终收口路径里，只执行一次。

- [ ] **步骤 4：运行测试验证通过**

运行：`source ~/.profile && mvn -Dtest=ChatServiceImplTest test`

预期：PASS，拒绝逻辑、异步执行和 permit 释放都通过。

- [ ] **步骤 5：Commit**

```bash
source ~/.profile && git add \
  backend/src/main/java/com/h/backend/chat/config/ChatStreamAsyncConfig.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java \
  backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java
source ~/.profile && git commit -m "feat: run chat agent on virtual threads"
```

## 任务 4：补全装配与回归验证

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
- 修改：`backend/src/main/resources/application.yml`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatStreamConcurrencyGuardTest.java`

- [ ] **步骤 1：补充失败测试，覆盖前端断开不影响收口**

在 `ChatServiceImplTest` 增加一个“订阅提前取消后仍完成 agent 收口”的测试：

```java
@Test
void shouldContinueRunCompletionAfterSubscriberCancels() {
    RecordingPermit permit = new RecordingPermit();
    when(guard.tryAcquire("session-1", 1L)).thenReturn(permit);
    when(hAssistant.streamChat("1:22:session-1", "hello"))
            .thenReturn(new DelayedCompletionTokenStream("ok"));

    Flux<ChatStreamEvent> flux = chatService.streamChat(1L, 2L, "session-1", "hello");

    flux.take(1).blockLast(Duration.ofSeconds(1));

    verify(agentRunService, timeout(1000)).completeRun(55L, 202L);
    assertTrue(permit.released);
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`source ~/.profile && mvn -Dtest=ChatServiceImplTest#shouldContinueRunCompletionAfterSubscriberCancels test`

预期：FAIL，当前实现会因为订阅取消导致收口未完成或 permit 未释放。

- [ ] **步骤 3：调整实现保证收口与释放不依赖订阅是否继续**

如果 `Flux.create` 默认取消行为影响收口，则把 agent 事件发送与 agent 执行收口分开，保持：

```java
try {
    // agent 执行与落库
    if (!sink.isCancelled()) {
        sink.next(new ChatStreamEvent("done", ""));
        sink.complete();
    }
} finally {
    permit.release();
}
```

同样在错误路径中保持：

```java
agentRunService.failRun(runId, message);
agentRunTelemetryService.markFailure(telemetryRun, error);
if (!sink.isCancelled()) {
    sink.next(new ChatStreamEvent("error", "AI 服务调用失败"));
    sink.complete();
}
```

- [ ] **步骤 4：运行完整回归**

运行：`source ~/.profile && mvn -Dtest=ChatControllerTest,ChatServiceImplTest,ChatStreamConcurrencyGuardTest test`

预期：PASS，heartbeat、并发闸门、虚拟线程执行与断链后继续收口全部通过。

- [ ] **步骤 5：Commit**

```bash
source ~/.profile && git add \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java \
  backend/src/main/resources/application.yml \
  backend/src/test/java/com/h/backend/chat/ChatControllerTest.java \
  backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java \
  backend/src/test/java/com/h/backend/chat/ChatStreamConcurrencyGuardTest.java
source ~/.profile && git commit -m "test: cover chat stream heartbeat concurrency flow"
```
