# Chat 文生图实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为现有聊天系统增加文生图能力，支持 `/image ...` 显式命令和 `langchain4j` 工具自动触发，将生成图片落盘到本地服务器，并在聊天框中以独立图片消息展示和下载。

**架构：** 后端新增 MiniMax 图片客户端、资源存储抽象、本地文件实现、图片资源表和图片消息装配层，两条入口最终统一调用 `ImageGenerationService`。现有 SSE 聊天流新增 `image` 事件，前端将消息升级为富消息联合类型，并以图片卡片渲染历史与实时生成结果。

**技术栈：** Spring Boot 3.4、MyBatis-Plus、Flyway、langchain4j 1.15、Reactor Flux、Next.js 16、React 19、TypeScript、Node test、JUnit 5、Mockito

---

## 文件结构

### 后端数据库与实体

- 创建：`backend/src/main/resources/db/migration/V8__create_chat_message_resources.sql`
  - 新增聊天资源表，持久化图片资源元数据与访问地址。
- 创建：`backend/src/main/java/com/h/backend/chat/entity/ChatMessageResourceEntity.java`
  - `chat_message_resources` 的 MyBatis 实体。
- 创建：`backend/src/main/java/com/h/backend/chat/mapper/ChatMessageResourceMapper.java`
  - 图片资源表读写 mapper。

### 后端 DTO 与模型

- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatStreamEvent.java`
  - 为 SSE 新增可选 `message` 负载，支持 `image` 事件。
- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatSessionMessageDto.java`
  - 从纯文本 DTO 升级为富消息 DTO，新增 `messageType`、`payload`、`resources`。
- 创建：`backend/src/main/java/com/h/backend/chat/dto/ChatMessagePayloadDto.java`
  - 图片消息轻量元数据 DTO。
- 创建：`backend/src/main/java/com/h/backend/chat/dto/ChatMessageResourceDto.java`
  - 前端消费的资源 DTO，包含 `viewUrl` 与 `downloadUrl`。
- 创建：`backend/src/main/java/com/h/backend/chat/model/ChatMessagePayload.java`
  - 后端内部消息 payload 模型。
- 修改：`backend/src/main/java/com/h/backend/chat/model/ChatSessionMessage.java`
  - 增加 `messageType` 与 `payload`，让历史与实时消息共用一套结构。

### 后端配置与基础设施

- 创建：`backend/src/main/java/com/h/backend/chat/config/ImageGenerationProperties.java`
  - 持有 `image-generation.*` 下的 MiniMax 与本地存储配置。
- 修改：`backend/src/main/java/com/h/backend/chat/config/ChatModelConfig.java`
  - 注册图片工具并接入图片事件桥。
- 修改：`backend/src/main/resources/application.yml`
  - 添加图片模型与本地存储配置项。
- 创建：`backend/src/main/java/com/h/backend/chat/storage/ResourceStorage.java`
  - 资源存储抽象。
- 创建：`backend/src/main/java/com/h/backend/chat/storage/LocalFileResourceStorage.java`
  - 本地文件存储实现。
- 创建：`backend/src/main/java/com/h/backend/chat/storage/ResourceSaveCommand.java`
  - 保存资源时的命令对象。
- 创建：`backend/src/main/java/com/h/backend/chat/storage/StoredResource.java`
  - 资源保存结果对象。
- 创建：`backend/src/main/java/com/h/backend/chat/storage/ResourceContent.java`
  - 打开资源内容时的返回对象。

### 后端图片生成与资源访问

- 创建：`backend/src/main/java/com/h/backend/chat/image/MiniMaxImageClient.java`
  - MiniMax 文生图客户端接口。
- 创建：`backend/src/main/java/com/h/backend/chat/image/MiniMaxHttpImageClient.java`
  - 基于 Spring `RestClient` 或 `WebClient` 的 MiniMax 调用实现。
- 创建：`backend/src/main/java/com/h/backend/chat/image/MiniMaxImageGenerationRequest.java`
  - MiniMax 请求 DTO。
- 创建：`backend/src/main/java/com/h/backend/chat/image/MiniMaxImageGenerationResult.java`
  - MiniMax 标准化返回 DTO。
- 创建：`backend/src/main/java/com/h/backend/chat/service/ImageGenerationService.java`
  - 图片生成服务接口。
- 创建：`backend/src/main/java/com/h/backend/chat/service/impl/ImageGenerationServiceImpl.java`
  - 统一处理图片生成、落盘、资源写库、消息写库。
- 创建：`backend/src/main/java/com/h/backend/chat/service/ChatResourceService.java`
  - 资源访问服务接口。
- 创建：`backend/src/main/java/com/h/backend/chat/service/impl/ChatResourceServiceImpl.java`
  - 鉴权、读取资源、生成下载响应头。
- 创建：`backend/src/main/java/com/h/backend/chat/controller/ChatResourceController.java`
  - 资源预览和下载接口。
- 创建：`backend/src/main/java/com/h/backend/chat/service/ChatStreamEventBridge.java`
  - 将工具内生成的图片消息桥接进当前 SSE 流。
- 创建：`backend/src/main/java/com/h/backend/chat/tools/ImageGenerationTool.java`
  - `langchain4j` 图片生成工具。
- 创建：`backend/src/test/java/com/h/backend/chat/ImageGenerationToolTest.java`
  - 覆盖图片工具经由事件桥发布 `image` 事件。

### 后端聊天主链路

- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java`
  - 增加写入图片消息与查询消息资源的接口。
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
  - 持久化 `IMAGE` 消息，加载消息资源并组装 DTO。
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatService.java`
  - 保持现有 Flux SSE 接口，增加图片命令链路支持。
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
  - 识别 `/image`，发出 `image` 事件，并接入 tool 图片事件桥。
- 修改：`backend/src/main/java/com/h/backend/chat/ai/HAssistant.java`
  - 更新系统提示或工具暴露入口。

### 前端

- 修改：`frontend/lib/http.ts`
  - 增加 `onImage` 事件解析。
- 修改：`frontend/lib/http.test.mjs`
  - 覆盖 `image` 事件解析。
- 修改：`frontend/lib/chat-sessions.ts`
  - 升级消息 DTO 为富消息结构。
