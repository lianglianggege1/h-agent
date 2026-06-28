# Agent Call 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为普通聊天和领域 Agent 增加 `/call` 电话模式，支持用户语音输入、3 秒静默成句、assistant 分句预览播报、挂断回同一文字会话，并在文字历史中展示用户录音与 assistant 完整 TTS 音频条。

**架构：** 电话页是现有聊天会话的语音外壳：用户语音仍通过浏览器 STT 变成文本后走 `/api/chat/messages/stream`。后端新增 voice 模块：call turn 分片聚合保存用户原声，MiniMax HTTP T2A 提供即时 preview 和完整 assistant message TTS。聊天流扩展 `user_message` 与携带 assistant message 的 `done` 事件，用于把音频资源绑定到对应消息。

**技术栈：** Spring Boot 3.4、MyBatis Plus、Reactor SSE、JDK `RestClient`、Next.js 16、React 19、Node test、Web Speech API、MediaRecorder、MiniMax HTTP T2A。

---

## 文件结构

后端聊天流事件与消息 DTO：

- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatStreamEvent.java`
  - 已支持 `message` 字段，保持 record 形状，必要时补充构造器。
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java`
  - 新增通过 messageId 读取/校验消息、将资源绑定到消息的服务方法。
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
  - 新增 `getOwnedMessage(...)`、`bindStoredAudioResource(...)`、`toMessageDto(...)` 复用逻辑。
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
  - 在用户消息落库后发送 `user_message` 事件。
- 修改：`backend/src/main/java/com/h/backend/chat/agent/HAssistantStreamingExecutor.java`
  - assistant 完成落库后在 `done` 事件携带 assistant message。
- 修改：`backend/src/main/java/com/h/backend/chat/agent/AgenticSyncExecutor.java`
  - domain Agent 完成落库后在 `done` 事件携带 assistant message。
- 测试：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`

后端资源存储泛化：

- 修改：`backend/src/main/java/com/h/backend/chat/storage/LocalFileResourceStorage.java`
  - 复用 `ResourceSaveCommand.resourceType`，按资源类型保存到 `generated-images`、`call-audio` 等目录。
- 测试：`backend/src/test/java/com/h/backend/chat/storage/LocalFileResourceStorageTest.java`

后端 voice 模块：

- 创建：`backend/src/main/java/com/h/backend/voice/config/VoiceTtsProperties.java`
- 创建：`backend/src/main/java/com/h/backend/voice/dto/CallTurnStartRequest.java`
- 创建：`backend/src/main/java/com/h/backend/voice/dto/CallTurnStartResponse.java`
- 创建：`backend/src/main/java/com/h/backend/voice/dto/CallTurnFinalizeRequest.java`
- 创建：`backend/src/main/java/com/h/backend/voice/dto/VoiceResourceResponse.java`
- 创建：`backend/src/main/java/com/h/backend/voice/dto/TtsPreviewRequest.java`
- 创建：`backend/src/main/java/com/h/backend/voice/dto/TtsMessageRequest.java`
- 创建：`backend/src/main/java/com/h/backend/voice/tts/MiniMaxTtsRequest.java`
- 创建：`backend/src/main/java/com/h/backend/voice/tts/MiniMaxTtsResult.java`
- 创建：`backend/src/main/java/com/h/backend/voice/tts/MiniMaxTtsClient.java`
- 创建：`backend/src/main/java/com/h/backend/voice/tts/MiniMaxHttpTtsClient.java`
- 创建：`backend/src/main/java/com/h/backend/voice/service/CallTurnService.java`
- 创建：`backend/src/main/java/com/h/backend/voice/service/VoiceTtsService.java`
- 创建：`backend/src/main/java/com/h/backend/voice/controller/VoiceController.java`
- 修改：`backend/src/main/resources/application.yml`
- 测试：`backend/src/test/java/com/h/backend/voice/MiniMaxHttpTtsClientTest.java`
- 测试：`backend/src/test/java/com/h/backend/voice/CallTurnServiceTest.java`
- 测试：`backend/src/test/java/com/h/backend/voice/VoiceTtsServiceTest.java`
- 测试：`backend/src/test/java/com/h/backend/voice/VoiceControllerTest.java`

前端 API 与状态工具：

- 修改：`frontend/lib/http.ts`
  - 支持 `user_message`、`done.message` 回调。
- 创建：`frontend/lib/voice.ts`
  - 封装 call turn、chunk 上传、finalize、cancel、preview TTS、message TTS。
- 创建：`frontend/lib/call-state.ts`
  - 纯函数：静默提交、分句、队列状态、URL 构造。
- 修改：`frontend/lib/chat-message-state.ts`
  - 支持将后端返回的真实 user/assistant message 替换本地 placeholder，并保留音频资源。
- 测试：`frontend/lib/http.test.mjs`
- 测试：`frontend/lib/voice.test.mjs`
- 测试：`frontend/lib/call-state.test.mjs`
- 测试：`frontend/lib/chat-message-state.test.mjs`

前端页面：

- 修改：`frontend/app/chat/page.tsx`
  - 支持 `sessionId` 查询参数恢复指定会话。
  - 增加电话按钮进入 `/call`。
  - 确认 user/assistant 文本消息均展示音频资源。
- 创建：`frontend/app/call/page.tsx`
  - 电话页 UI、STT、MediaRecorder、chat stream、TTS preview 播放、挂断跳转。

---

## 任务 1：扩展聊天流事件，返回用户消息与 assistant done message

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
- 修改：`backend/src/main/java/com/h/backend/chat/agent/HAssistantStreamingExecutor.java`
- 修改：`backend/src/main/java/com/h/backend/chat/agent/AgenticSyncExecutor.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`

- [ ] **步骤 1：为 ChatSessionService 写失败测试，要求可按 messageId 返回 DTO**

在 `backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java` 添加测试：

```java
@Test
void getOwnedMessageReturnsMessageWithResources() {
    ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
    ChatSessionMessageMapper messageMapper = mock(ChatSessionMessageMapper.class);
    ChatMessageResourceMapper resourceMapper = mock(ChatMessageResourceMapper.class);
    ChatMemorySnapshotService snapshotService = mock(ChatMemorySnapshotService.class);
    SystemPromptService promptService = mock(SystemPromptService.class);
    ObjectMapper objectMapper = new ObjectMapper();
    ChatSessionServiceImpl service = new ChatSessionServiceImpl(
            sessionMapper,
            messageMapper,
            resourceMapper,
            snapshotService,
            promptService,
            objectMapper,
            testAgentRegistry()
    );

    ChatSessionEntity session = new ChatSessionEntity();
    session.setId(11L);
    session.setUserId(1L);
    session.setSessionId("session-1");
    session.setAgentId("standard-chat");
    session.setStatus("ACTIVE");

    ChatSessionMessageEntity row = new ChatSessionMessageEntity();
    row.setId(101L);
    row.setSessionRecordId(11L);
    row.setSessionId("session-1");
    row.setUserId(1L);
    row.setRoleCode("user");
    row.setMessageType("USER");
    row.setContentText("你好");
    row.setCreatedAt(LocalDateTime.now());

    ChatMessageResourceEntity audio = new ChatMessageResourceEntity();
    audio.setId("audio-1");
    audio.setMessageId(101L);
    audio.setUserId(1L);
    audio.setSessionId("session-1");
    audio.setResourceType("AUDIO");
    audio.setResourceRole("ATTACHMENT");
    audio.setViewUrl("/api/chat/resources/audio-1/content");
    audio.setDownloadUrl("/api/chat/resources/audio-1/download");
    audio.setMimeType("audio/webm");
    audio.setFileName("call-audio.webm");
    audio.setFileSize(3L);
    audio.setCreatedAt(LocalDateTime.now());

    when(sessionMapper.selectBySessionId("session-1")).thenReturn(session);
    when(messageMapper.selectById(101L)).thenReturn(row);
    when(resourceMapper.selectByMessageIds(List.of(101L))).thenReturn(List.of(audio));

    ChatSessionMessageDto dto = service.getOwnedMessage(1L, "session-1", 101L);

    assertEquals("101", dto.id());
    assertEquals("user", dto.role());
    assertEquals("USER", dto.messageType());
    assertEquals("你好", dto.content());
    assertEquals(1, dto.resources().size());
    assertEquals("AUDIO", dto.resources().getFirst().type());
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=ChatSessionServiceImplTest#getOwnedMessageReturnsMessageWithResources test
```

预期：编译失败，提示 `getOwnedMessage` 方法不存在。

- [ ] **步骤 3：实现 ChatSessionService.getOwnedMessage**

在 `backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java` 增加：

```java
ChatSessionMessageDto getOwnedMessage(Long userId, String sessionId, Long messageId);
```

在 `ChatSessionServiceImpl` 增加实现：

```java
@Override
public ChatSessionMessageDto getOwnedMessage(Long userId, String sessionId, Long messageId) {
    ChatSessionEntity session = requireOwnedSession(userId, sessionId);
    ChatSessionMessageEntity message = chatSessionMessageMapper.selectById(messageId);
    if (message == null
            || !session.getId().equals(message.getSessionRecordId())
            || !sessionId.equals(message.getSessionId())
            || !userId.equals(message.getUserId())) {
        throw new BusinessException(40404, "消息不存在");
    }
    List<ChatMessageResourceEntity> resources = chatMessageResourceMapper == null
            ? List.of()
            : chatMessageResourceMapper.selectByMessageIds(List.of(messageId));
    return toMessageDto(message, resources);
}
```

如果现有 `toMessageDto(...)` 私有方法签名不同，提取一个重载，复用 `buildMessagesPage(...)` 中已有的 DTO 映射逻辑。不要复制两份资源 metadata 解析代码。

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=ChatSessionServiceImplTest#getOwnedMessageReturnsMessageWithResources test
```

预期：PASS。

- [ ] **步骤 5：写失败测试，要求 ChatService 先发 user_message 再发 chunk/done**

在 `backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java` 添加：

```java
@Test
void shouldEmitUserMessageEventAfterAppendingUserMessage() {
    HAssistant hAssistant = mock(HAssistant.class);
    SystemPromptService systemPromptService = mock(SystemPromptService.class);
    ChatSessionService chatSessionService = mock(ChatSessionService.class);
    AgentRunService agentRunService = mock(AgentRunService.class);
    AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
    FakeTokenStream tokenStream = new FakeTokenStream().emitText("你好呀");
    ChatServiceImpl chatService = createChatService(
            hAssistant,
            systemPromptService,
            chatSessionService,
            agentRunService,
            agentRunTelemetryService,
            new DirectExecutorService(),
            (sessionId, userId) -> new RecordingPermit()
    );

    ChatSessionMessageDto userMessage = new ChatSessionMessageDto(
            "101", "user", "USER", "你好", null, List.of(), java.time.LocalDateTime.now()
    );
    ChatSessionMessageDto assistantMessage = new ChatSessionMessageDto(
            "202", "assistant", "AI", "你好呀", null, List.of(), java.time.LocalDateTime.now()
    );

    when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
    when(chatSessionService.appendUserMessage(eq(1L), eq("session-call"), eq("你好"), any())).thenReturn(101L);
    when(chatSessionService.getOwnedMessage(1L, "session-call", 101L)).thenReturn(userMessage);
    when(chatSessionService.appendAssistantMessage(1L, "session-call", "你好呀")).thenReturn(202L);
    when(chatSessionService.getOwnedMessage(1L, "session-call", 202L)).thenReturn(assistantMessage);
    when(agentRunTelemetryService.startRun("session-call", 1L, 22L))
            .thenReturn(new AgentRunTelemetryService.TelemetryRun(null, "trace-call"));
    when(agentRunService.createRun("session-call", 1L, 22L, 101L, "standard-chat", "trace-call"))
            .thenReturn(new AgentRunService.AgentRunHandle(55L));
    when(hAssistant.streamChat("1:22:session-call", "你好")).thenReturn(tokenStream);

    List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "standard-chat", "session-call", "你好", null)
            .collectList()
            .block();

    assertEquals("user_message", events.get(0).type());
    assertEquals(userMessage, events.get(0).message());
    assertEquals("chunk", events.get(1).type());
    assertEquals("done", events.get(2).type());
    assertEquals(assistantMessage, events.get(2).message());
}
```

- [ ] **步骤 6：运行测试验证失败**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=ChatServiceImplTest#shouldEmitUserMessageEventAfterAppendingUserMessage test
```

预期：FAIL，当前第一个事件是 `chunk`，且 `done.message` 为空。

- [ ] **步骤 7：在 ChatServiceImpl 发送 user_message**

在 `ChatServiceImpl.runChatStream(...)` 中：

```java
Long userMessageId = chatSessionService.appendUserMessage(userId, sessionId, userMessage, resources);
ChatSessionMessageDto persistedUserMessage = chatSessionService.getOwnedMessage(userId, sessionId, userMessageId);
emitIfActive(sink, new ChatStreamEvent("user_message", "", persistedUserMessage));
```

保持创建 `agent_runs` 使用同一个 `userMessageId`。

`runImageCommandStream(...)` 和 `emitImageCommandEvents(...)` 当前也会 append user message。第一版可以同样发 `user_message`，但图片命令不进入电话 TTS；若实现时不改图片命令，必须保证普通文本 chat 的测试通过。

- [ ] **步骤 8：在 HAssistantStreamingExecutor 的 done 事件携带 assistant message**

修改 `completeSuccessfulStream(...)`：

```java
Long assistantMessageId = chatSessionService.appendAssistantMessage(
        command.userId(),
        command.sessionId(),
        reply
);
ChatSessionMessageDto assistantMessage = chatSessionService.getOwnedMessage(
        command.userId(),
        command.sessionId(),
        assistantMessageId
);
agentRunService.completeRun(command.runHandle().id(), assistantMessageId);
agentRunTelemetryService.markSuccess(command.telemetryRun());
emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("done", "", assistantMessage));
```

图片-only 分支保持 `new ChatStreamEvent("done", "")`，即 `message = null`。

- [ ] **步骤 9：在 AgenticSyncExecutor 的 done 事件携带 assistant message**

修改 `AgenticSyncExecutor.execute(...)` 中 assistant 落库后：

```java
Long assistantMessageId = chatSessionService.appendAssistantMessage(
        command.userId(),
        command.sessionId(),
        reply
);
ChatSessionMessageDto assistantMessage = chatSessionService.getOwnedMessage(
        command.userId(),
        command.sessionId(),
        assistantMessageId
);
agentRunService.completeRun(command.runHandle().id(), assistantMessageId);
agentRunTelemetryService.markSuccess(command.telemetryRun());
emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("done", "", assistantMessage));
```

- [ ] **步骤 10：运行相关后端测试**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=ChatServiceImplTest,ChatSessionServiceImplTest,ChatControllerTest test
```