- 修改：`frontend/app/chat/page.tsx`
  - 支持图片占位态、图片消息、历史回放、下载按钮。

### 测试

- 修改：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`
  - 覆盖图片消息持久化与历史 DTO 装配。
- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
  - 覆盖 `/image` 命令路径。
- 创建：`backend/src/test/java/com/h/backend/chat/ImageGenerationServiceImplTest.java`
  - 覆盖 MiniMax 结果落盘、资源写库、消息写库。
- 创建：`backend/src/test/java/com/h/backend/chat/ImageGenerationToolTest.java`
  - 覆盖 tool 图片事件桥。
- 创建：`backend/src/test/java/com/h/backend/chat/ChatResourceControllerTest.java`
  - 覆盖资源预览、下载与权限校验。
- 修改：`backend/src/test/java/com/h/backend/chat/config/ChatModelConfigTest.java`
  - 覆盖图片工具已注册。

## 任务 1：建资源表并升级消息 DTO 为富消息

**文件：**
- 创建：`backend/src/main/resources/db/migration/V8__create_chat_message_resources.sql`
- 创建：`backend/src/main/java/com/h/backend/chat/entity/ChatMessageResourceEntity.java`
- 创建：`backend/src/main/java/com/h/backend/chat/mapper/ChatMessageResourceMapper.java`
- 修改：`backend/src/main/java/com/h/backend/chat/model/ChatSessionMessage.java`
- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatSessionMessageDto.java`
- 创建：`backend/src/main/java/com/h/backend/chat/dto/ChatMessagePayloadDto.java`
- 创建：`backend/src/main/java/com/h/backend/chat/dto/ChatMessageResourceDto.java`
- 创建：`backend/src/main/java/com/h/backend/chat/model/ChatMessagePayload.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`

- [ ] **步骤 1：先为图片消息 DTO 形状编写失败测试**

在 `backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java` 新增测试，先锁定历史消息接口需要返回的图片消息结构：

```java
@Test
void shouldMapImageMessageWithResourceMetadata() throws Exception {
    ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
    ChatSessionMessageMapper chatSessionMessageMapper = mock(ChatSessionMessageMapper.class);
    ChatMessageResourceMapper chatMessageResourceMapper = mock(ChatMessageResourceMapper.class);
    ChatMemorySnapshotService chatMemorySnapshotService = mock(ChatMemorySnapshotService.class);
    SystemPromptService systemPromptService = mock(SystemPromptService.class);
    ObjectMapper objectMapper = new ObjectMapper();
    ChatSessionServiceImpl service = new ChatSessionServiceImpl(
            chatSessionMapper,
            chatSessionMessageMapper,
            chatMessageResourceMapper,
            chatMemorySnapshotService,
            systemPromptService,
            objectMapper
    );

    ChatSessionEntity session = new ChatSessionEntity();
    session.setId(11L);
    session.setUserId(1L);
    session.setSessionId("session-1");
    session.setPromptId(22L);
    session.setTitle("图片会话");
    session.setStatus("ACTIVE");
    session.setMessageCount(2);
    session.setCreatedAt(LocalDateTime.now());
    session.setUpdatedAt(LocalDateTime.now());

    ChatSessionMessageEntity imageRow = new ChatSessionMessageEntity();
    imageRow.setId(501L);
    imageRow.setSessionRecordId(11L);
    imageRow.setSessionId("session-1");
    imageRow.setUserId(1L);
    imageRow.setSequenceNo(2);
    imageRow.setMessageType("IMAGE");
    imageRow.setRoleCode("assistant");
    imageRow.setContentText("一只白猫");
    imageRow.setPayloadJson("""
            {"prompt":"一只白猫","provider":"MINIMAX","model":"image-01","aspectRatio":"1:1","status":"READY"}
            """);
    imageRow.setCreatedAt(LocalDateTime.now());

    ChatMessageResourceEntity resourceRow = new ChatMessageResourceEntity();
    resourceRow.setId("resource-701");
    resourceRow.setMessageId(501L);
    resourceRow.setUserId(1L);
    resourceRow.setSessionId("session-1");
    resourceRow.setResourceKind("IMAGE");
    resourceRow.setStorageType("LOCAL_FILE");
    resourceRow.setStorageKey("generated-images/2026/05/27/cat.png");
    resourceRow.setViewUrl("/api/chat/resources/resource-701/content");
    resourceRow.setDownloadUrl("/api/chat/resources/resource-701/download");
    resourceRow.setMimeType("image/png");
    resourceRow.setFileName("generated-cat.png");
    resourceRow.setFileSize(1234L);
    resourceRow.setWidth(1024);
    resourceRow.setHeight(1024);
    resourceRow.setSha256("abc");
    resourceRow.setCreatedAt(LocalDateTime.now());

    when(chatSessionMapper.selectList(any())).thenReturn(List.of());
    when(chatSessionMapper.selectBySessionId("session-1")).thenReturn(session);
    when(chatSessionMessageMapper.selectPageBySessionRecordId(11L, 20, null)).thenReturn(List.of(imageRow));
    when(chatMessageResourceMapper.selectByMessageIds(List.of(501L))).thenReturn(List.of(resourceRow));

    ChatSessionMessagesPageDto page = service.getSessionMessages(1L, "session-1", 20, null);

    ChatSessionMessageDto dto = page.messages().getFirst();
    assertEquals("assistant", dto.role());
    assertEquals("IMAGE", dto.messageType());
    assertEquals("一只白猫", dto.content());
    assertEquals("MINIMAX", dto.payload().provider());
    assertEquals(1, dto.resources().size());
    assertEquals("/api/chat/resources/resource-701/content", dto.resources().getFirst().viewUrl());
    assertEquals("/api/chat/resources/resource-701/download", dto.resources().getFirst().downloadUrl());
}
```

- [ ] **步骤 2：运行测试确认 DTO 与 mapper 尚不存在**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatSessionServiceImplTest test
```

预期：

- 编译失败
- 错误包含 `ChatMessageResourceMapper`、`messageType()`、`payload()`、`resources()` 不存在

- [ ] **步骤 3：创建 migration、实体、mapper 和富消息 DTO**

新建 `backend/src/main/resources/db/migration/V8__create_chat_message_resources.sql`：

```sql
CREATE TABLE IF NOT EXISTS chat_message_resources (
    id VARCHAR(64) PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    resource_kind VARCHAR(32) NOT NULL,
    storage_type VARCHAR(32) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    view_url VARCHAR(512) NOT NULL,
    download_url VARCHAR(512) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_size BIGINT,
    width INTEGER,
    height INTEGER,
    sha256 VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_chat_message_resources_message_id
        FOREIGN KEY (message_id) REFERENCES chat_session_messages(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_chat_message_resources_message_id
    ON chat_message_resources(message_id);

CREATE INDEX IF NOT EXISTS idx_chat_message_resources_session_id
    ON chat_message_resources(session_id, created_at DESC);
```

新建 `backend/src/main/java/com/h/backend/chat/dto/ChatMessagePayloadDto.java`：

```java
package com.h.backend.chat.dto;

public record ChatMessagePayloadDto(
        String prompt,
        String provider,
        String providerRequestId,
        String model,
        String aspectRatio,
        String status,
        String triggerSource
) {
}
```

新建 `backend/src/main/java/com/h/backend/chat/dto/ChatMessageResourceDto.java`：

```java
package com.h.backend.chat.dto;

public record ChatMessageResourceDto(
        String id,
        String kind,
        String viewUrl,
        String downloadUrl,
        String fileName,
        String mimeType,
        Long fileSize,
        Integer width,
        Integer height
) {
}
```

将 `backend/src/main/java/com/h/backend/chat/dto/ChatSessionMessageDto.java` 改为：

```java
public record ChatSessionMessageDto(
        String id,
        String role,
        String messageType,
        String content,
        ChatMessagePayloadDto payload,
        List<ChatMessageResourceDto> resources,
        LocalDateTime createdAt
) {
}
```

将 `backend/src/main/java/com/h/backend/chat/model/ChatSessionMessage.java` 增加字段：

```java
private String messageType;
private ChatMessagePayload payload;
```

- [ ] **步骤 4：运行测试确认模型层编译通过，但 service 尚未组装资源**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatSessionServiceImplTest test
```

预期：

- 编译通过
- 测试失败在 `ChatSessionServiceImpl` 构造器或 `toMessageDto(...)` 尚未支持资源装配

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/resources/db/migration/V8__create_chat_message_resources.sql \
  backend/src/main/java/com/h/backend/chat/entity/ChatMessageResourceEntity.java \
  backend/src/main/java/com/h/backend/chat/mapper/ChatMessageResourceMapper.java \
  backend/src/main/java/com/h/backend/chat/model/ChatSessionMessage.java \
  backend/src/main/java/com/h/backend/chat/model/ChatMessagePayload.java \
  backend/src/main/java/com/h/backend/chat/dto/ChatSessionMessageDto.java \
  backend/src/main/java/com/h/backend/chat/dto/ChatMessagePayloadDto.java \
  backend/src/main/java/com/h/backend/chat/dto/ChatMessageResourceDto.java \
  backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java
git commit -m "feat: add chat image resource schema"
```

## 任务 2：实现本地资源存储与图片消息持久化

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/config/ImageGenerationProperties.java`
- 修改：`backend/src/main/resources/application.yml`
- 创建：`backend/src/main/java/com/h/backend/chat/storage/ResourceStorage.java`
- 创建：`backend/src/main/java/com/h/backend/chat/storage/ResourceSaveCommand.java`
- 创建：`backend/src/main/java/com/h/backend/chat/storage/StoredResource.java`
- 创建：`backend/src/main/java/com/h/backend/chat/storage/ResourceContent.java`
- 创建：`backend/src/main/java/com/h/backend/chat/storage/LocalFileResourceStorage.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`

- [ ] **步骤 1：先为图片消息写库和资源表写入编写失败测试**

在 `ChatSessionServiceImplTest` 中新增：

```java
@Test
void shouldPersistImageMessageAndResourceRows() throws Exception {
    ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
    ChatSessionMessageMapper chatSessionMessageMapper = mock(ChatSessionMessageMapper.class);
    ChatMessageResourceMapper chatMessageResourceMapper = mock(ChatMessageResourceMapper.class);
    ChatMemorySnapshotService chatMemorySnapshotService = mock(ChatMemorySnapshotService.class);
    SystemPromptService systemPromptService = mock(SystemPromptService.class);
    ObjectMapper objectMapper = new ObjectMapper();
    ChatSessionServiceImpl service = new ChatSessionServiceImpl(
            chatSessionMapper,
            chatSessionMessageMapper,
            chatMessageResourceMapper,
            chatMemorySnapshotService,
            systemPromptService,
            objectMapper
    );

    ChatSessionEntity session = new ChatSessionEntity();
    session.setId(11L);
    session.setUserId(1L);
    session.setSessionId("session-1");
    session.setPromptId(22L);
    session.setTitle("图片会话");
    session.setStatus("ACTIVE");
    session.setMessageCount(1);
    session.setCreatedAt(LocalDateTime.now());
    session.setUpdatedAt(LocalDateTime.now());

    when(chatSessionMapper.selectBySessionId("session-1")).thenReturn(session);
    doAnswer(invocation -> {
        ChatSessionMessageEntity row = invocation.getArgument(0);
        row.setId(501L);
        return 1;
    }).when(chatSessionMessageMapper).insert(any(ChatSessionMessageEntity.class));

    ChatMessagePayload payload = new ChatMessagePayload();
    payload.setPrompt("一只白猫");
    payload.setProvider("MINIMAX");
    payload.setModel("image-01");
    payload.setAspectRatio("1:1");
    payload.setStatus("READY");
    payload.setTriggerSource("COMMAND");

    ChatMessageResourceDto resource = new ChatMessageResourceDto(
            "resource-701",
            "IMAGE",
            "/api/chat/resources/resource-701/content",
            "/api/chat/resources/resource-701/download",
            "generated-cat.png",
            "image/png",
            1234L,
            1024,
            1024
    );

    ChatSessionMessageDto message = service.appendImageMessage(1L, "session-1", "一只白猫", payload, List.of(resource));

    assertEquals("IMAGE", message.messageType());
    assertEquals("一只白猫", message.content());
    assertEquals("/api/chat/resources/resource-701/download", message.resources().getFirst().downloadUrl());
    verify(chatSessionMessageMapper).insert(any(ChatSessionMessageEntity.class));
    verify(chatMessageResourceMapper).insert(any(ChatMessageResourceEntity.class));
}
```

- [ ] **步骤 2：运行测试确认图片消息接口与存储对象尚不存在**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatSessionServiceImplTest test
```

预期：

- 编译失败
- `appendImageMessage(...)`、`ChatMessagePayload`、`ChatMessageResourceEntity` 等符号缺失

- [ ] **步骤 3：实现配置、存储抽象和图片消息持久化入口**

新建 `backend/src/main/java/com/h/backend/chat/config/ImageGenerationProperties.java`：

```java
package com.h.backend.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "image-generation")
public record ImageGenerationProperties(
        MiniMax minimax,
        LocalStorage storage
) {

    public record MiniMax(
            String baseUrl,
            String apiKey,
            String model,
            String aspectRatio,
            boolean promptOptimizer
    ) {
    }

    public record LocalStorage(
            String baseDir,
            String publicBaseUrl
    ) {
    }
}
```

在 `backend/src/main/resources/application.yml` 增加：

```yaml
image-generation:
  minimax:
    base-url: https://api.minimaxi.chat
    api-key: ""
    model: image-01
    aspect-ratio: "1:1"
    prompt-optimizer: true
  storage:
    base-dir: /tmp/h-agent
    public-base-url: ""
```

为 `ChatSessionService` 新增：

```java
ChatSessionMessageDto appendImageMessage(
        Long userId,
        String sessionId,
        String imagePrompt,
        ChatMessagePayload payload,
        List<ChatMessageResourceDto> resources
);
```

在 `ChatSessionServiceImpl` 中新增 `appendImageMessage(...)`，核心写法：

```java
ChatSessionMessage message = buildMessage("assistant", imagePrompt, now, nextSequence);
message.setMessageType("IMAGE");
message.setPayload(payload);
Long messageId = persistMessage(session, message);

for (ChatMessageResourceDto resource : resources) {
    ChatMessageResourceEntity row = new ChatMessageResourceEntity();
    row.setId(resource.id());
    row.setMessageId(messageId);
    row.setUserId(userId);
    row.setSessionId(sessionId);
    row.setResourceKind(resource.kind());
    row.setStorageType("LOCAL_FILE");
    row.setStorageKey(resource.id());
    row.setViewUrl(resource.viewUrl());
    row.setDownloadUrl(resource.downloadUrl());
    row.setMimeType(resource.mimeType());
    row.setFileName(resource.fileName());
    row.setFileSize(resource.fileSize());
    row.setWidth(resource.width());
    row.setHeight(resource.height());
    chatMessageResourceMapper.insert(row);
}

return new ChatSessionMessageDto(
        String.valueOf(messageId),
        "assistant",
        "IMAGE",
        imagePrompt,
        toPayloadDto(payload),
        resources,
        now
);
```

- [ ] **步骤 4：运行测试确认图片消息写库逻辑通过**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatSessionServiceImplTest test
```

预期：

- `ChatSessionServiceImplTest` PASS

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/config/ImageGenerationProperties.java \
  backend/src/main/resources/application.yml \
  backend/src/main/java/com/h/backend/chat/storage/ResourceStorage.java \
  backend/src/main/java/com/h/backend/chat/storage/ResourceSaveCommand.java \
  backend/src/main/java/com/h/backend/chat/storage/StoredResource.java \
  backend/src/main/java/com/h/backend/chat/storage/ResourceContent.java \
  backend/src/main/java/com/h/backend/chat/storage/LocalFileResourceStorage.java \
  backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java \
  backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java
git commit -m "feat: persist chat image messages"
```

## 任务 3：接入 MiniMax 客户端和统一图片生成服务

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/image/MiniMaxImageClient.java`
- 创建：`backend/src/main/java/com/h/backend/chat/image/MiniMaxHttpImageClient.java`
- 创建：`backend/src/main/java/com/h/backend/chat/image/MiniMaxImageGenerationRequest.java`
- 创建：`backend/src/main/java/com/h/backend/chat/image/MiniMaxImageGenerationResult.java`
- 创建：`backend/src/main/java/com/h/backend/chat/service/ImageGenerationService.java`
- 创建：`backend/src/main/java/com/h/backend/chat/service/impl/ImageGenerationServiceImpl.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ImageGenerationServiceImplTest.java`

- [ ] **步骤 1：先为图片生成服务编写失败测试**

创建 `backend/src/test/java/com/h/backend/chat/ImageGenerationServiceImplTest.java`：

```java
package com.h.backend.chat;

import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.image.MiniMaxImageClient;
import com.h.backend.chat.image.MiniMaxImageGenerationResult;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.ImageGenerationService;
import com.h.backend.chat.service.impl.ImageGenerationServiceImpl;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageGenerationServiceImplTest {

    @Test
    void shouldGenerateImageStoreResourceAndAppendImageMessage() {
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                resourceStorage,
                chatSessionService
        );

        byte[] pngBytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+yF9kAAAAASUVORK5CYII=");
        when(miniMaxImageClient.generate(any())).thenReturn(new MiniMaxImageGenerationResult(
                "req-1",
                pngBytes,
                "image/png",
                "image-01",
                "{\"id\":\"req-1\"}"
        ));
        when(resourceStorage.save(any(ResourceSaveCommand.class))).thenReturn(new StoredResource(
                "LOCAL_FILE",
                "generated-images/2026/05/27/cat.png",
                "image/png",
                "generated-cat.png",
                68L,
                1024,
                1024,
                "sha256"
        ));
        when(chatSessionService.appendImageMessage(any(), any(), any(), any(), any())).thenReturn(
                new ChatSessionMessageDto(
                        "501",
                        "assistant",
                        "IMAGE",
                        "一只白猫",
                        new ChatMessagePayloadDto("一只白猫", "MINIMAX", "req-1", "image-01", "1:1", "READY", "COMMAND"),
                        List.of(new ChatMessageResourceDto(
                                "resource-701",
                                "IMAGE",
                                "/api/chat/resources/resource-701/content",
                                "/api/chat/resources/resource-701/download",
                                "generated-cat.png",
                                "image/png",
                                68L,
                                1024,
                                1024
                        )),
                        java.time.LocalDateTime.now()
                )
        );

        var result = service.generateImage(new ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "一只白猫",
                "COMMAND",
                "user-1"
        ));

        assertEquals("IMAGE", result.message().messageType());
        assertEquals(1, result.message().resources().size());
        ChatMessageResourceDto resource = result.message().resources().getFirst();
        assertEquals("/api/chat/resources/resource-701/content", resource.viewUrl());
        assertEquals("/api/chat/resources/resource-701/download", resource.downloadUrl());
        verify(chatSessionService).appendImageMessage(any(), any(), any(), any(), any());
    }
}
```

- [ ] **步骤 2：运行测试确认服务与命令对象尚不存在**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ImageGenerationServiceImplTest test
```

预期：

- 编译失败
- `ImageGenerationServiceImpl`、`ImageGenerationCommand`、`MiniMaxImageClient` 等缺失

- [ ] **步骤 3：实现图片客户端接口和统一服务**

创建 `ImageGenerationCommand` 与 `ImageGenerationResult`：

```java
public record ImageGenerationCommand(
        Long userId,
        String sessionId,
        Long promptId,
        String prompt,
        String triggerSource,
        String requestedByMessage
) {
}

public record ImageGenerationResult(
        ChatSessionMessageDto message
) {
}
```

`ImageGenerationServiceImpl.generateImage(...)` 的核心流程：

```java
MiniMaxImageGenerationResult providerResult = miniMaxImageClient.generate(
        new MiniMaxImageGenerationRequest(command.prompt())
);
StoredResource stored = resourceStorage.save(new ResourceSaveCommand(
        command.sessionId(),
        providerResult.imageBytes(),
        providerResult.mimeType(),
        "generated-" + System.currentTimeMillis() + ".png"
));

ChatMessagePayload payload = new ChatMessagePayload();
payload.setPrompt(command.prompt());
payload.setProvider("MINIMAX");
payload.setProviderRequestId(providerResult.providerRequestId());
payload.setModel(providerResult.model());
payload.setAspectRatio("1:1");
payload.setStatus("READY");
payload.setTriggerSource(command.triggerSource());

String resourceId = UUID.randomUUID().toString();
ChatMessageResourceDto resource = new ChatMessageResourceDto(
        resourceId,
        "IMAGE",
        resourceStorage.buildViewUrl(resourceId),
        resourceStorage.buildDownloadUrl(resourceId),
        stored.fileName(),
        stored.mimeType(),
        stored.fileSize(),
        stored.width(),
        stored.height()
);

ChatSessionMessageDto persisted = chatSessionService.appendImageMessage(
        command.userId(),
        command.sessionId(),
        command.prompt(),
        payload,
        List.of(resource)
);
```

- [ ] **步骤 4：运行图片生成服务测试确认通过**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ImageGenerationServiceImplTest test
```

预期：

- `ImageGenerationServiceImplTest` PASS

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/image/MiniMaxImageClient.java \
  backend/src/main/java/com/h/backend/chat/image/MiniMaxHttpImageClient.java \
  backend/src/main/java/com/h/backend/chat/image/MiniMaxImageGenerationRequest.java \
  backend/src/main/java/com/h/backend/chat/image/MiniMaxImageGenerationResult.java \
  backend/src/main/java/com/h/backend/chat/service/ImageGenerationService.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ImageGenerationServiceImpl.java \
  backend/src/test/java/com/h/backend/chat/ImageGenerationServiceImplTest.java
git commit -m "feat: add minimax image generation service"
```

## 任务 4：扩展聊天主链路支持 `/image` 命令和 `image` SSE 事件

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatStreamEvent.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`

- [ ] **步骤 1：先为 `/image` 命令路径编写失败测试**

在 `backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java` 中新增：

```java
@Test
void shouldEmitImageEventForSlashImageCommand() {
    HAssistant hAssistant = mock(HAssistant.class);
    SystemPromptService systemPromptService = mock(SystemPromptService.class);
    ChatSessionService chatSessionService = mock(ChatSessionService.class);
    AgentRunService agentRunService = mock(AgentRunService.class);
    AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
    ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
    ChatServiceImpl chatService = new ChatServiceImpl(
            hAssistant,
            systemPromptService,
            chatSessionService,
            agentRunService,
            agentRunTelemetryService,
            imageGenerationService
    );

    when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
    when(chatSessionService.appendUserMessage(1L, "session-1", "/image 一只白猫")).thenReturn(101L);
    when(imageGenerationService.generateImage(any())).thenReturn(new ImageGenerationResult(
            new ChatSessionMessageDto(
                    "501",
                    "assistant",
                    "IMAGE",
                    "一只白猫",
                    new ChatMessagePayloadDto("一只白猫", "MINIMAX", "req-1", "image-01", "1:1", "READY", "COMMAND"),
                    List.of(new ChatMessageResourceDto(
                            "resource-701",
                            "IMAGE",
                            "/api/chat/resources/resource-701/content",
                            "/api/chat/resources/resource-701/download",
                            "generated-cat.png",
                            "image/png",
                            1234L,
                            1024,
                            1024
                    )),
                    LocalDateTime.now()
            )
    ));

    List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-1", "/image 一只白猫")
            .collectList()
            .block();

    assertEquals("image", events.getFirst().type());
    assertEquals("IMAGE", events.getFirst().message().messageType());
    assertEquals("一只白猫", events.getFirst().message().content());
    assertEquals("done", events.get(1).type());
    verify(hAssistant, never()).streamChat(any(), any());
}
```

- [ ] **步骤 2：运行测试确认图片命令路径尚未实现**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatServiceImplTest,ChatControllerTest test
```

预期：

- 编译失败或测试失败
- `ChatStreamEvent` 没有 `message()`，`ChatServiceImpl` 构造器缺少 `ImageGenerationService`

- [ ] **步骤 3：实现 `/image` 命令链路和 `image` 事件 DTO**

将 `ChatStreamEvent` 改为：

```java
public record ChatStreamEvent(
        String type,
        String content,
        ChatSessionMessageDto message
) {
    public ChatStreamEvent(String type, String content) {
        this(type, content, null);
    }
}
```

在 `ChatServiceImpl` 增加判断：

```java
if (userMessage.startsWith("/image ")) {
    String prompt = userMessage.substring("/image ".length()).trim();
    if (prompt.isBlank()) {
        return Flux.just(new ChatStreamEvent("error", "请输入图片描述"));
    }
    Long resolvedPromptId = systemPromptService.resolvePromptId(userId, promptId);
    chatSessionService.assertActiveSession(userId, sessionId, promptId);
    chatSessionService.appendUserMessage(userId, sessionId, userMessage);
    ImageGenerationResult result = imageGenerationService.generateImage(new ImageGenerationCommand(
            userId,
            sessionId,
            resolvedPromptId,
            prompt,
            "COMMAND",
            null
    ));
    return Flux.just(
            new ChatStreamEvent("image", "", result.message()),
            new ChatStreamEvent("done", "")
    );
}
```

- [ ] **步骤 4：运行聊天流测试确认 `/image` 事件通过**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatServiceImplTest,ChatControllerTest test
```

预期：

- `/image` 路径测试 PASS
- 控制器对 `image` 事件的 SSE 映射测试 PASS

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/dto/ChatStreamEvent.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java \
  backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java \
  backend/src/test/java/com/h/backend/chat/ChatControllerTest.java
git commit -m "feat: stream image chat events"
```

## 任务 5：接入 `langchain4j` 图片工具与事件桥

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/service/ChatStreamEventBridge.java`
- 创建：`backend/src/main/java/com/h/backend/chat/tools/ImageGenerationTool.java`
- 修改：`backend/src/main/java/com/h/backend/chat/ai/HAssistant.java`
- 修改：`backend/src/main/java/com/h/backend/chat/config/ChatModelConfig.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
- 创建：`backend/src/test/java/com/h/backend/chat/ImageGenerationToolTest.java`
- 修改：`backend/src/test/java/com/h/backend/chat/config/ChatModelConfigTest.java`

- [ ] **步骤 1：先为图片工具发布事件编写失败测试**

创建 `backend/src/test/java/com/h/backend/chat/ImageGenerationToolTest.java`：

```java
@Test
void shouldPublishImageEventWhenToolGeneratesImage() {
    ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
    ChatStreamEventBridge bridge = new ChatStreamEventBridge();
    ImageGenerationTool tool = new ImageGenerationTool(imageGenerationService, bridge);
    List<ChatStreamEvent> published = new ArrayList<>();
    bridge.register(published::add);

    when(imageGenerationService.generateImage(any())).thenReturn(new ImageGenerationResult(
            new ChatSessionMessageDto(
                    "501",
                    "assistant",
                    "IMAGE",
                    "一只白猫",
                    new ChatMessagePayloadDto("一只白猫", "MINIMAX", "req-1", "image-01", "1:1", "READY", "TOOL"),
                    List.of(new ChatMessageResourceDto(
                            "resource-701",
                            "IMAGE",
                            "/api/chat/resources/resource-701/content",
                            "/api/chat/resources/resource-701/download",
                            "generated-cat.png",
                            "image/png",
                            68L,
                            1024,
                            1024
                    )),
                    LocalDateTime.now()
            )
    ));

    String result = tool.generateImage("1:22:session-1", "一只白猫");

    assertEquals("图片已生成并发送到聊天中", result);
    assertEquals(1, published.size());
    assertEquals("image", published.getFirst().type());
    assertEquals("IMAGE", published.getFirst().message().messageType());
}
```

- [ ] **步骤 2：运行测试确认事件桥与工具尚未接入**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ImageGenerationToolTest,ChatModelConfigTest test
```

预期：

- 编译失败
- `ChatStreamEventBridge`、`ImageGenerationTool` 或新构造器缺失

- [ ] **步骤 3：实现事件桥和图片工具注册**

创建 `ChatStreamEventBridge`：

```java
package com.h.backend.chat.service;

import com.h.backend.chat.dto.ChatStreamEvent;

import java.util.function.Consumer;

public class ChatStreamEventBridge {

    private static final ThreadLocal<Consumer<ChatStreamEvent>> CURRENT = new ThreadLocal<>();

    public void register(Consumer<ChatStreamEvent> consumer) {
        CURRENT.set(consumer);
    }

    public void clear() {
        CURRENT.remove();
    }

    public void publish(ChatStreamEvent event) {
        Consumer<ChatStreamEvent> consumer = CURRENT.get();
        if (consumer != null) {
            consumer.accept(event);
        }
    }
}
```

创建 `ImageGenerationTool`：

```java
package com.h.backend.chat.tools;

import com.h.backend.chat.dto.ChatMessagePayloadDto;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.ChatStreamEventBridge;
import com.h.backend.chat.service.ImageGenerationService;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.service.tool.Tool;
import dev.langchain4j.service.tool.ToolProviderResult;

public class ImageGenerationTool {

    private final ImageGenerationService imageGenerationService;
    private final ChatStreamEventBridge bridge;

    public ImageGenerationTool(ImageGenerationService imageGenerationService, ChatStreamEventBridge bridge) {
        this.imageGenerationService = imageGenerationService;
        this.bridge = bridge;
    }

    @Tool(name = "generateImage")
    public String generateImage(@ToolMemoryId String memoryId, String prompt) {
        // 解析 memoryId，调用 imageGenerationService
        // 组装 ChatSessionMessageDto 并发布 image 事件
        return "图片已生成并发送到聊天中";
    }
}
```

在 `ChatModelConfig` 中将 `.tools(toolWithP)` 扩展为：

```java
.tools(toolWithP, imageGenerationTool)
```

在 `ChatServiceImpl.streamChat(...)` 中注册与清理 bridge：

```java
return Flux.create(sink -> {
    chatStreamEventBridge.register(sink::next);
    try {
        hAssistant.streamChat(memoryId, userMessage)
                .onPartialResponse(chunk -> {
                    replyBuilder.append(chunk);
                    sink.next(new ChatStreamEvent("chunk", chunk));
                })
                .onCompleteResponse(ignored -> {
                    // 保留现有成功收口逻辑
                    sink.next(new ChatStreamEvent("done", ""));
                    sink.complete();
                })
                .onError(error -> emitFailureEvent(sink, userId, sessionId, runHandle.id(), telemetryRun, error))
                .start();
    } finally {
        chatStreamEventBridge.clear();
    }
});
```

- [ ] **步骤 4：运行测试确认工具和桥接生效**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ImageGenerationToolTest,ChatServiceImplTest,ChatModelConfigTest test
```

预期：

- 工具触发图片事件的测试 PASS
- `ChatModelConfigTest` 能验证 `AiServices` 已包含图片工具

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/service/ChatStreamEventBridge.java \
  backend/src/main/java/com/h/backend/chat/tools/ImageGenerationTool.java \
  backend/src/main/java/com/h/backend/chat/ai/HAssistant.java \
  backend/src/main/java/com/h/backend/chat/config/ChatModelConfig.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java \
  backend/src/test/java/com/h/backend/chat/ImageGenerationToolTest.java \
  backend/src/test/java/com/h/backend/chat/config/ChatModelConfigTest.java \
  backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java
git commit -m "feat: add langchain image generation tool"
```

## 任务 6：实现资源预览/下载接口并完成历史消息装配

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/service/ChatResourceService.java`
- 创建：`backend/src/main/java/com/h/backend/chat/service/impl/ChatResourceServiceImpl.java`
- 创建：`backend/src/main/java/com/h/backend/chat/controller/ChatResourceController.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatResourceControllerTest.java`

- [ ] **步骤 1：先为资源接口写失败测试**

创建 `backend/src/test/java/com/h/backend/chat/ChatResourceControllerTest.java`：

```java
package com.h.backend.chat;

import com.h.backend.chat.controller.ChatResourceController;
import com.h.backend.chat.service.ChatResourceService;
import com.h.backend.security.AuthUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatResourceControllerTest {

    @Test
    void shouldReturnInlineImageContent() {
        ChatResourceService service = mock(ChatResourceService.class);
        ChatResourceController controller = new ChatResourceController(service);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "u@example.com", "USER");

        when(service.loadInline(1L, "resource-701")).thenReturn(
                ResponseEntity.ok()
                        .header("Content-Type", "image/png")
                        .body(new ByteArrayResource(new byte[] {1, 2, 3}))
        );

        ResponseEntity<ByteArrayResource> response = controller.content(principal, "resource-701");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("image/png", response.getHeaders().getFirst("Content-Type"));
    }
}
```

- [ ] **步骤 2：运行测试确认 controller 与 service 尚不存在**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatResourceControllerTest test
```