预期：PASS。若既有断言期待 `new ChatStreamEvent("done", "")`，更新为检查 `type/content` 或补 mock `getOwnedMessage(...)`。

- [ ] **步骤 11：Commit**

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main
git add backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java \
  backend/src/main/java/com/h/backend/chat/agent/HAssistantStreamingExecutor.java \
  backend/src/main/java/com/h/backend/chat/agent/AgenticSyncExecutor.java \
  backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java \
  backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java \
  backend/src/test/java/com/h/backend/chat/ChatControllerTest.java
git commit -m "feat: emit persisted chat stream messages"
```

---

## 任务 2：泛化本地资源存储，支持 call 音频目录

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/storage/LocalFileResourceStorage.java`
- 测试：`backend/src/test/java/com/h/backend/chat/storage/LocalFileResourceStorageTest.java`

- [ ] **步骤 1：编写失败测试，音频资源保存到 call-audio 目录**

创建 `backend/src/test/java/com/h/backend/chat/storage/LocalFileResourceStorageTest.java`：

```java
package com.h.backend.chat.storage;

import com.h.backend.chat.config.ImageGenerationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileResourceStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAudioResourcesUnderCallAudioDirectory() {
        ImageGenerationProperties properties = new ImageGenerationProperties(
                null,
                new ImageGenerationProperties.LocalStorage(tempDir.toString(), "")
        );
        LocalFileResourceStorage storage = new LocalFileResourceStorage(properties);

        StoredResource stored = storage.save(new ResourceSaveCommand(
                "AUDIO",
                "session-1",
                "call-user-recording",
                new byte[]{1, 2, 3},
                "audio/webm",
                "webm",
                null,
                null
        ));

        assertTrue(stored.storageKey().startsWith("call-audio/"));
        assertTrue(stored.fileName().endsWith(".webm"));
        assertEquals("audio/webm", stored.mimeType());
        assertEquals(3L, stored.fileSize());
        assertTrue(Files.exists(tempDir.resolve(stored.storageKey())));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=LocalFileResourceStorageTest test
```

预期：FAIL，当前 `storageKey` 以 `generated-images/` 开头。

- [ ] **步骤 3：实现按 resourceType 选择目录和文件名前缀**

在 `LocalFileResourceStorage.save(...)` 中将：

```java
String relativeKey = "generated-images/%04d/%02d/%02d/%s.%s".formatted(...);
```

替换为：

```java
String directory = "AUDIO".equalsIgnoreCase(command.resourceType()) ? "call-audio" : "generated-images";
String filePrefix = "AUDIO".equalsIgnoreCase(command.resourceType()) ? "call-audio" : "generated";
String relativeKey = "%s/%04d/%02d/%02d/%s.%s".formatted(
        directory,
        today.getYear(),
        today.getMonthValue(),
        today.getDayOfMonth(),
        resourceId,
        extension
);
```

并将 `StoredResource.fileName` 改为：

```java
"%s-%s.%s".formatted(filePrefix, resourceId, extension)
```

保持图片尺寸读取逻辑不变；音频 `ImageIO.read(...)` 返回 null 时宽高为 null。

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=LocalFileResourceStorageTest test
```

预期：PASS。

- [ ] **步骤 5：运行资源相关测试**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=ChatResourceControllerTest,ImageGenerationServiceImplTest,LocalFileResourceStorageTest test
```

预期：PASS。

- [ ] **步骤 6：Commit**

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main
git add backend/src/main/java/com/h/backend/chat/storage/LocalFileResourceStorage.java \
  backend/src/test/java/com/h/backend/chat/storage/LocalFileResourceStorageTest.java