预期：

- 编译失败
- `ChatResourceController` / `ChatResourceService` 缺失

- [ ] **步骤 3：实现资源访问 controller 与 service**

`ChatResourceController` 形态：

```java
@RestController
@RequestMapping("/api/chat/resources")
public class ChatResourceController {

    private final ChatResourceService chatResourceService;

    public ChatResourceController(ChatResourceService chatResourceService) {
        this.chatResourceService = chatResourceService;
    }

    @GetMapping("/{resourceId}/content")
    public ResponseEntity<ByteArrayResource> content(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String resourceId
    ) {
        return chatResourceService.loadInline(principal.userId(), resourceId);
    }

    @GetMapping("/{resourceId}/download")
    public ResponseEntity<ByteArrayResource> download(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String resourceId
    ) {
        return chatResourceService.loadDownload(principal.userId(), resourceId);
    }
}
```

`ChatSessionServiceImpl.toMessageDto(...)` 中补齐：

```java
private ChatSessionMessageDto toMessageDto(
        ChatSessionMessageEntity row,
        List<ChatMessageResourceDto> resources
) {
    ChatMessagePayloadDto payload = readPayload(row.getPayloadJson());
    return new ChatSessionMessageDto(
            String.valueOf(row.getId()),
            normalizeRole(row.getRoleCode()),
            row.getMessageType(),
            row.getContentText() == null ? "" : row.getContentText(),
            payload,
            resources,
            row.getCreatedAt()
    );
}
```

- [ ] **步骤 4：运行资源接口和历史 DTO 测试确认通过**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatSessionServiceImplTest,ChatResourceControllerTest test
```

预期：

- `ChatResourceControllerTest` PASS
- 历史图片消息 DTO 测试 PASS

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/service/ChatResourceService.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatResourceServiceImpl.java \
  backend/src/main/java/com/h/backend/chat/controller/ChatResourceController.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java \
  backend/src/test/java/com/h/backend/chat/ChatResourceControllerTest.java \
  backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java
git commit -m "feat: serve chat image resources"
```

## 任务 7：前端支持 `image` 事件、图片卡片和历史回放

**文件：**
- 修改：`frontend/lib/http.ts`
- 修改：`frontend/lib/http.test.mjs`
- 修改：`frontend/lib/chat-sessions.ts`
- 修改：`frontend/app/chat/page.tsx`

- [ ] **步骤 1：先为 `image` SSE 事件解析编写失败测试**

在 `frontend/lib/http.test.mjs` 中新增：

```js
test("apiStream dispatches image events without throwing", async () => {
  const originalFetch = globalThis.fetch;
  const received = [];

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              "event: image\n" +
                `data: ${JSON.stringify({
                  type: "image",
                  content: "",
                  message: {
                    id: "501",
                    role: "assistant",
                    messageType: "IMAGE",
                    content: "一只白猫",
                    payload: { prompt: "一只白猫", provider: "MINIMAX", model: "image-01" },
                    resources: [
                      {
                        id: "resource-701",
                        kind: "IMAGE",
                        viewUrl: "/api/chat/resources/resource-701/content",
                        downloadUrl: "/api/chat/resources/resource-701/download",
                        fileName: "generated-cat.png",
                        mimeType: "image/png",
                        width: 1024,
                        height: 1024
                      }
                    ]
                  }
                })}\n\n`,
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
      onImage(message) {
        received.push(message);
      },
    });
    assert.equal(received[0].messageType, "IMAGE");
    assert.equal(received[0].resources[0].downloadUrl, "/api/chat/resources/resource-701/download");
  } finally {
    globalThis.fetch = originalFetch;
  }
});
```