git commit -m "feat: store audio resources separately"
```

---

## 任务 3：实现 MiniMax HTTP TTS 客户端和配置

**文件：**
- 创建：`backend/src/main/java/com/h/backend/voice/config/VoiceTtsProperties.java`
- 创建：`backend/src/main/java/com/h/backend/voice/tts/MiniMaxTtsRequest.java`
- 创建：`backend/src/main/java/com/h/backend/voice/tts/MiniMaxTtsResult.java`
- 创建：`backend/src/main/java/com/h/backend/voice/tts/MiniMaxTtsClient.java`
- 创建：`backend/src/main/java/com/h/backend/voice/tts/MiniMaxHttpTtsClient.java`
- 修改：`backend/src/main/resources/application.yml`
- 测试：`backend/src/test/java/com/h/backend/voice/MiniMaxHttpTtsClientTest.java`

- [ ] **步骤 1：编写失败测试，MiniMax TTS 请求包含鉴权和正文**

创建 `backend/src/test/java/com/h/backend/voice/MiniMaxHttpTtsClientTest.java`：

```java
package com.h.backend.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.voice.config.VoiceTtsProperties;
import com.h.backend.voice.tts.MiniMaxHttpTtsClient;
import com.h.backend.voice.tts.MiniMaxTtsRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniMaxHttpTtsClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsTtsRequestAndParsesHexAudio() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/t2a_v2", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"data":{"audio":"010203"},"trace_id":"trace-1","base_resp":{"status_code":0,"status_msg":"success"}}
                    """);
        });
        server.start();

        VoiceTtsProperties properties = new VoiceTtsProperties();
        properties.getMinimax().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getMinimax().setApiKey("test-key");
        properties.getMinimax().setModel("speech-2.8-turbo");
        properties.getMinimax().setVoiceId("male-qn-qingse");
        MiniMaxHttpTtsClient client = new MiniMaxHttpTtsClient(properties, new ObjectMapper());

        var result = client.synthesize(new MiniMaxTtsRequest("你好", null));

        assertEquals("Bearer test-key", authorization.get());
        assertTrue(body.get().contains("\"model\":\"speech-2.8-turbo\""));
        assertTrue(body.get().contains("\"text\":\"你好\""));
        assertTrue(body.get().contains("\"voice_id\":\"male-qn-qingse\""));
        assertArrayEquals(new byte[]{1, 2, 3}, result.audioBytes());
        assertEquals("audio/mpeg", result.mimeType());
        assertEquals("trace-1", result.providerRequestId());
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=MiniMaxHttpTtsClientTest test
```

预期：编译失败，voice TTS 类不存在。

- [ ] **步骤 3：创建 VoiceTtsProperties**

创建 `backend/src/main/java/com/h/backend/voice/config/VoiceTtsProperties.java`：

```java
package com.h.backend.voice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voice.tts")
public class VoiceTtsProperties {

    private MiniMax minimax = new MiniMax();
    private int previewMaxTextLength = 240;
    private int messageMaxTextLength = 5000;

    public MiniMax getMinimax() {
        return minimax;
    }

    public void setMinimax(MiniMax minimax) {
        this.minimax = minimax == null ? new MiniMax() : minimax;
    }

    public int getPreviewMaxTextLength() {
        return previewMaxTextLength;
    }

    public void setPreviewMaxTextLength(int previewMaxTextLength) {
        this.previewMaxTextLength = Math.max(1, previewMaxTextLength);
    }

    public int getMessageMaxTextLength() {
        return messageMaxTextLength;
    }

    public void setMessageMaxTextLength(int messageMaxTextLength) {
        this.messageMaxTextLength = Math.max(1, messageMaxTextLength);
    }

    public static class MiniMax {
        private String baseUrl = "https://api.minimaxi.com";
        private String apiKey = "";
        private String model = "speech-2.8-turbo";
        private String voiceId = "male-qn-qingse";
        private String format = "mp3";
        private int sampleRate = 32000;
        private int bitrate = 128000;
        private int requestTimeoutSeconds = 60;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getVoiceId() { return voiceId; }
        public void setVoiceId(String voiceId) { this.voiceId = voiceId; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public int getSampleRate() { return sampleRate; }
        public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
        public int getBitrate() { return bitrate; }
        public void setBitrate(int bitrate) { this.bitrate = bitrate; }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
        }
    }
}
```

在 `BackendApplication` 所在配置扫描已经覆盖 `com.h.backend`，还需要在任一配置类上启用属性。若项目已有 `@ConfigurationPropertiesScan`，复用；否则在 `BackendApplication` 增加：

```java
@ConfigurationPropertiesScan
```

- [ ] **步骤 4：创建 TTS DTO 与接口**

创建：

```java
package com.h.backend.voice.tts;

public record MiniMaxTtsRequest(String text, String voiceId) {
}
```

```java
package com.h.backend.voice.tts;

public record MiniMaxTtsResult(
        byte[] audioBytes,
        String mimeType,
        String providerRequestId,
        String model,
        String voiceId
) {
}
```

```java
package com.h.backend.voice.tts;

public interface MiniMaxTtsClient {
    MiniMaxTtsResult synthesize(MiniMaxTtsRequest request);
}
```

- [ ] **步骤 5：实现 MiniMaxHttpTtsClient**

创建 `backend/src/main/java/com/h/backend/voice/tts/MiniMaxHttpTtsClient.java`：

```java
package com.h.backend.voice.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.voice.config.VoiceTtsProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

@Component
public class MiniMaxHttpTtsClient implements MiniMaxTtsClient {

    private static final String T2A_PATH = "/v1/t2a_v2";

    private final VoiceTtsProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public MiniMaxHttpTtsClient(VoiceTtsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getMinimax().getBaseUrl())
                .requestFactory(requestFactory(properties.getMinimax()))
                .build();
    }

    @Override
    public MiniMaxTtsResult synthesize(MiniMaxTtsRequest request) {
        VoiceTtsProperties.MiniMax minimax = properties.getMinimax();
        String voiceId = request.voiceId() == null || request.voiceId().isBlank()
                ? minimax.getVoiceId()
                : request.voiceId();
        Map<String, Object> body = Map.of(
                "model", minimax.getModel(),
                "text", request.text(),
                "stream", false,
                "voice_setting", Map.of("voice_id", voiceId),
                "audio_setting", Map.of(
                        "sample_rate", minimax.getSampleRate(),
                        "bitrate", minimax.getBitrate(),
                        "format", minimax.getFormat()
                )
        );
        String response = restClient.post()
                .uri(T2A_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + minimax.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((clientRequest, clientResponse) -> {
                    String responseBody = StreamUtils.copyToString(clientResponse.getBody(), StandardCharsets.UTF_8);
                    if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("MiniMax TTS request failed with HTTP "
                                + clientResponse.getStatusCode().value());
                    }
                    return responseBody;
                });
        return parseResponse(response, minimax.getModel(), voiceId, minimax.getFormat());
    }

    private MiniMaxTtsResult parseResponse(String response, String model, String voiceId, String format) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode baseResp = root.path("base_resp");
            int providerStatusCode = baseResp.path("status_code").asInt(0);
            if (providerStatusCode != 0) {
                throw new IllegalStateException("MiniMax TTS request failed with provider status "
                        + providerStatusCode + ": " + baseResp.path("status_msg").asText(""));
            }
            String audioHex = root.path("data").path("audio").asText("");
            if (audioHex.isBlank()) {
                throw new IllegalStateException("MiniMax TTS response did not contain audio");
            }
            return new MiniMaxTtsResult(
                    HexFormat.of().parseHex(audioHex),
                    mimeType(format),
                    root.path("trace_id").asText(null),
                    model,
                    voiceId
            );
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse MiniMax TTS response", ex);
        }
    }

    private String mimeType(String format) {
        if ("wav".equalsIgnoreCase(format)) {
            return "audio/wav";
        }
        return "audio/mpeg";
    }

    private static JdkClientHttpRequestFactory requestFactory(VoiceTtsProperties.MiniMax minimax) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(minimax.getRequestTimeoutSeconds()));
        return requestFactory;
    }
}
```

- [ ] **步骤 6：补充 application.yml 配置**

在 `backend/src/main/resources/application.yml` 增加：

```yaml
voice:
  tts:
    preview-max-text-length: 240
    message-max-text-length: 5000
    minimax:
      base-url: https://api.minimaxi.com
      api-key: ""
      model: speech-2.8-turbo
      voice-id: male-qn-qingse
      format: mp3
      sample-rate: 32000
      bitrate: 128000
      request-timeout-seconds: 60
```

- [ ] **步骤 7：运行测试验证通过**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=MiniMaxHttpTtsClientTest test
```

预期：PASS。

- [ ] **步骤 8：添加 provider 错误测试并实现**

在 `MiniMaxHttpTtsClientTest` 添加：

```java
@Test
void throwsWhenProviderReturnsErrorStatus() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/v1/t2a_v2", exchange -> respond(exchange, """
            {"base_resp":{"status_code":1001,"status_msg":"bad voice"}}
            """));
    server.start();

    VoiceTtsProperties properties = new VoiceTtsProperties();
    properties.getMinimax().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.getMinimax().setApiKey("test-key");
    MiniMaxHttpTtsClient client = new MiniMaxHttpTtsClient(properties, new ObjectMapper());

    IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> client.synthesize(new MiniMaxTtsRequest("你好", null))
    );
    assertTrue(error.getMessage().contains("1001"));
    assertTrue(error.getMessage().contains("bad voice"));
}
```

补 import：

```java
import static org.junit.jupiter.api.Assertions.assertThrows;
```

如果步骤 5 已正确实现，测试应直接通过。

- [ ] **步骤 9：运行后端编译相关测试**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=MiniMaxHttpTtsClientTest,BackendApplicationTests test
```

预期：PASS。

- [ ] **步骤 10：Commit**

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main
git add backend/src/main/java/com/h/backend/BackendApplication.java \
  backend/src/main/java/com/h/backend/voice/config/VoiceTtsProperties.java \
  backend/src/main/java/com/h/backend/voice/tts/MiniMaxTtsRequest.java \
  backend/src/main/java/com/h/backend/voice/tts/MiniMaxTtsResult.java \
  backend/src/main/java/com/h/backend/voice/tts/MiniMaxTtsClient.java \
  backend/src/main/java/com/h/backend/voice/tts/MiniMaxHttpTtsClient.java \
  backend/src/main/resources/application.yml \
  backend/src/test/java/com/h/backend/voice/MiniMaxHttpTtsClientTest.java
git commit -m "feat: add MiniMax TTS client"
```

---

## 任务 4：实现 call turn 分片上传、聚合、绑定用户录音

**文件：**
- 创建：`backend/src/main/java/com/h/backend/voice/dto/CallTurnStartRequest.java`
- 创建：`backend/src/main/java/com/h/backend/voice/dto/CallTurnStartResponse.java`
- 创建：`backend/src/main/java/com/h/backend/voice/dto/CallTurnFinalizeRequest.java`
- 创建：`backend/src/main/java/com/h/backend/voice/dto/VoiceResourceResponse.java`
- 创建：`backend/src/main/java/com/h/backend/voice/service/CallTurnService.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
- 测试：`backend/src/test/java/com/h/backend/voice/CallTurnServiceTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`

- [ ] **步骤 1：为 ChatSessionService 资源绑定写失败测试**

在 `ChatSessionServiceImplTest` 添加：

```java
@Test
void bindStoredAudioResourceRejectsNonUserMessageForRecording() {
    ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
    ChatSessionMessageMapper messageMapper = mock(ChatSessionMessageMapper.class);
    ChatMessageResourceMapper resourceMapper = mock(ChatMessageResourceMapper.class);
    ChatSessionServiceImpl service = new ChatSessionServiceImpl(
            sessionMapper,
            messageMapper,
            resourceMapper,
            mock(ChatMemorySnapshotService.class),
            mock(SystemPromptService.class),
            new ObjectMapper(),
            testAgentRegistry()
    );

    ChatSessionEntity session = new ChatSessionEntity();
    session.setId(11L);
    session.setUserId(1L);
    session.setSessionId("session-1");
    session.setStatus("ACTIVE");

    ChatSessionMessageEntity assistant = new ChatSessionMessageEntity();
    assistant.setId(202L);
    assistant.setSessionRecordId(11L);
    assistant.setSessionId("session-1");
    assistant.setUserId(1L);
    assistant.setRoleCode("assistant");
    assistant.setMessageType("AI");
    assistant.setContentText("reply");

    when(sessionMapper.selectBySessionId("session-1")).thenReturn(session);
    when(messageMapper.selectById(202L)).thenReturn(assistant);

    BusinessException error = assertThrows(BusinessException.class, () -> service.bindStoredAudioResource(
            1L,
            "session-1",
            202L,
            "USER_RECORDING",
            new StoredResource("audio-1", "LOCAL_FILE", "call-audio/audio-1.webm", "audio/webm", "call.webm", 3L, null, null),
            java.util.Map.of("source", "USER_RECORDING")
    ));

    assertEquals(40000, error.getCode());
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=ChatSessionServiceImplTest#bindStoredAudioResourceRejectsNonUserMessageForRecording test
```

预期：编译失败，`bindStoredAudioResource` 不存在。

- [ ] **步骤 3：实现 ChatSessionService.bindStoredAudioResource**

在 `ChatSessionService.java` 增加 import 和方法：

```java
import com.h.backend.chat.storage.StoredResource;
import java.util.Map;

ChatMessageResourceDto bindStoredAudioResource(
        Long userId,
        String sessionId,
        Long messageId,
        String source,
        StoredResource storedResource,
        Map<String, Object> metadata
);
```

在 `ChatSessionServiceImpl` 实现：

```java
@Override
@Transactional
public ChatMessageResourceDto bindStoredAudioResource(
        Long userId,
        String sessionId,
        Long messageId,
        String source,
        StoredResource storedResource,
        Map<String, Object> metadata
) {
    if (chatMessageResourceMapper == null) {
        throw new IllegalStateException("ChatMessageResourceMapper is required to bind audio resources");
    }
    ChatSessionEntity session = requireOwnedSession(userId, sessionId);
    ChatSessionMessageEntity message = chatSessionMessageMapper.selectById(messageId);
    if (message == null
            || !session.getId().equals(message.getSessionRecordId())
            || !sessionId.equals(message.getSessionId())
            || !userId.equals(message.getUserId())) {
        throw new BusinessException(40404, "消息不存在");
    }
    String normalizedSource = requireResourceField(source, "source").trim().toUpperCase();
    boolean userRecording = "USER_RECORDING".equals(normalizedSource);
    boolean assistantTts = "ASSISTANT_TTS".equals(normalizedSource);
    if (userRecording && !"user".equals(message.getRoleCode())) {
        throw new BusinessException(40000, "用户录音只能绑定用户消息");
    }
    if (assistantTts && (!"assistant".equals(message.getRoleCode()) || !"AI".equals(message.getMessageType()))) {
        throw new BusinessException(40000, "Assistant TTS 只能绑定 AI 回复消息");
    }
    if (!userRecording && !assistantTts) {
        throw new BusinessException(40000, "不支持的音频来源");
    }

    ChatMessageResourceEntity row = new ChatMessageResourceEntity();
    row.setId(storedResource.id());
    row.setMessageId(messageId);
    row.setUserId(userId);
    row.setSessionId(sessionId);
    row.setResourceType("AUDIO");
    row.setResourceRole("ATTACHMENT");
    row.setStorageType(storedResource.storageType());
    row.setStorageKey(storedResource.storageKey());
    row.setViewUrl(resourceViewUrl(storedResource.id()));
    row.setDownloadUrl(resourceDownloadUrl(storedResource.id()));
    row.setMimeType(storedResource.mimeType());
    row.setFileName(storedResource.fileName());
    row.setFileSize(storedResource.fileSize());
    row.setWidth(null);
    row.setHeight(null);
    row.setMetadataJson(toMetadataJson(metadata));
    row.setCreatedAt(LocalDateTime.now());
    chatMessageResourceMapper.insert(row);

    return toResourceDto(row);
}

private String resourceViewUrl(String resourceId) {
    return "/api/chat/resources/" + resourceId + "/content";
}

private String resourceDownloadUrl(String resourceId) {
    return "/api/chat/resources/" + resourceId + "/download";
}
```

并用该方法设置 URL，避免扩大构造器影响。

同步新增 `toResourceDto(ChatMessageResourceEntity row)` 私有方法，复用已有资源 DTO 映射。

- [ ] **步骤 4：运行 ChatSessionService 绑定测试**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=ChatSessionServiceImplTest#bindStoredAudioResourceRejectsNonUserMessageForRecording test
```

预期：PASS。

- [ ] **步骤 5：编写 CallTurnService 生命周期失败测试**

创建 `backend/src/test/java/com/h/backend/voice/CallTurnServiceTest.java`：

```java
package com.h.backend.voice;

import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import com.h.backend.voice.service.CallTurnService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallTurnServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void finalizesChunksIntoOneUserRecordingResource() throws Exception {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);

        when(storage.save(any(ResourceSaveCommand.class))).thenReturn(
                new StoredResource("audio-1", "LOCAL_FILE", "call-audio/audio-1.webm", "audio/webm", "call-audio.webm", 6L, null, null)
        );
        when(chatSessionService.bindStoredAudioResource(eq(1L), eq("session-1"), eq(101L), eq("USER_RECORDING"), any(), any()))
                .thenReturn(new ChatMessageResourceDto(
                        "audio-1",
                        "AUDIO",
                        "ATTACHMENT",
                        "/api/chat/resources/audio-1/content",
                        "/api/chat/resources/audio-1/download",
                        "call-audio.webm",
                        "audio/webm",
                        6L,
                        null,
                        null
                ));

        String turnId = service.start(1L, "session-1", "standard-chat").turnId();
        service.appendChunk(1L, turnId, new MockMultipartFile("chunk", "0.webm", "audio/webm", new byte[]{1, 2, 3}), 0, "audio/webm");
        service.appendChunk(1L, turnId, new MockMultipartFile("chunk", "1.webm", "audio/webm", new byte[]{4, 5, 6}), 1, "audio/webm");

        var response = service.finalizeTurn(1L, turnId, "session-1", "standard-chat", 101L, "你好");

        assertEquals("audio-1", response.resourceId());
        verify(storage).save(any(ResourceSaveCommand.class));
        verify(chatSessionService).bindStoredAudioResource(
                eq(1L),
                eq("session-1"),
                eq(101L),
                eq("USER_RECORDING"),
                any(StoredResource.class),
                eq(Map.of("source", "USER_RECORDING", "callTurnId", turnId, "transcript", "你好"))
        );
        assertTrue(java.nio.file.Files.notExists(tempDir.resolve("1").resolve(turnId)));
    }
}
```

- [ ] **步骤 6：运行 CallTurnService 测试验证失败**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=CallTurnServiceTest test
```

预期：编译失败，CallTurnService 不存在。

- [ ] **步骤 7：创建 DTO**

创建 `CallTurnStartRequest.java`：

```java
package com.h.backend.voice.dto;

public record CallTurnStartRequest(String sessionId, String agentId) {
}
```

创建 `CallTurnStartResponse.java`：

```java
package com.h.backend.voice.dto;

public record CallTurnStartResponse(String turnId) {
}
```

创建 `CallTurnFinalizeRequest.java`：

```java
package com.h.backend.voice.dto;

public record CallTurnFinalizeRequest(String sessionId, String agentId, Long messageId, String transcript) {
}
```

创建 `VoiceResourceResponse.java`：

```java
package com.h.backend.voice.dto;

public record VoiceResourceResponse(
        String resourceId,
        String viewUrl,
        String downloadUrl,
        String mimeType,
        Long durationMs
) {
}
```

- [ ] **步骤 8：实现 CallTurnService**

创建 `backend/src/main/java/com/h/backend/voice/service/CallTurnService.java`：

```java
package com.h.backend.voice.service;

import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.dto.CallTurnStartResponse;
import com.h.backend.voice.dto.VoiceResourceResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

@Service
public class CallTurnService {

    private final Path baseDir;
    private final ResourceStorage resourceStorage;
    private final ChatSessionService chatSessionService;

    public CallTurnService(
            @Value("${voice.call-turns.base-dir:/tmp/h-agent/call-turns}") String baseDir,
            ResourceStorage resourceStorage,
            ChatSessionService chatSessionService
    ) {
        this(Path.of(baseDir), resourceStorage, chatSessionService);
    }

    public CallTurnService(Path baseDir, ResourceStorage resourceStorage, ChatSessionService chatSessionService) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.resourceStorage = resourceStorage;
        this.chatSessionService = chatSessionService;
    }

    public CallTurnStartResponse start(Long userId, String sessionId, String agentId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(40000, "sessionId 不能为空");
        }
        String turnId = UUID.randomUUID().toString();
        try {
            Files.createDirectories(turnDir(userId, turnId));
            return new CallTurnStartResponse(turnId);
        } catch (IOException ex) {
            throw new IllegalStateException("创建通话录音失败", ex);
        }
    }

    public void appendChunk(Long userId, String turnId, MultipartFile chunk, int sequence, String mimeType) {
        if (sequence < 0) {
            throw new BusinessException(40000, "音频分片序号无效");
        }
        Path dir = turnDir(userId, turnId);
        if (!Files.exists(dir)) {
            throw new BusinessException(40404, "通话片段不存在");
        }
        try {
            String extension = extension(mimeType);
            Files.write(dir.resolve("chunk-%06d.%s".formatted(sequence, extension)), chunk.getBytes());
        } catch (IOException ex) {
            throw new IllegalStateException("保存通话分片失败", ex);
        }
    }

    public VoiceResourceResponse finalizeTurn(
            Long userId,
            String turnId,
            String sessionId,
            String agentId,
            Long messageId,
            String transcript
    ) {
        Path dir = turnDir(userId, turnId);
        if (!Files.exists(dir)) {
            throw new BusinessException(40404, "通话片段不存在");
        }
        try {
            byte[] audio = mergeChunks(dir);
            StoredResource stored = resourceStorage.save(new ResourceSaveCommand(
                    "AUDIO",
                    sessionId,
                    "call-user-recording",
                    audio,
                    "audio/webm",
                    "webm",
                    null,
                    null
            ));
            ChatMessageResourceDto resource = chatSessionService.bindStoredAudioResource(
                    userId,
                    sessionId,
                    messageId,
                    "USER_RECORDING",
                    stored,
                    Map.of("source", "USER_RECORDING", "callTurnId", turnId, "transcript", transcript == null ? "" : transcript)
            );
            deleteDirectory(dir);
            return new VoiceResourceResponse(resource.id(), resource.viewUrl(), resource.downloadUrl(), resource.mimeType(), null);
        } catch (IOException ex) {
            throw new IllegalStateException("聚合通话录音失败", ex);
        }
    }

    public void cancel(Long userId, String turnId) {
        try {
            deleteDirectory(turnDir(userId, turnId));
        } catch (IOException ex) {
            throw new IllegalStateException("取消通话录音失败", ex);
        }
    }

    private byte[] mergeChunks(Path dir) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var paths = Files.list(dir)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::getFileName)).toList()) {
                out.write(Files.readAllBytes(path));
            }
        }
        return out.toByteArray();
    }

    private Path turnDir(Long userId, String turnId) {
        Path dir = baseDir.resolve(String.valueOf(userId)).resolve(turnId).normalize();
        if (!dir.startsWith(baseDir)) {
            throw new BusinessException(40000, "turnId 无效");
        }
        return dir;
    }

    private String extension(String mimeType) {
        if ("audio/mpeg".equalsIgnoreCase(mimeType)) return "mp3";
        if ("audio/wav".equalsIgnoreCase(mimeType)) return "wav";
        return "webm";
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
```

- [ ] **步骤 9：运行 CallTurnService 测试验证通过**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=CallTurnServiceTest,ChatSessionServiceImplTest#bindStoredAudioResourceRejectsNonUserMessageForRecording test
```

预期：PASS。

- [ ] **步骤 10：Commit**

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main
git add backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java \
  backend/src/main/java/com/h/backend/voice/dto/CallTurnStartRequest.java \
  backend/src/main/java/com/h/backend/voice/dto/CallTurnStartResponse.java \
  backend/src/main/java/com/h/backend/voice/dto/CallTurnFinalizeRequest.java \
  backend/src/main/java/com/h/backend/voice/dto/VoiceResourceResponse.java \
  backend/src/main/java/com/h/backend/voice/service/CallTurnService.java \
  backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java \
  backend/src/test/java/com/h/backend/voice/CallTurnServiceTest.java
git commit -m "feat: persist call turn recordings"
```

---

## 任务 5：实现 VoiceTtsService 与 voice controller

**文件：**
- 创建：`backend/src/main/java/com/h/backend/voice/dto/TtsPreviewRequest.java`
- 创建：`backend/src/main/java/com/h/backend/voice/dto/TtsMessageRequest.java`
- 创建：`backend/src/main/java/com/h/backend/voice/service/VoiceTtsService.java`
- 创建：`backend/src/main/java/com/h/backend/voice/controller/VoiceController.java`
- 测试：`backend/src/test/java/com/h/backend/voice/VoiceTtsServiceTest.java`
- 测试：`backend/src/test/java/com/h/backend/voice/VoiceControllerTest.java`

- [ ] **步骤 1：编写 VoiceTtsService 失败测试**

创建 `backend/src/test/java/com/h/backend/voice/VoiceTtsServiceTest.java`：

```java
package com.h.backend.voice;

import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import com.h.backend.voice.config.VoiceTtsProperties;
import com.h.backend.voice.service.VoiceTtsService;
import com.h.backend.voice.tts.MiniMaxTtsClient;
import com.h.backend.voice.tts.MiniMaxTtsRequest;
import com.h.backend.voice.tts.MiniMaxTtsResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceTtsServiceTest {

    @Test
    void previewReturnsAudioBytesWithoutPersisting() {
        MiniMaxTtsClient client = mock(MiniMaxTtsClient.class);
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        VoiceTtsService service = new VoiceTtsService(new VoiceTtsProperties(), client, storage, chatSessionService);

        when(client.synthesize(new MiniMaxTtsRequest("你好", null)))
                .thenReturn(new MiniMaxTtsResult(new byte[]{1, 2, 3}, "audio/mpeg", "trace-1", "speech-2.8-turbo", "voice-1"));

        var result = service.preview(1L, "session-1", "standard-chat", "你好");

        assertArrayEquals(new byte[]{1, 2, 3}, result.audioBytes());
        assertEquals("audio/mpeg", result.mimeType());
    }

    @Test
    void messageTtsReadsAssistantMessageAndBindsAudioResource() {
        MiniMaxTtsClient client = mock(MiniMaxTtsClient.class);
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        VoiceTtsService service = new VoiceTtsService(new VoiceTtsProperties(), client, storage, chatSessionService);
        ChatSessionMessageDto assistant = new ChatSessionMessageDto(
                "202", "assistant", "AI", "完整回复", null, List.of(), LocalDateTime.now()
        );
        StoredResource stored = new StoredResource("audio-tts", "LOCAL_FILE", "call-audio/audio-tts.mp3", "audio/mpeg", "call-audio.mp3", 3L, null, null);

        when(chatSessionService.getOwnedMessage(1L, "session-1", 202L)).thenReturn(assistant);
        when(client.synthesize(new MiniMaxTtsRequest("完整回复", null)))
                .thenReturn(new MiniMaxTtsResult(new byte[]{1, 2, 3}, "audio/mpeg", "trace-1", "speech-2.8-turbo", "voice-1"));
        when(storage.save(any(ResourceSaveCommand.class))).thenReturn(stored);
        when(chatSessionService.bindStoredAudioResource(eq(1L), eq("session-1"), eq(202L), eq("ASSISTANT_TTS"), any(), any()))
                .thenReturn(new ChatMessageResourceDto(
                        "audio-tts",
                        "AUDIO",
                        "ATTACHMENT",
                        "/api/chat/resources/audio-tts/content",
                        "/api/chat/resources/audio-tts/download",
                        "call-audio.mp3",
                        "audio/mpeg",
                        3L,
                        null,
                        null
                ));

        var response = service.messageTts(1L, "session-1", "standard-chat", 202L);

        assertEquals("audio-tts", response.resourceId());
        verify(chatSessionService).bindStoredAudioResource(
                eq(1L),
                eq("session-1"),
                eq(202L),
                eq("ASSISTANT_TTS"),
                eq(stored),
                eq(Map.of("source", "ASSISTANT_TTS", "voiceId", "voice-1", "model", "speech-2.8-turbo"))
        );
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=VoiceTtsServiceTest test
```

预期：编译失败，VoiceTtsService/TTS DTO 不存在。

- [ ] **步骤 3：创建 TTS 请求 DTO**

创建 `TtsPreviewRequest.java`：

```java
package com.h.backend.voice.dto;

public record TtsPreviewRequest(String sessionId, String agentId, String text) {
}
```

创建 `TtsMessageRequest.java`：

```java
package com.h.backend.voice.dto;

public record TtsMessageRequest(String sessionId, String agentId, Long messageId) {
}
```

- [ ] **步骤 4：实现 VoiceTtsService**

创建 `backend/src/main/java/com/h/backend/voice/service/VoiceTtsService.java`：

```java
package com.h.backend.voice.service;

import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.config.VoiceTtsProperties;
import com.h.backend.voice.dto.VoiceResourceResponse;
import com.h.backend.voice.tts.MiniMaxTtsClient;
import com.h.backend.voice.tts.MiniMaxTtsRequest;
import com.h.backend.voice.tts.MiniMaxTtsResult;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class VoiceTtsService {

    private final VoiceTtsProperties properties;
    private final MiniMaxTtsClient ttsClient;
    private final ResourceStorage resourceStorage;
    private final ChatSessionService chatSessionService;

    public VoiceTtsService(
            VoiceTtsProperties properties,
            MiniMaxTtsClient ttsClient,
            ResourceStorage resourceStorage,
            ChatSessionService chatSessionService
    ) {
        this.properties = properties;
        this.ttsClient = ttsClient;
        this.resourceStorage = resourceStorage;
        this.chatSessionService = chatSessionService;
    }

    public PreviewAudio preview(Long userId, String sessionId, String agentId, String text) {
        validateText(text, properties.getPreviewMaxTextLength());
        MiniMaxTtsResult result = ttsClient.synthesize(new MiniMaxTtsRequest(text.trim(), null));
        return new PreviewAudio(result.audioBytes(), result.mimeType());
    }

    public VoiceResourceResponse messageTts(Long userId, String sessionId, String agentId, Long messageId) {
        ChatSessionMessageDto message = chatSessionService.getOwnedMessage(userId, sessionId, messageId);
        if (!"assistant".equals(message.role()) || !"AI".equals(message.messageType())) {
            throw new BusinessException(40000, "Assistant TTS 只能绑定 AI 回复消息");
        }
        validateText(message.content(), properties.getMessageMaxTextLength());
        MiniMaxTtsResult result = ttsClient.synthesize(new MiniMaxTtsRequest(message.content().trim(), null));
        StoredResource stored = resourceStorage.save(new ResourceSaveCommand(
                "AUDIO",
                sessionId,
                "call-assistant-tts",
                result.audioBytes(),
                result.mimeType(),
                extension(result.mimeType()),
                null,
                null
        ));
        ChatMessageResourceDto resource = chatSessionService.bindStoredAudioResource(
                userId,
                sessionId,
                messageId,
                "ASSISTANT_TTS",
                stored,
                Map.of("source", "ASSISTANT_TTS", "voiceId", result.voiceId(), "model", result.model())
        );
        return new VoiceResourceResponse(resource.id(), resource.viewUrl(), resource.downloadUrl(), resource.mimeType(), null);
    }

    private void validateText(String text, int maxLength) {
        if (text == null || text.trim().isEmpty()) {
            throw new BusinessException(40000, "语音合成文本不能为空");
        }
        if (text.length() > maxLength) {
            throw new BusinessException(40000, "语音合成文本过长");
        }
    }

    private String extension(String mimeType) {
        if ("audio/wav".equalsIgnoreCase(mimeType)) return "wav";
        return "mp3";
    }

    public record PreviewAudio(byte[] audioBytes, String mimeType) {
    }
}
```

- [ ] **步骤 5：运行 VoiceTtsService 测试验证通过**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=VoiceTtsServiceTest test
```

预期：PASS。

- [ ] **步骤 6：编写 VoiceController 失败测试**

创建 `backend/src/test/java/com/h/backend/voice/VoiceControllerTest.java`：

```java
package com.h.backend.voice;

import com.h.backend.security.AuthUserPrincipal;
import com.h.backend.voice.controller.VoiceController;
import com.h.backend.voice.dto.CallTurnFinalizeRequest;
import com.h.backend.voice.dto.CallTurnStartRequest;
import com.h.backend.voice.dto.CallTurnStartResponse;
import com.h.backend.voice.dto.TtsMessageRequest;
import com.h.backend.voice.dto.TtsPreviewRequest;
import com.h.backend.voice.dto.VoiceResourceResponse;
import com.h.backend.voice.service.CallTurnService;
import com.h.backend.voice.service.VoiceTtsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceControllerTest {

    @Test
    void previewReturnsAudioResponse() {
        CallTurnService callTurnService = mock(CallTurnService.class);
        VoiceTtsService ttsService = mock(VoiceTtsService.class);
        VoiceController controller = new VoiceController(callTurnService, ttsService);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");

        when(ttsService.preview(1L, "session-1", "standard-chat", "你好"))
                .thenReturn(new VoiceTtsService.PreviewAudio(new byte[]{1, 2, 3}, "audio/mpeg"));

        var response = controller.preview(principal, new TtsPreviewRequest("session-1", "standard-chat", "你好"));

        assertEquals(MediaType.parseMediaType("audio/mpeg"), response.getHeaders().getContentType());
        assertArrayEquals(new byte[]{1, 2, 3}, response.getBody());
    }

    @Test
    void delegatesCallTurnLifecycle() throws Exception {
        CallTurnService callTurnService = mock(CallTurnService.class);
        VoiceTtsService ttsService = mock(VoiceTtsService.class);
        VoiceController controller = new VoiceController(callTurnService, ttsService);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");

        when(callTurnService.start(1L, "session-1", "standard-chat"))
                .thenReturn(new CallTurnStartResponse("turn-1"));
        when(callTurnService.finalizeTurn(1L, "turn-1", "session-1", "standard-chat", 101L, "你好"))
                .thenReturn(new VoiceResourceResponse("audio-1", "/content", "/download", "audio/webm", null));

        assertEquals("turn-1", controller.startTurn(principal, new CallTurnStartRequest("session-1", "standard-chat")).data().turnId());
        controller.uploadChunk(principal, "turn-1", new MockMultipartFile("chunk", "0.webm", "audio/webm", new byte[]{1}), 0, "audio/webm");
        assertEquals("audio-1", controller.finalizeTurn(principal, "turn-1",
                new CallTurnFinalizeRequest("session-1", "standard-chat", 101L, "你好")).data().resourceId());
        controller.cancelTurn(principal, "turn-1");

        verify(callTurnService).appendChunk(eq(1L), eq("turn-1"), any(MultipartFile.class), eq(0), eq("audio/webm"));
        verify(callTurnService).cancel(1L, "turn-1");
    }
}
```

- [ ] **步骤 7：运行 Controller 测试验证失败**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=VoiceControllerTest test
```

预期：编译失败，VoiceController 不存在。

- [ ] **步骤 8：实现 VoiceController**

创建 `backend/src/main/java/com/h/backend/voice/controller/VoiceController.java`：

```java
package com.h.backend.voice.controller;

import com.h.backend.common.api.ApiResponse;
import com.h.backend.security.AuthUserPrincipal;
import com.h.backend.voice.dto.CallTurnFinalizeRequest;
import com.h.backend.voice.dto.CallTurnStartRequest;
import com.h.backend.voice.dto.CallTurnStartResponse;
import com.h.backend.voice.dto.TtsMessageRequest;
import com.h.backend.voice.dto.TtsPreviewRequest;
import com.h.backend.voice.dto.VoiceResourceResponse;
import com.h.backend.voice.service.CallTurnService;
import com.h.backend.voice.service.VoiceTtsService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final CallTurnService callTurnService;
    private final VoiceTtsService voiceTtsService;

    public VoiceController(CallTurnService callTurnService, VoiceTtsService voiceTtsService) {
        this.callTurnService = callTurnService;
        this.voiceTtsService = voiceTtsService;
    }

    @PostMapping("/call-turns/start")
    public ApiResponse<CallTurnStartResponse> startTurn(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestBody CallTurnStartRequest request
    ) {
        return ApiResponse.ok(callTurnService.start(principal.userId(), request.sessionId(), request.agentId()));
    }

    @PostMapping(value = "/call-turns/{turnId}/chunks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Void> uploadChunk(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String turnId,
            @RequestParam MultipartFile chunk,
            @RequestParam int sequence,
            @RequestParam String mimeType
    ) {
        callTurnService.appendChunk(principal.userId(), turnId, chunk, sequence, mimeType);
        return ApiResponse.ok(null);
    }

    @PostMapping("/call-turns/{turnId}/finalize")
    public ApiResponse<VoiceResourceResponse> finalizeTurn(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String turnId,
            @RequestBody CallTurnFinalizeRequest request
    ) {
        return ApiResponse.ok(callTurnService.finalizeTurn(
                principal.userId(),
                turnId,
                request.sessionId(),
                request.agentId(),
                request.messageId(),
                request.transcript()
        ));
    }

    @PostMapping("/call-turns/{turnId}/cancel")
    public ApiResponse<Void> cancelTurn(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String turnId
    ) {
        callTurnService.cancel(principal.userId(), turnId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/tts/preview")
    public ResponseEntity<byte[]> preview(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestBody TtsPreviewRequest request
    ) {
        VoiceTtsService.PreviewAudio audio = voiceTtsService.preview(
                principal.userId(),
                request.sessionId(),
                request.agentId(),
                request.text()
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(audio.mimeType()))
                .body(audio.audioBytes());
    }

    @PostMapping("/tts/message")
    public ApiResponse<VoiceResourceResponse> messageTts(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestBody TtsMessageRequest request
    ) {
        return ApiResponse.ok(voiceTtsService.messageTts(
                principal.userId(),
                request.sessionId(),
                request.agentId(),
                request.messageId()
        ));
    }
}
```

- [ ] **步骤 9：运行 voice 测试**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=VoiceTtsServiceTest,VoiceControllerTest,CallTurnServiceTest,MiniMaxHttpTtsClientTest test
```

预期：PASS。

- [ ] **步骤 10：Commit**

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main
git add backend/src/main/java/com/h/backend/voice/dto/TtsPreviewRequest.java \
  backend/src/main/java/com/h/backend/voice/dto/TtsMessageRequest.java \
  backend/src/main/java/com/h/backend/voice/service/VoiceTtsService.java \
  backend/src/main/java/com/h/backend/voice/controller/VoiceController.java \
  backend/src/test/java/com/h/backend/voice/VoiceTtsServiceTest.java \
  backend/src/test/java/com/h/backend/voice/VoiceControllerTest.java
git commit -m "feat: expose voice call endpoints"
```

---

## 任务 6：前端 stream 类型扩展与 voice API 封装

**文件：**
- 修改：`frontend/lib/http.ts`
- 创建：`frontend/lib/voice.ts`
- 测试：`frontend/lib/http.test.mjs`
- 测试：`frontend/lib/voice.test.mjs`

- [ ] **步骤 1：为 apiStream 写失败测试，分发 user_message 和 done.message**

在 `frontend/lib/http.test.mjs` 添加：

```js
test("apiStream dispatches user_message and done message payloads", async () => {
  const originalFetch = globalThis.fetch;
  const userMessage = {
    id: "101",
    role: "user",
    messageType: "USER",
    content: "你好",
    resources: [],
    createdAt: "2026-06-27T10:00:00",
  };
  const assistantMessage = {
    id: "202",
    role: "assistant",
    messageType: "AI",
    content: "你好呀",
    resources: [],
    createdAt: "2026-06-27T10:00:01",
  };
  const events = [];

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              "event: user_message\n" +
                `data: ${JSON.stringify({ type: "user_message", content: "", message: userMessage })}\n\n` +
                "event: done\n" +
                `data: ${JSON.stringify({ type: "done", content: "", message: assistantMessage })}\n\n`,
            ),
          );
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );

  try {
    await apiStream("/api/chat/messages/stream", { method: "POST" }, {
      onChunk() {},
      onUserMessage(message) {
        events.push(["user", message]);
      },
      onDone(_content, message) {
        events.push(["done", message]);
      },
    });
    assert.deepEqual(events, [
      ["user", userMessage],
      ["done", assistantMessage],
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm test -- lib/http.test.mjs
```

预期：FAIL 或编译错误，`onUserMessage` 不在 handler 类型中，`onDone` 收不到 message。

- [ ] **步骤 3：扩展 apiStream handler 类型与事件分发**

在 `frontend/lib/http.ts` handler 类型中增加：

```ts
onUserMessage?: (message: ChatSessionMessage) => void;
onDone?: (value: string, message?: ChatSessionMessage) => void;
```

将原 `onDone?: (value: string) => void;` 替换为新签名。

在事件分发中增加：

```ts
} else if (eventType === "user_message" && payload.message) {
  handlers.onUserMessage?.(payload.message);
} else if (eventType === "done") {
  handlers.onDone?.(payload.content, payload.message);
}
```

确保旧调用 `onDone() {}` 仍能工作。

- [ ] **步骤 4：运行 http 测试**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm test -- lib/http.test.mjs
```

预期：PASS。

- [ ] **步骤 5：编写 voice API 失败测试**

创建 `frontend/lib/voice.test.mjs`：

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  cancelCallTurn,
  finalizeCallTurn,
  messageTts,
  previewTts,
  startCallTurn,
  uploadCallTurnChunk,
} from "./voice.ts";

test("startCallTurn posts session and agent", async () => {
  const originalFetch = globalThis.fetch;
  let captured;
  globalThis.fetch = async (path, init) => {
    captured = { path, init };
    return new Response(JSON.stringify({ code: 0, message: "ok", data: { turnId: "turn-1" } }), { status: 200 });
  };
  try {
    const result = await startCallTurn("session-1", "standard-chat");
    assert.equal(result.turnId, "turn-1");
    assert.equal(captured.path, "/api/voice/call-turns/start");
    assert.equal(JSON.parse(captured.init.body).sessionId, "session-1");
    assert.equal(captured.init.credentials, "include");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("previewTts returns audio blob", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(new Uint8Array([1, 2, 3]), {
    status: 200,
    headers: { "Content-Type": "audio/mpeg" },
  });
  try {
    const blob = await previewTts("session-1", "standard-chat", "你好");
    assert.equal(blob.type, "audio/mpeg");
    assert.equal(blob.size, 3);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("uploadCallTurnChunk sends multipart form data", async () => {
  const originalFetch = globalThis.fetch;
  let captured;
  globalThis.fetch = async (path, init) => {
    captured = { path, init };
    return new Response(JSON.stringify({ code: 0, message: "ok", data: null }), { status: 200 });
  };
  try {
    await uploadCallTurnChunk("turn-1", new Blob(["a"], { type: "audio/webm" }), 2, "audio/webm");
    assert.equal(captured.path, "/api/voice/call-turns/turn-1/chunks");
    assert.equal(captured.init.method, "POST");
    assert.equal(captured.init.body instanceof FormData, true);
    assert.equal(captured.init.credentials, "include");
  } finally {
    globalThis.fetch = originalFetch;
  }
});
```

- [ ] **步骤 6：运行 voice 测试验证失败**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm test -- lib/voice.test.mjs
```

预期：模块不存在。

- [ ] **步骤 7：实现 frontend/lib/voice.ts**

创建 `frontend/lib/voice.ts`：

```ts
import { apiFetch, apiFormFetch } from "./http";

export type CallTurnStartResponse = {
  turnId: string;
};

export type VoiceResourceResponse = {
  resourceId: string;
  viewUrl: string;
  downloadUrl: string;
  mimeType: string;
  durationMs: number | null;
};

export function startCallTurn(sessionId: string, agentId: string) {
  return apiFetch<CallTurnStartResponse>("/api/voice/call-turns/start", {
    method: "POST",
    body: JSON.stringify({ sessionId, agentId }),
  });
}

export function uploadCallTurnChunk(turnId: string, chunk: Blob, sequence: number, mimeType: string) {
  const form = new FormData();
  form.append("chunk", chunk, `chunk-${sequence}.webm`);
  form.append("sequence", String(sequence));
  form.append("mimeType", mimeType);
  return apiFormFetch<void>(`/api/voice/call-turns/${encodeURIComponent(turnId)}/chunks`, form);
}

export function finalizeCallTurn(input: {
  turnId: string;
  sessionId: string;
  agentId: string;
  messageId: string;
  transcript: string;
}) {
  return apiFetch<VoiceResourceResponse>(`/api/voice/call-turns/${encodeURIComponent(input.turnId)}/finalize`, {
    method: "POST",
    body: JSON.stringify({
      sessionId: input.sessionId,
      agentId: input.agentId,
      messageId: Number(input.messageId),
      transcript: input.transcript,
    }),
  });
}

export function cancelCallTurn(turnId: string) {
  return apiFetch<void>(`/api/voice/call-turns/${encodeURIComponent(turnId)}/cancel`, {
    method: "POST",
  });
}

export async function previewTts(sessionId: string, agentId: string, text: string) {
  const response = await fetch("/api/voice/tts/preview", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionId, agentId, text }),
  });
  if (!response.ok) {
    throw new Error("语音合成失败");
  }
  return response.blob();
}

export function messageTts(sessionId: string, agentId: string, messageId: string) {
  return apiFetch<VoiceResourceResponse>("/api/voice/tts/message", {
    method: "POST",
    body: JSON.stringify({ sessionId, agentId, messageId: Number(messageId) }),
  });
}
```

- [ ] **步骤 8：运行前端 API 测试**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm test -- lib/http.test.mjs lib/voice.test.mjs
```

预期：PASS。

- [ ] **步骤 9：Commit**

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main
git add frontend/lib/http.ts frontend/lib/http.test.mjs frontend/lib/voice.ts frontend/lib/voice.test.mjs
git commit -m "feat: add frontend voice APIs"
```

---

## 任务 7：实现电话页纯状态工具

**文件：**
- 创建：`frontend/lib/call-state.ts`
- 测试：`frontend/lib/call-state.test.mjs`

- [ ] **步骤 1：编写静默检测和分句测试**

创建 `frontend/lib/call-state.test.mjs`：

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  appendTranscript,
  buildCallHref,
  buildChatHrefFromCall,
  createAudioQueue,
  segmentAssistantText,
  shouldCommitUtterance,
} from "./call-state.ts";

test("shouldCommitUtterance returns true after three seconds without transcript changes", () => {
  assert.equal(shouldCommitUtterance({
    transcript: "你好",
    lastTranscriptAt: 1000,
    now: 3999,
    silenceMs: 3000,
  }), false);
  assert.equal(shouldCommitUtterance({
    transcript: "你好",
    lastTranscriptAt: 1000,
    now: 4000,
    silenceMs: 3000,
  }), true);
});

test("appendTranscript refreshes timestamp only when text changes", () => {
  const first = appendTranscript({ transcript: "", lastTranscriptAt: 0 }, "你好", 100);
  const second = appendTranscript(first, "你好", 200);
  assert.deepEqual(first, { transcript: "你好", lastTranscriptAt: 100 });
  assert.deepEqual(second, { transcript: "你好", lastTranscriptAt: 100 });
});

test("segmentAssistantText emits completed Chinese sentences and keeps remainder", () => {
  const result = segmentAssistantText("你好。我正在查询", "");
  assert.deepEqual(result.segments, ["你好。"]);
  assert.equal(result.remainder, "我正在查询");
});

test("audio queue clear removes pending items and active flag", () => {
  const queue = createAudioQueue();
  const withItems = queue.enqueue("a").enqueue("b").startCurrent();
  const cleared = withItems.clear();
  assert.deepEqual(cleared.items, []);
  assert.equal(cleared.playing, false);
});

test("call and chat href builders preserve agent and session", () => {
  assert.equal(buildCallHref("standard-chat", "session-1"), "/call?agentId=standard-chat&sessionId=session-1");
  assert.equal(buildChatHrefFromCall("car-rental-assistant", "session-2"), "/chat?agentId=car-rental-assistant&sessionId=session-2");
});
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm test -- lib/call-state.test.mjs
```

预期：模块不存在。

- [ ] **步骤 3：实现 call-state.ts**

创建 `frontend/lib/call-state.ts`：

```ts
export type TranscriptState = {
  transcript: string;
  lastTranscriptAt: number;
};

export function appendTranscript(state: TranscriptState, nextTranscript: string, now: number): TranscriptState {
  const normalized = nextTranscript.trim();
  if (normalized === state.transcript) {
    return state;
  }
  return { transcript: normalized, lastTranscriptAt: now };
}

export function shouldCommitUtterance(input: {
  transcript: string;
  lastTranscriptAt: number;
  now: number;
  silenceMs?: number;
}) {
  const silenceMs = input.silenceMs ?? 3000;
  return input.transcript.trim().length > 0 && input.now - input.lastTranscriptAt >= silenceMs;
}

export function segmentAssistantText(text: string, previousRemainder: string) {
  const combined = `${previousRemainder}${text}`;
  const segments: string[] = [];
  let start = 0;
  const pattern = /[。！？!?]\s*/g;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(combined)) !== null) {
    const end = match.index + match[0].length;
    const sentence = combined.slice(start, end).trim();
    if (sentence) {
      segments.push(sentence);
    }
    start = end;
  }
  let remainder = combined.slice(start);
  if (remainder.length >= 80) {
    segments.push(remainder);
    remainder = "";
  }
  return { segments, remainder };
}

export type AudioQueueState = {
  items: string[];
  playing: boolean;
  enqueue: (url: string) => AudioQueueState;
  startCurrent: () => AudioQueueState;
  finishCurrent: () => AudioQueueState;
  clear: () => AudioQueueState;
};

export function createAudioQueue(items: string[] = [], playing = false): AudioQueueState {
  return {
    items,
    playing,
    enqueue(url: string) {
      return createAudioQueue([...items, url], playing);
    },
    startCurrent() {
      return createAudioQueue(items, items.length > 0);
    },
    finishCurrent() {
      return createAudioQueue(items.slice(1), false);
    },
    clear() {
      return createAudioQueue([], false);
    },
  };
}

export function buildCallHref(agentId: string, sessionId: string) {
  const search = new URLSearchParams({ agentId, sessionId });
  return `/call?${search.toString()}`;
}

export function buildChatHrefFromCall(agentId: string, sessionId: string) {
  const search = new URLSearchParams({ agentId, sessionId });
  return `/chat?${search.toString()}`;
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm test -- lib/call-state.test.mjs
```

预期：PASS。

- [ ] **步骤 5：Commit**

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main
git add frontend/lib/call-state.ts frontend/lib/call-state.test.mjs
git commit -m "feat: add call state helpers"
```

---

## 任务 8：文字聊天页支持 sessionId 恢复、电话按钮、音频资源同泡展示

**文件：**
- 修改：`frontend/lib/chat-message-state.ts`
- 修改：`frontend/lib/chat-message-state.test.mjs`
- 修改：`frontend/app/chat/page.tsx`

- [ ] **步骤 1：写失败测试，assistant turn 保留 resources**

在 `frontend/lib/chat-message-state.test.mjs` 添加：

```js
test("toRenderableTurns keeps assistant audio resources with assistant answer", () => {
  const turns = toRenderableTurns([
    {
      id: "assistant-1",
      role: "assistant",
      messageType: "AI",
      content: "你好呀",
      resources: [
        {
          id: "audio-1",
          type: "AUDIO",
          role: "ATTACHMENT",
          viewUrl: "/api/chat/resources/audio-1/content",
          downloadUrl: "/api/chat/resources/audio-1/download",
          fileName: "reply.mp3",
          mimeType: "audio/mpeg",
          fileSize: 3,
          width: null,
          height: null,
        },
      ],
      createdAt: "",
    },
  ]);

  assert.equal(turns[0].kind, "assistant");
  assert.equal(turns[0].resources.length, 1);
  assert.equal(turns[0].resources[0].mimeType, "audio/mpeg");
});
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm test -- lib/chat-message-state.test.mjs
```

预期：FAIL，assistant renderable turn 没有 `resources` 字段。

- [ ] **步骤 3：扩展 RenderableTurn assistant/blocked 类型携带 resources**

在 `frontend/lib/chat-message-state.ts` 中：

```ts
| {
    kind: "assistant";
    id: string;
    reasoning: string | null;
    answer: string;
    blocked: null;
    agentSteps: UiAgentStep[];
    resources: ChatMessageResource[];
  }
```

blocked 也加：

```ts
resources: ChatMessageResource[];
```

所有构造 assistant/blocked turn 的地方补：

```ts
resources: next.resources ?? []
```

或对应当前消息变量。

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm test -- lib/chat-message-state.test.mjs
```

预期：PASS。若既有 deepEqual 测试失败，给预期对象补 `resources: []`。

- [ ] **步骤 5：修改 chat 页面渲染 assistant 音频资源**

在 `frontend/app/chat/page.tsx` 的 assistant 消息渲染区域，找到使用 `AssistantMessageContent` 的位置。在 assistant 文本后追加：

```tsx
{turn.resources && turn.resources.length > 0 ? (
  <div className="mt-3">
    <MediaContent content={turn.answer} resources={turn.resources} />
  </div>
) : null}
```

用户消息已经应通过 `turn.resources` 渲染；若当前只显示文本，给 user bubble 同样添加：

```tsx
{turn.resources && turn.resources.length > 0 ? (
  <div className="mt-3">
    <MediaContent content={turn.content} resources={turn.resources} />
  </div>
) : null}
```

- [ ] **步骤 6：支持 `/chat?sessionId=...` 优先恢复会话**

在 `ChatPageContent` 中读取：

```ts
const requestedSessionId = searchParams.get("sessionId");
```

在 bootstrap 成功后、处理 `requestedAgentId` 前，插入：

```ts
if (requestedSessionId) {
  const currentSessionId = bootstrap.session?.session.sessionId ?? bootstrap.candidates[0]?.sessionId ?? null;
  const requested = await activateHistorySession(requestedSessionId, currentSessionId);
  hydrateSession(requested, defaultPrompt?.id ?? null);
  return;
}
```

确保 `useEffect` dependency 包含 `requestedSessionId`。

- [ ] **步骤 7：增加电话按钮**

导入：

```ts
import { buildCallHref } from "@/lib/call-state";
```

在当前会话标题/工具区域增加按钮。使用现有样式体系，按钮文字为“电话”。点击：

```tsx
onClick={() => {
  if (!sessionId || streaming) return;
  router.push(buildCallHref(currentAgentId, sessionId));
}}
```

如果更适合用 Link：

```tsx
<Link href={sessionId ? buildCallHref(currentAgentId, sessionId) : "/chat"}>电话</Link>
```

禁用无 session 或 streaming 状态。

- [ ] **步骤 8：运行前端测试和 lint**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm test -- lib/chat-message-state.test.mjs lib/call-state.test.mjs
npm run lint
```

预期：PASS。

- [ ] **步骤 9：Commit**

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main
git add frontend/lib/chat-message-state.ts frontend/lib/chat-message-state.test.mjs frontend/app/chat/page.tsx
git commit -m "feat: link chat and call sessions"
```

---

## 任务 9：实现 `/call` 页面基础流程

**文件：**
- 创建：`frontend/app/call/page.tsx`
- 修改：`frontend/lib/chat-message-state.ts`
- 修改：`frontend/lib/http.ts`

- [ ] **步骤 1：创建 `/call` 页面骨架**

创建 `frontend/app/call/page.tsx`，先包含认证、session hydrate、挂断跳转，不接麦克风：

```tsx
"use client";

import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { apiStream } from "@/lib/http";
import { getCurrentUser } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import { buildChatHrefFromCall, segmentAssistantText } from "@/lib/call-state";
import { buildChatSendPayload, buildNewSessionPayload, STANDARD_AGENT_ID } from "@/lib/chat-agent-mode";
import { createChatSession, getChatSession, activateHistorySession, type ChatSessionOpen } from "@/lib/chat-sessions";
import { cancelCallTurn, finalizeCallTurn, messageTts, previewTts, startCallTurn, uploadCallTurnChunk } from "@/lib/voice";

function CallPageContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const requestedAgentId = searchParams.get("agentId") ?? STANDARD_AGENT_ID;
  const requestedSessionId = searchParams.get("sessionId");
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(requestedSessionId);
  const [agentId, setAgentId] = useState(requestedAgentId);
  const [agentName, setAgentName] = useState("电话");
  const [status, setStatus] = useState("准备通话");
  const [transcript, setTranscript] = useState("");
  const [assistantText, setAssistantText] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    getCurrentUser()
      .then(async () => {
        setAuthenticated(true);
        if (requestedSessionId) {
          const meta = await getChatSession(requestedSessionId);
          setSessionId(meta.sessionId);
          setAgentId(meta.agentId || requestedAgentId);
          setAgentName(meta.agentDisplayName || "电话");
          return;
        }
        const created = await createChatSession(buildNewSessionPayload({
          currentSessionId: null,
          targetAgentId: requestedAgentId,
          promptId: null,
        }));
        hydrate(created);
      })
      .catch(() => {
        savePostLoginRedirect("/call");
        router.replace("/auth/login");
      });
  }, [requestedAgentId, requestedSessionId, router]);

  function hydrate(open: ChatSessionOpen) {
    setSessionId(open.session.sessionId);
    setAgentId(open.session.agentId || STANDARD_AGENT_ID);
    setAgentName(open.session.agentDisplayName || "电话");
  }

  function hangUp() {
    if (!sessionId) {
      router.replace("/chat");
      return;
    }
    router.replace(buildChatHrefFromCall(agentId, sessionId));
  }

  if (authenticated !== true) {
    return <main className="min-h-screen bg-[#f7f4ea]" />;
  }

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] text-stone-900">
      <section className="mx-auto flex min-h-screen w-full max-w-md flex-col px-4 py-5">
        <header className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs font-medium text-amber-700">{status}</p>
            <h1 className="truncate text-xl font-semibold">{agentName}</h1>
          </div>
          <button className="rounded-lg border border-stone-200 bg-white px-3 py-2 text-sm" onClick={hangUp}>
            挂断
          </button>
        </header>

        <div className="flex flex-1 flex-col justify-center gap-5">
          <div className="rounded-lg border border-stone-200 bg-white/85 p-4">
            <p className="text-xs text-stone-500">你正在说</p>
            <p className="mt-2 min-h-16 whitespace-pre-wrap text-lg leading-8">{transcript || "点击开始后说话"}</p>
          </div>
          <div className="rounded-lg border border-stone-200 bg-white/85 p-4">
            <p className="text-xs text-stone-500">Agent</p>
            <p className="mt-2 min-h-24 whitespace-pre-wrap text-base leading-7">{assistantText || "等待回复"}</p>
          </div>
          {error ? <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p> : null}
        </div>

        <div className="grid grid-cols-2 gap-3">
          <button className="h-12 rounded-lg bg-stone-900 text-sm font-semibold text-white" type="button">
            开始
          </button>
          <button className="h-12 rounded-lg bg-red-600 text-sm font-semibold text-white" type="button" onClick={hangUp}>
            挂断
          </button>
        </div>
      </section>
    </main>
  );
}

export default function CallPage() {
  return (
    <Suspense fallback={<main className="min-h-screen bg-[#f7f4ea]" />}>
      <CallPageContent />
    </Suspense>
  );
}
```

- [ ] **步骤 2：运行 lint 验证骨架**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm run lint
```

预期：PASS。若有未使用 import，删除骨架里暂未使用的 import，后续步骤再加。

- [ ] **步骤 3：接入 SpeechRecognition 与 3 秒静默**

在 `CallPageContent` 中添加 refs：

```ts
const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
const silenceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
const transcriptRef = useRef("");
```

添加类型声明：

```ts
type SpeechRecognitionAlternativeLike = { transcript: string };
type SpeechRecognitionResultLike = { 0?: SpeechRecognitionAlternativeLike };
type SpeechRecognitionEventLike = { results: ArrayLike<SpeechRecognitionResultLike> };
type SpeechRecognitionLike = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onerror: (() => void) | null;
  start: () => void;
  stop: () => void;
};
type SpeechRecognitionConstructor = new () => SpeechRecognitionLike;

declare global {
  interface Window {
    SpeechRecognition?: SpeechRecognitionConstructor;
    webkitSpeechRecognition?: SpeechRecognitionConstructor;
  }
}
```

添加函数：

```ts
function resetSilenceTimer() {
  if (silenceTimerRef.current) {
    clearTimeout(silenceTimerRef.current);
  }
  silenceTimerRef.current = setTimeout(() => {
    const finalText = transcriptRef.current.trim();
    if (finalText) {
      void submitUtterance(finalText);
    }
  }, 3000);
}

function startListening() {
  const Recognition = window.SpeechRecognition ?? window.webkitSpeechRecognition;
  if (!Recognition) {
    setError("当前浏览器不支持语音识别，请使用 Chrome 或 Edge。");
    return;
  }
  const recognition = new Recognition();
  recognition.lang = "zh-CN";
  recognition.continuous = true;
  recognition.interimResults = true;
  recognition.onresult = (event) => {
    let text = "";
    for (let index = 0; index < event.results.length; index += 1) {
      text += event.results[index][0]?.transcript ?? "";
    }
    transcriptRef.current = text;
    setTranscript(text);
    setStatus("正在听你说");
    resetSilenceTimer();
  };
  recognition.onerror = () => setError("语音识别失败，请重试。");
  recognition.start();
  recognitionRef.current = recognition;
  setStatus("正在听你说");
}
```

添加空的 `submitUtterance`：

```ts
async function submitUtterance(text: string) {
  setStatus("正在发送");
  setTranscript(text);
}
```

将“开始”按钮 onClick 改为 `startListening`。

- [ ] **步骤 4：接入 MediaRecorder 分片上传**

添加 refs：

```ts
const mediaRecorderRef = useRef<MediaRecorder | null>(null);
const currentTurnIdRef = useRef<string | null>(null);
const chunkSequenceRef = useRef(0);
```

新增：

```ts
async function startRecordingTurn() {
  if (!sessionId) return;
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  const turn = await startCallTurn(sessionId, agentId);
  currentTurnIdRef.current = turn.turnId;
  chunkSequenceRef.current = 0;
  const recorder = new MediaRecorder(stream, { mimeType: "audio/webm" });
  recorder.ondataavailable = (event) => {
    if (!event.data.size || !currentTurnIdRef.current) return;
    const sequence = chunkSequenceRef.current;
    chunkSequenceRef.current += 1;
    void uploadCallTurnChunk(currentTurnIdRef.current, event.data, sequence, event.data.type || "audio/webm")
      .catch(() => setError("本轮录音保存失败，文字对话会继续。"));
  };
  recorder.start(500);
  mediaRecorderRef.current = recorder;
}
```

在 `startListening` 中先 `void startRecordingTurn()`。

在 `submitUtterance` 开头停止 recorder：

```ts
mediaRecorderRef.current?.stop();
mediaRecorderRef.current?.stream.getTracks().forEach((track) => track.stop());
mediaRecorderRef.current = null;
recognitionRef.current?.stop();
recognitionRef.current = null;
```

- [ ] **步骤 5：接入 chat stream、user_message finalize、preview TTS、message TTS**

添加状态/refs：

```ts
const streamingRef = useRef(false);
const pendingUtteranceRef = useRef<string | null>(null);
const assistantRemainderRef = useRef("");
const audioRef = useRef<HTMLAudioElement | null>(null);
const audioQueueRef = useRef<string[]>([]);
```

实现播放：

```ts
function enqueueAudio(blob: Blob) {
  const url = URL.createObjectURL(blob);
  audioQueueRef.current.push(url);
  void playNextAudio();
}

async function playNextAudio() {
  if (audioRef.current || audioQueueRef.current.length === 0) return;
  const url = audioQueueRef.current.shift()!;
  const audio = new Audio(url);
  audioRef.current = audio;
  audio.onended = () => {
    URL.revokeObjectURL(url);
    audioRef.current = null;
    void playNextAudio();
  };
  audio.onerror = () => {
    URL.revokeObjectURL(url);
    audioRef.current = null;
    void playNextAudio();
  };
  setStatus("Agent 正在说话");
  await audio.play();
}

function stopPlayback() {
  audioRef.current?.pause();
  audioRef.current = null;
  for (const url of audioQueueRef.current) {
    URL.revokeObjectURL(url);
  }
  audioQueueRef.current = [];
}
```

在 `startListening` 开头调用 `stopPlayback()`，实现插话停止播报。

实现 `submitUtterance`：

```ts
async function submitUtterance(text: string) {
  if (!sessionId) return;
  if (streamingRef.current) {
    pendingUtteranceRef.current = text;
    setStatus("等待上一轮回复结束");
    return;
  }
  streamingRef.current = true;
  setStatus("Agent 正在回复");
  setAssistantText("");
  let assistantMessageId: string | null = null;
  const turnId = currentTurnIdRef.current;
  currentTurnIdRef.current = null;

  try {
    await apiStream("/api/chat/messages/stream", {
      method: "POST",
      body: JSON.stringify(buildChatSendPayload({
        message: text,
        sessionId,
        promptId: null,
        agentId,
      })),
    }, {
      onUserMessage(message) {
        if (turnId) {
          void finalizeCallTurn({ turnId, sessionId, agentId, messageId: message.id, transcript: text })
            .catch(() => setError("本轮录音保存失败，文字对话已保留。"));
        }
      },
      onChunk(chunk) {
        setAssistantText((current) => `${current}${chunk}`);
        const segmented = segmentAssistantText(chunk, assistantRemainderRef.current);
        assistantRemainderRef.current = segmented.remainder;
        for (const segment of segmented.segments) {
          void previewTts(sessionId, agentId, segment).then(enqueueAudio).catch(() => {});
        }
      },
      onAgentStep(step) {
        setStatus(`正在执行：${step.nodeName}`);
      },
      onDone(_content, message) {
        assistantMessageId = message?.id ?? null;
      },
      onBlocked(message) {
        setAssistantText(message);
      },
      onError(message) {
        setError(message);
      },
    });
    if (assistantMessageId) {
      void messageTts(sessionId, agentId, assistantMessageId).catch(() => setError("回复语音保存失败，文字对话已保留。"));
    }
  } finally {
    streamingRef.current = false;
    assistantRemainderRef.current = "";
    setStatus("正在听你说");
    if (pendingUtteranceRef.current) {
      const next = pendingUtteranceRef.current;
      pendingUtteranceRef.current = null;
      void submitUtterance(next);
    }
  }
}
```

- [ ] **步骤 6：挂断清理 turn 和播放**

修改 `hangUp`：

```ts
function hangUp() {
  recognitionRef.current?.stop();
  mediaRecorderRef.current?.stop();
  mediaRecorderRef.current?.stream.getTracks().forEach((track) => track.stop());
  if (currentTurnIdRef.current) {
    void cancelCallTurn(currentTurnIdRef.current);
    currentTurnIdRef.current = null;
  }
  stopPlayback();
  if (!sessionId) {
    router.replace("/chat");
    return;
  }
  router.replace(buildChatHrefFromCall(agentId, sessionId));
}
```

- [ ] **步骤 7：跑 lint 并手动检查类型**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm run lint
npm test -- lib/http.test.mjs lib/voice.test.mjs lib/call-state.test.mjs
```

预期：PASS。页面已经定义本地最小 `SpeechRecognitionLike` 类型，不依赖 TypeScript DOM 是否内置 `SpeechRecognition`。

- [ ] **步骤 8：Commit**

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main
git add frontend/app/call/page.tsx
git commit -m "feat: add agent call page"
```

---

## 任务 10：端到端验证与收尾

**文件：**
- 修改：根据前面任务的修复需要，限定在已触碰的 backend/frontend 文件。

- [ ] **步骤 1：运行后端 voice 与 chat 全量相关测试**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn -Dtest=ChatServiceImplTest,ChatSessionServiceImplTest,ChatControllerTest,ChatResourceControllerTest,MiniMaxHttpTtsClientTest,CallTurnServiceTest,VoiceTtsServiceTest,VoiceControllerTest test
```

预期：PASS。

- [ ] **步骤 2：运行前端测试与 lint**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm test -- lib/*.test.mjs
npm run lint
```

预期：PASS。

- [ ] **步骤 3：运行构建**

运行：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm run build
```

预期：PASS。

- [ ] **步骤 4：人工浏览器验证**

启动服务：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/backend
mvn spring-boot:run
```

另一个终端：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main/frontend
npm run dev
```

在 Chrome 或 Edge 中验证：

1. 登录后进入 `/chat`。
2. 点击电话按钮进入 `/call?agentId=...&sessionId=...`。
3. 授权麦克风。
4. 说一句话，停顿 3 秒后自动发送。
5. 看到 agent 字幕流式展示并听到 preview TTS。
6. agent 播报时再次说话，确认当前音频停止，页面进入听用户说话状态。
7. 挂断后回到 `/chat?agentId=...&sessionId=...`。
8. 文字历史中用户消息显示文本和用户录音 `<audio controls>`。
9. assistant 消息显示完整文本和一个完整 TTS `<audio controls>`。

- [ ] **步骤 5：记录验证结果**

在最终回复中记录：

```text
后端测试：mvn -Dtest=... test PASS
前端测试：npm test -- lib/*.test.mjs PASS
前端 lint：npm run lint PASS
前端 build：npm run build PASS
浏览器验证：Chrome/Edge 手动验证 PASS 或列出未验证原因
```

- [ ] **步骤 6：Commit 最终修复**

如果步骤 1-4 产生修复：

```bash
source ~/.profile && cd /Users/huajiang/Desktop/h-agent/.worktrees/main
git status --short
git add backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java \
  backend/src/main/java/com/h/backend/chat/agent/HAssistantStreamingExecutor.java \
  backend/src/main/java/com/h/backend/chat/agent/AgenticSyncExecutor.java \
  backend/src/main/java/com/h/backend/chat/storage/LocalFileResourceStorage.java \
  backend/src/main/java/com/h/backend/voice \
  backend/src/main/resources/application.yml \
  backend/src/test/java/com/h/backend/chat \
  backend/src/test/java/com/h/backend/voice \
  frontend/lib \
  frontend/app/chat/page.tsx \
  frontend/app/call/page.tsx
git commit -m "fix: stabilize agent call flow"
```

如果没有修复，不创建空提交。