- [ ] **步骤 2：运行前端流解析测试确认 `onImage` 尚未实现**

运行：

```bash
cd frontend && node --test lib/http.test.mjs
```

预期：

- 测试失败
- `onImage` 未被调用或消息对象被忽略

- [ ] **步骤 3：升级前端消息类型与图片卡片渲染**

将 `frontend/lib/chat-sessions.ts` 中的消息类型改为：

```ts
export type ChatMessageResource = {
  id: string;
  kind: string;
  viewUrl: string;
  downloadUrl: string;
  fileName: string;
  mimeType: string;
  fileSize: number | null;
  width: number | null;
  height: number | null;
};

export type ChatMessagePayload = {
  prompt: string | null;
  provider: string | null;
  providerRequestId?: string | null;
  model: string | null;
  aspectRatio?: string | null;
  status?: string | null;
  triggerSource?: string | null;
};

export type ChatSessionMessage = {
  id: string;
  role: "assistant" | "blocked" | "user";
  messageType: "TEXT" | "BLOCKED" | "IMAGE";
  content: string;
  payload: ChatMessagePayload | null;
  resources: ChatMessageResource[];
  createdAt: string;
};
```

在 `frontend/lib/http.ts` 中加入：

```ts
onImage?: (message: {
  id: string;
  role: "assistant" | "blocked" | "user";
  messageType: "TEXT" | "BLOCKED" | "IMAGE";
  content: string;
  payload: Record<string, unknown> | null;
  resources: Array<Record<string, unknown>>;
}) => void;
```

并在 SSE 分发逻辑中加入：

```ts
} else if (eventType === "image") {
  handlers.onImage?.(payload.message);
}
```

在 `frontend/app/chat/page.tsx` 中将本地消息改成联合类型，并新增图片渲染：

```tsx
function ImageMessageCard({ message }: { message: ChatMessage }) {
  if (message.type !== "image") return null;
  const resource = message.resources[0];
  if (!resource) return null;

  return (
    <div className="space-y-3">
      <img
        src={resource.viewUrl}
        alt={message.content || message.payload?.prompt || "生成图片"}
        className="w-full rounded-3xl border border-stone-200 object-cover"
      />
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm leading-6 text-stone-700">{message.content}</p>
        <a
          href={resource.downloadUrl}
          download={resource.fileName}
          className="rounded-full border border-stone-300 px-4 py-2 text-xs font-semibold text-stone-700"
        >
          下载
        </a>
      </div>
    </div>
  );
}
```

- [ ] **步骤 4：运行前端测试确认 `image` 事件解析和图片消息渲染通过**

运行：

```bash
cd frontend && node --test lib/http.test.mjs
```

如果仓库已有前端 lint/test 脚本，再运行：

```bash
cd frontend && npm test -- --runInBand
```

预期：

- `http.test.mjs` PASS
- 若 `npm test` 不存在，记录该事实并继续

- [ ] **步骤 5：Commit**

```bash
git add frontend/lib/http.ts \
  frontend/lib/http.test.mjs \
  frontend/lib/chat-sessions.ts \
  frontend/app/chat/page.tsx
git commit -m "feat: render generated chat images"
```

## 任务 8：端到端回归验证与配置说明收尾

**文件：**
- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ImageGenerationServiceImplTest.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ImageGenerationToolTest.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatResourceControllerTest.java`
- 修改：`frontend/lib/http.test.mjs`
- 参考：`docs/superpowers/specs/2026-05-27-chat-image-generation-design.md`

- [ ] **步骤 1：补齐遗漏的失败用例**

确认至少覆盖以下场景；若缺失则补测试：

```java
@Test
void shouldEmitErrorWhenSlashImagePromptBlank() {
    // "/image   " -> error 事件，不调用图片服务
}

@Test
void shouldRejectResourceAccessForDifferentUser() {
    // 非所属用户访问 resourceId -> BusinessException(40404) 或 403
}
```

以及前端：

```js
test("apiStream keeps blocked and image handlers isolated", async () => {
  // 确认 image 事件不会走 onChunk / onBlocked
});
```

- [ ] **步骤 2：运行后端测试全集验证图片链路**

运行：

```bash
source ~/.profile && cd backend && mvn -q -Dtest=ChatControllerTest,ChatServiceImplTest,ChatSessionServiceImplTest,ImageGenerationServiceImplTest,ImageGenerationToolTest,ChatResourceControllerTest,ChatModelConfigTest test
```

预期：

- 所有相关后端测试 PASS

- [ ] **步骤 3：运行前端测试验证 SSE 解析与消息状态机**

运行：

```bash
cd frontend && node --test lib/http.test.mjs
```

预期：

- 所有 `apiStream` 解析测试 PASS

- [ ] **步骤 4：整理手工验证清单并确认无未覆盖行为**

手工验证清单：

```text
1. 登录后在聊天页发送 "/image 一只白猫"，应出现占位态，随后出现图片卡片和下载按钮。
2. 发送自然语言“帮我生成一只白猫”，当模型选择图片工具时，应出现图片卡片，且仍可伴随文本 chunk。
3. 刷新聊天页或重新打开历史会话，图片消息应从历史接口正确回放。
4. 点击下载按钮，请求 /api/chat/resources/{id}/download，浏览器应下载图片文件。
5. 用其他账号访问同一资源地址，应被拒绝。
```

- [ ] **步骤 5：Commit**

```bash
git add backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java \
  backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java \
  backend/src/test/java/com/h/backend/chat/ImageGenerationServiceImplTest.java \
  backend/src/test/java/com/h/backend/chat/ImageGenerationToolTest.java \
  backend/src/test/java/com/h/backend/chat/ChatResourceControllerTest.java \
  backend/src/test/java/com/h/backend/chat/config/ChatModelConfigTest.java \
  frontend/lib/http.test.mjs
git commit -m "test: verify chat image generation flow"
```
