# 图生图功能扩展实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 扩展文生图为图生图，支持参考图上传（本地+历史选择），LLM 工具调用决定意图，前端通用媒体渲染。

**架构：** 新增通用文件上传端点 → 前端上传拿 resourceId → 随消息发送 referenceResourceIds → /image 命令直接走图生图，普通消息由 LLM 工具调用决定 → 前端 MediaContent 通用渲染。

**技术栈：** Java 17 / Spring Boot / Maven / MyBatis-Plus / langchain4j / TypeScript / React 19 / Next.js

**设计文档：** `docs/superpowers/specs/2026-06-24-chat-image-to-image-design.md`

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|------|------|
| `backend/src/main/java/com/h/backend/chat/dto/ResourceUploadResponse.java` | 上传响应 DTO |
| `backend/src/main/java/com/h/backend/chat/config/ResourceUploadProperties.java` | 上传白名单+大小限制配置 |
| `backend/src/test/java/com/h/backend/chat/ResourceUploadControllerTest.java` | 上传端点测试 |
| `frontend/lib/resource-upload.ts` | 前端上传 API 封装 |

### 修改文件

| 文件 | 变化 |
|------|------|
| `backend/src/main/java/com/h/backend/chat/controller/ChatResourceController.java` | 新增 `POST /upload` |
| `backend/src/main/java/com/h/backend/chat/dto/ChatMessageRequest.java` | 加 `referenceResourceIds` |
| `backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java` | `appendUserMessage` 加参数 |
| `backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java` | 实现资源关联 |
| `backend/src/main/java/com/h/backend/chat/controller/ChatController.java` | 传 `referenceResourceIds` |
| `backend/src/main/java/com/h/backend/chat/service/ChatService.java` | `streamChat` 加参数 |
| `backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java` | 传参 + emitImageCommandEvents 加参数 + 增强消息 |
| `backend/src/main/java/com/h/backend/chat/image/MiniMaxImageGenerationRequest.java` | 加 `SubjectReference` |
| `backend/src/main/java/com/h/backend/chat/image/MiniMaxHttpImageClient.java` | 请求体改 HashMap |
| `backend/src/main/java/com/h/backend/chat/tools/ImageGenerationTool.java` | 加 `referenceResourceId` 入参 |
| `backend/src/main/java/com/h/backend/chat/service/impl/ImageGenerationServiceImpl.java` | 读参考图转 Base64 |
| `backend/src/main/java/com/h/backend/chat/agent/ChatAgentExecutionCommand.java` | 加 `referenceResourceIds` |
| `backend/src/main/java/com/h/backend/chat/agent/HAssistantStreamingExecutor.java` | 消息增强 |
| `frontend/lib/chat-message-state.ts` | `RenderableTurn.user` 加 resources |
| `frontend/lib/chat-agent-mode.ts` | `buildChatSendPayload` 加参数 |
| `frontend/app/chat/page.tsx` | 输入框附件UI + MediaContent + 用户消息渲染 |

---

### 任务 1：上传配置与 DTO

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/config/ResourceUploadProperties.java`
- 创建：`backend/src/main/java/com/h/backend/chat/dto/ResourceUploadResponse.java`
- 修改：`backend/src/main/resources/application.yml`

- [ ] **步骤 1：创建 `ResourceUploadProperties`**

```java
package com.h.backend.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "chat.resources.upload")
public class ResourceUploadProperties {

    private List<String> allowedMimeTypes = List.of("image/jpeg", "image/png");
    private long maxFileSize = 10_485_760L; // 10MB

    public List<String> getAllowedMimeTypes() { return allowedMimeTypes; }
    public void setAllowedMimeTypes(List<String> allowedMimeTypes) { this.allowedMimeTypes = allowedMimeTypes; }
    public long getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(long maxFileSize) { this.maxFileSize = maxFileSize; }
}
```

- [ ] **步骤 2：创建 `ResourceUploadResponse`**

```java
package com.h.backend.chat.dto;

public record ResourceUploadResponse(
    String resourceId,
    String kind,
    String viewUrl,
    String downloadUrl,
    String fileName,
    String mimeType,
    Long fileSize
) {}
```

- [ ] **步骤 3：在 `application.yml` 中添加配置**

```yaml
chat:
  resources:
    upload:
      allowed-mime-types: image/jpeg,image/png
      max-file-size: 10485760
```

- [ ] **步骤 4：验证编译通过**

运行：`cd backend && mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat: add resource upload config and DTO"
```

---

### 任务 2：资源上传端点

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/controller/ChatResourceController.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ResourceUploadControllerTest.java`

- [ ] **步骤 1：编写上传端点测试**

```java
// ResourceUploadControllerTest.java
package com.h.backend.chat;

import com.h.backend.chat.config.ResourceUploadProperties;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.mapper.ChatMessageResourceMapper;
import com.h.backend.security.AuthUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatResourceController.class)
class ResourceUploadControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ResourceStorage resourceStorage;
    @MockBean ChatMessageResourceMapper chatMessageResourceMapper;
    @MockBean ResourceUploadProperties uploadProperties;

    @Test
    @WithMockUser(username = "1")
    void uploadImage_success() throws Exception {
        when(uploadProperties.getAllowedMimeTypes()).thenReturn(java.util.List.of("image/jpeg", "image/png"));
        when(uploadProperties.getMaxFileSize()).thenReturn(10_485_760L);
        when(resourceStorage.save(any(ResourceSaveCommand.class))).thenReturn(
            new StoredResource("r1", "LOCAL_FILE", "key1", "photo.jpg", "image/jpeg", 1024L, null, null, "sha1")
        );

        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[1024]);
        mockMvc.perform(multipart("/api/chat/resources/upload").file(file).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resourceId").value("r1"))
            .andExpect(jsonPath("$.kind").value("IMAGE"));
    }

    @Test
    @WithMockUser(username = "1")
    void upload_disallowedMimeType_returns400() throws Exception {
        when(uploadProperties.getAllowedMimeTypes()).thenReturn(java.util.List.of("image/jpeg"));
        when(uploadProperties.getMaxFileSize()).thenReturn(10_485_760L);

        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[100]);
        mockMvc.perform(multipart("/api/chat/resources/upload").file(file).with(csrf()))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd backend && mvn test -pl . -Dtest=ResourceUploadControllerTest -q`
预期：FAIL（端点不存在）

- [ ] **步骤 3：实现上传端点**

在 `ChatResourceController` 中新增 `upload` 方法：

```java
@PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<ResourceUploadResponse> upload(
        @AuthenticationPrincipal AuthUserPrincipal principal,
        @RequestParam("file") MultipartFile file
) {
    // 1. 校验 mimeType
    String mimeType = file.getContentType();
    if (mimeType == null || !uploadProperties.getAllowedMimeTypes().contains(mimeType)) {
        throw new BusinessException(40000, "暂不支持该文件类型: " + mimeType);
    }
    // 2. 校验大小
    if (file.getSize() > uploadProperties.getMaxFileSize()) {
        throw new BusinessException(40000, "文件大小不能超过 " + (uploadProperties.getMaxFileSize() / 1_048_576) + "MB");
    }
    // 3. 归类 resourceKind
    String kind = mimeType.startsWith("image/") ? "IMAGE"
                : mimeType.startsWith("video/") ? "VIDEO"
                : mimeType.startsWith("audio/") ? "AUDIO" : "FILE";
    // 4. 存储
    StoredResource stored = resourceStorage.save(new ResourceSaveCommand(
        kind, null, safeFileName(file.getOriginalFilename()),
        file.getBytes(), mimeType,
        extensionFor(mimeType), null, null
    ));
    // 5. 写 DB（messageId=null）
    ChatMessageResourceEntity row = new ChatMessageResourceEntity();
    row.setId(stored.id());
    row.setMessageId(null);
    row.setUserId(principal.userId());
    row.setSessionId(null);
    row.setResourceKind(kind);
    row.setStorageType(stored.storageType());
    row.setStorageKey(stored.storageKey());
    row.setViewUrl(resourceStorage.buildViewUrl(stored.id()));
    row.setDownloadUrl(resourceStorage.buildDownloadUrl(stored.id()));
    row.setMimeType(stored.mimeType());
    row.setFileName(stored.fileName());
    row.setFileSize(stored.fileSize());
    row.setWidth(stored.width());
    row.setHeight(stored.height());
    row.setSha256(stored.sha256());
    row.setCreatedAt(LocalDateTime.now());
    chatMessageResourceMapper.insert(row);
    // 6. 返回
    return ResponseEntity.ok(new ResourceUploadResponse(
        stored.id(), kind,
        resourceStorage.buildViewUrl(stored.id()),
        resourceStorage.buildDownloadUrl(stored.id()),
        stored.fileName(), stored.mimeType(), stored.fileSize()
    ));
}
```

需要在 Controller 中注入 `ResourceUploadProperties`、`ResourceStorage`、`ChatMessageResourceMapper`。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd backend && mvn test -pl . -Dtest=ResourceUploadControllerTest -q`
预期：PASS

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat: add POST /api/chat/resources/upload endpoint"
```

---

### 任务 3：ChatMessageRequest 加 referenceResourceIds

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatMessageRequest.java`
- 修改：`backend/src/main/java/com/h/backend/chat/controller/ChatController.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatService.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`

- [ ] **步骤 1：修改 `ChatMessageRequest`**

```java
public record ChatMessageRequest(
    @NotBlank(message = "消息不能为空")
    @Size(max = 4000, message = "消息长度不能超过 4000")
    String message,
    @NotBlank(message = "sessionId 不能为空")
    String sessionId,
    Long promptId,
    String agentId,
    List<String> referenceResourceIds  // 新增，可空
) {}
```

- [ ] **步骤 2：修改 `ChatController` 传参**

```java
Flux<ChatStreamEvent> chatEvents = chatService.streamChat(
    principal.userId(),
    request.promptId(),
    request.agentId(),
    request.sessionId(),
    request.message().trim(),
    request.referenceResourceIds()  // 新增
);
```

- [ ] **步骤 3：修改 `ChatService` 接口**

```java
Flux<ChatStreamEvent> streamChat(Long userId, Long promptId, String agentId,
    String sessionId, String message, List<String> referenceResourceIds);
```

- [ ] **步骤 4：修改 `ChatServiceImpl.streamChat` 签名**

暂时只改签名，`referenceResourceIds` 透传到 `runChatStream` 和 `runImageCommandStream`（后续任务实现）。

- [ ] **步骤 5：验证编译**

运行：`cd backend && mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 6：Commit**

```bash
git add -A && git commit -m "feat: add referenceResourceIds to ChatMessageRequest"
```

---

### 任务 4：appendUserMessage 支持资源关联

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`

- [ ] **步骤 1：编写测试**

在 `ChatSessionServiceImplTest` 中新增：

```java
@Test
void appendUserMessage_withReferenceResourceIds_createsReferenceRecord() {
    // 先上传一条资源（messageId=null）
    ChatMessageResourceEntity uploadRecord = new ChatMessageResourceEntity();
    uploadRecord.setId("r-upload-1");
    uploadRecord.setMessageId(null);
    uploadRecord.setUserId(userId);
    uploadRecord.setResourceKind("IMAGE");
    uploadRecord.setStorageKey("key1");
    uploadRecord.setViewUrl("/api/chat/resources/r-upload-1/content");
    uploadRecord.setMimeType("image/jpeg");
    // ... insert via mapper

    // 调用 appendUserMessage with referenceResourceIds
    Long messageId = service.appendUserMessage(userId, sessionId, "test", List.of("r-upload-1"));

    // 验证 REFERENCE 记录被创建
    List<ChatMessageResourceEntity> resources = mapper.selectByMessageIds(List.of(messageId));
    assertEquals(1, resources.size());
    assertEquals("REFERENCE", resources.get(0).getResourceKind());
    assertEquals("key1", resources.get(0).getStorageKey());
}
```

- [ ] **步骤 2：运行测试验证失败**

- [ ] **步骤 3：修改 `ChatSessionService` 接口**

```java
Long appendUserMessage(Long userId, String sessionId, String userMessage, List<String> referenceResourceIds);
```

- [ ] **步骤 4：修改 `ChatSessionServiceImpl.appendUserMessage`**

按设计文档中的代码实现（查原记录 → 复制新记录 → `resource_kind=REFERENCE`）。

- [ ] **步骤 5：更新所有现有调用点**

`ChatServiceImpl` 中所有 `appendUserMessage(userId, sessionId, message)` 调用改为 `appendUserMessage(userId, sessionId, message, null)`。

- [ ] **步骤 6：运行测试验证通过**

运行：`cd backend && mvn test -pl . -Dtest=ChatSessionServiceImplTest -q`
预期：PASS

- [ ] **步骤 7：Commit**

```bash
git add -A && git commit -m "feat: appendUserMessage supports referenceResourceIds"
```

---

### 任务 5：MiniMax subject_reference 支持

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/image/MiniMaxImageGenerationRequest.java`
- 修改：`backend/src/main/java/com/h/backend/chat/image/MiniMaxHttpImageClient.java`
- 修改：`backend/src/test/java/com/h/backend/chat/image/MiniMaxHttpImageClientTest.java`

- [ ] **步骤 1：编写测试**

```java
@Test
void generate_withSubjectReference_includesInRequestBody() {
    // mock HTTP response
    // call generate() with SubjectReference
    // verify request body contains "subject_reference" array
}

@Test
void generate_withoutSubjectReference_noSubjectReferenceInBody() {
    // call generate() with subjectReference=null
    // verify request body does NOT contain "subject_reference"
}
```

- [ ] **步骤 2：修改 `MiniMaxImageGenerationRequest`**

```java
public record MiniMaxImageGenerationRequest(
    String model, String prompt, String aspectRatio,
    String responseFormat, int n, boolean promptOptimizer,
    SubjectReference subjectReference
) {
    public record SubjectReference(String type, String imageFile) {}
}
```

- [ ] **步骤 3：修改 `MiniMaxHttpImageClient.generate`**

将 `Map.of(...)` 改为 `HashMap`，条件加入 `subject_reference`：

```java
Map<String, Object> body = new HashMap<>();
body.put("model", request.model());
body.put("prompt", request.prompt());
body.put("aspect_ratio", request.aspectRatio());
body.put("response_format", request.responseFormat());
body.put("n", request.n());
body.put("prompt_optimizer", request.promptOptimizer());
if (request.subjectReference() != null) {
    body.put("subject_reference", List.of(Map.of(
        "type", request.subjectReference().type(),
        "image_file", request.subjectReference().imageFile()
    )));
}
```

- [ ] **步骤 4：运行测试验证通过**

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat: MiniMax request supports subject_reference"
```

---

### 任务 6：ImageGenerationTool 加 referenceResourceId

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/tools/ImageGenerationTool.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ImageGenerationToolTest.java`

- [ ] **步骤 1：编写测试**

```java
@Test
void generateImage_withReferenceResourceId_passesToSubAgent() {
    tool.generateImage("1:2:session1", "test prompt", "r-ref-1");
    // verify imageSubAgentService called with sourceResourceId="r-ref-1"
}

@Test
void generateImage_withoutReferenceResourceId_passesNull() {
    tool.generateImage("1:2:session1", "test prompt", null);
    // verify imageSubAgentService called with sourceResourceId=null
}
```

- [ ] **步骤 2：修改 `ImageGenerationTool`**

```java
@Tool(value = "根据描述生成图片并发送到聊天。若用户提供了参考图资源ID，传入该ID将基于参考图"
    + "生成保留人物特征的新图；不传 referenceResourceId 则为纯文生图。",
    searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
public String generateImage(@ToolMemoryId String memoryId, String prompt, String referenceResourceId) {
    ImageGenerationContext context = parseMemoryId(memoryId);
    ChatSessionMessageDto message = imageSubAgentService.generateImage(
        new ImageSubAgentCommand(
            context.userId(), context.sessionId(), context.promptId(),
            prompt, "TOOL",
            referenceResourceId, null, "GENERATE"
        )
    );
    chatStreamEventBridge.publishImage(memoryId, message);
    return "图片已生成并发送到聊天中。";
}
```

- [ ] **步骤 3：运行测试验证通过**

- [ ] **步骤 4：Commit**

```bash
git add -A && git commit -m "feat: ImageGenerationTool accepts referenceResourceId"
```

---

### 任务 7：ImageGenerationServiceImpl 支持参考图 Base64

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ImageGenerationServiceImpl.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ImageGenerationServiceImplTest.java`

- [ ] **步骤 1：编写测试**

```java
@Test
void generateImage_withSourceResourceId_readsBase64AndPassesSubjectReference() {
    // mock ChatMessageResourceMapper to return a record with storageKey
    // mock ResourceStorage.open() to return image bytes
    // mock MiniMaxImageClient to capture request
    // verify request.subjectReference() is not null
    // verify imageFile starts with "data:image/jpeg;base64,"
}

@Test
void generateImage_withoutSourceResourceId_pureTextToImage() {
    // command.sourceResourceId() = null
    // verify request.subjectReference() is null
}
```

- [ ] **步骤 2：修改 `ImageGenerationServiceImpl`**

新增依赖 `ChatMessageResourceMapper`，在 `generateImage` 中：

```java
MiniMaxImageGenerationRequest.SubjectReference subjectRef = null;
if (command.sourceResourceId() != null && chatMessageResourceMapper != null) {
    ChatMessageResourceEntity refResource = chatMessageResourceMapper.selectByResourceId(command.sourceResourceId());
    if (refResource != null) {
        try (InputStream is = resourceStorage.open(refResource.getStorageKey()).inputStream()) {
            byte[] bytes = is.readAllBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String dataUrl = "data:" + refResource.getMimeType() + ";base64," + base64;
            subjectRef = new MiniMaxImageGenerationRequest.SubjectReference("character", dataUrl);
        }
    }
}
// 传入 request
new MiniMaxImageGenerationRequest(..., subjectRef)
```

- [ ] **步骤 3：运行测试验证通过**

- [ ] **步骤 4：Commit**

```bash
git add -A && git commit -m "feat: ImageGenerationService reads reference image as Base64"
```

---

### 任务 8：ChatServiceImpl + Executor 消息增强

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
- 修改：`backend/src/main/java/com/h/backend/chat/agent/ChatAgentExecutionCommand.java`
- 修改：`backend/src/main/java/com/h/backend/chat/agent/HAssistantStreamingExecutor.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`

- [ ] **步骤 1：修改 `ChatAgentExecutionCommand`**

新增 `referenceResourceIds` 字段（`List<String>`，可空）。

- [ ] **步骤 2：修改 `ChatServiceImpl`**

三个改动点：
1. `runChatStream` 中 `appendUserMessage` 传 `referenceResourceIds`
2. `runChatStream` 中 `ChatAgentExecutionCommand` 构造传 `referenceResourceIds`
3. `emitImageCommandEvents` 加参数，非空时取第一个 ID 作为 `sourceResourceId`

- [ ] **步骤 3：修改 `HAssistantStreamingExecutor`**

在 `hAssistant.streamChat(memoryId, userMessage)` 调用前，若 `command.referenceResourceIds()` 非空，构建增强消息：

```java
String messageForLlm = command.userMessage();
if (command.referenceResourceIds() != null && !command.referenceResourceIds().isEmpty()) {
    messageForLlm = command.userMessage()
        + "\n[系统：用户附带了一张参考图（资源ID: " + command.referenceResourceIds().get(0)
        + "），如需基于此图生成新图片，调用 generateImage 并传入该资源ID]";
}
hAssistant.streamChat(command.memoryId(), messageForLlm)
```

注意：`command.userMessage()` 保持不变（存储用），`messageForLlm` 仅用于 LLM 调用。

- [ ] **步骤 4：更新 `ChatServiceImplTest` 中受影响的测试**

更新 `streamChat` 调用签名以匹配新参数。

- [ ] **步骤 5：运行所有后端测试**

运行：`cd backend && mvn test -q`
预期：PASS

- [ ] **步骤 6：Commit**

```bash
git add -A && git commit -m "feat: executor injects reference image hint for LLM"
```

---

### 任务 9：前端上传 API + 输入框附件 UI

**文件：**
- 创建：`frontend/lib/resource-upload.ts`
- 修改：`frontend/lib/chat-agent-mode.ts`
- 修改：`frontend/lib/chat-message-state.ts`
- 修改：`frontend/app/chat/page.tsx`

- [ ] **步骤 1：创建 `resource-upload.ts`**

```ts
import { apiFetch } from "./http";

export type UploadedResource = {
  resourceId: string;
  kind: string;
  viewUrl: string;
  downloadUrl: string;
  fileName: string;
  mimeType: string;
  fileSize: number;
};

export function uploadChatResource(file: File): Promise<UploadedResource> {
  const formData = new FormData();
  formData.append("file", file);
  return fetch("/api/chat/resources/upload", {
    method: "POST",
    body: formData,
  }).then(async (res) => {
    if (!res.ok) {
      const text = await res.text();
      throw new Error(text || "上传失败");
    }
    return res.json();
  });
}
```

注意：不能用 `apiFetch`，因为它会设置 `Content-Type: application/json`。直接用 `fetch` + `FormData`。

- [ ] **步骤 2：修改 `buildChatSendPayload`**

```ts
export function buildChatSendPayload(input: {
  message: string;
  sessionId: string;
  agentId: string;
  promptId: number | null;
  referenceResourceIds?: string[];
}) {
  const standard = isStandardAgent(input.agentId);
  return {
    message: input.message,
    sessionId: input.sessionId,
    promptId: standard ? input.promptId : null,
    agentId: standard ? STANDARD_AGENT_ID : input.agentId,
    referenceResourceIds: input.referenceResourceIds ?? null,
  };
}
```

- [ ] **步骤 3：修改 `RenderableTurn.user` 加 resources**

```ts
| { kind: "user"; id: string; content: string; resources?: ChatMessageResource[] }
```

修改 `toRenderableTurns` user 分支：

```ts
turns.push({
  kind: "user",
  id: current.id,
  content: current.content,
  resources: current.resources ?? [],
});
```

- [ ] **步骤 4：修改 `buildPendingAssistantTurn`**

新增 `pendingResources` 参数：

```ts
export function buildPendingAssistantTurn(content: string, seed: number, pendingResources?: ChatMessageResource[]) {
  return {
    userMessage: {
      id: `user-${seed}`,
      role: "user",
      messageType: "USER",
      content,
      resources: pendingResources ?? [],
    } satisfies UiChatMessage,
    // ... 其余不变
  };
}
```

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat: frontend upload API and message type extensions"
```

---

### 任务 10：前端 MediaContent 组件 + 用户消息渲染

**文件：**
- 修改：`frontend/app/chat/page.tsx`

- [ ] **步骤 1：将 `ImageMessageContent` 改为通用 `MediaContent`**

替换现有 `ImageMessageContent` 组件为 `MediaContent`，增加 mimeType 分发：

```tsx
function MediaContent({ content, resources }: {
  content: string;
  resources: Array<{
    id: string; viewUrl: string; downloadUrl: string;
    fileName: string; mimeType: string;
    width: number | null; height: number | null;
  }>;
}) {
  return (
    <div className="space-y-3">
      {resources.map((resource) => {
        if (resource.mimeType.startsWith("image/")) {
          return (
            <div key={resource.id} className="space-y-2">
              <img className="aspect-square w-full rounded-[1.2rem] border border-stone-200 object-cover"
                src={resource.viewUrl} alt={content || resource.fileName}
                width={resource.width ?? 1024} height={resource.height ?? 1024} />
              <div className="flex justify-end">
                <a className="shrink-0 rounded-full bg-stone-900 px-3 py-2 text-xs font-semibold text-white"
                  href={resource.downloadUrl}>下载</a>
              </div>
            </div>
          );
        }
        if (resource.mimeType.startsWith("video/")) {
          return <video key={resource.id} src={resource.viewUrl} controls className="w-full rounded-[1.2rem]" />;
        }
        if (resource.mimeType.startsWith("audio/")) {
          return <audio key={resource.id} src={resource.viewUrl} controls className="w-full" />;
        }
        return (
          <a key={resource.id} href={resource.downloadUrl}
            className="block rounded-xl border border-stone-200 px-3 py-2 text-sm text-stone-600">
            {resource.fileName}
          </a>
        );
      })}
    </div>
  );
}
```

更新 AI IMAGE 消息渲染（`turn.kind === "image"`）使用 `MediaContent`。

- [ ] **步骤 2：修改用户消息气泡渲染**

```tsx
{turn.kind === "user" ? (
  <div className="space-y-2">
    {turn.resources && turn.resources.length > 0 ? (
      <MediaContent content={turn.content} resources={turn.resources} />
    ) : null}
    <p className="whitespace-pre-wrap">{turn.content}</p>
  </div>
) : ...}
```

- [ ] **步骤 3：Commit**

```bash
git add -A && git commit -m "feat: MediaContent component and user message resource rendering"
```

---

### 任务 11：前端输入框附件功能

**文件：**
- 修改：`frontend/app/chat/page.tsx`

- [ ] **步骤 1：新增状态和导入**

```ts
import { uploadChatResource, type UploadedResource } from "@/lib/resource-upload";

const [pendingResources, setPendingResources] = useState<UploadedResource[]>([]);
const [uploading, setUploading] = useState(false);
const fileInputRef = useRef<HTMLInputElement>(null);
```

- [ ] **步骤 2：实现文件选择和上传**

```tsx
<input ref={fileInputRef} type="file" accept="image/jpeg,image/png" className="hidden"
  onChange={async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 10 * 1024 * 1024) { setError("文件大小不能超过 10MB"); return; }
    setUploading(true);
    try {
      const result = await uploadChatResource(file);
      setPendingResources((prev) => [...prev, result]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "上传失败");
    } finally {
      setUploading(false);
      e.target.value = "";
    }
  }}
/>
```

- [ ] **步骤 3：实现附件预览区和附件按钮**

在 `<form>` 内 `<textarea>` 上方：

```tsx
{pendingResources.length > 0 ? (
  <div className="flex gap-2 px-2 pb-2">
    {pendingResources.map((r) => (
      <div key={r.resourceId} className="relative h-16 w-16">
        <img src={r.viewUrl} alt={r.fileName}
          className="h-full w-full rounded-lg border border-stone-200 object-cover" />
        <button type="button"
          className="absolute -right-1 -top-1 flex h-5 w-5 items-center justify-center rounded-full bg-stone-800 text-xs text-white"
          onClick={() => setPendingResources((prev) => prev.filter((p) => p.resourceId !== r.resourceId))}>
          ×
        </button>
      </div>
    ))}
  </div>
) : null}
```

📎 按钮放在 textarea 左侧：

```tsx
<button type="button" className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full border border-stone-200 bg-white text-stone-600"
  onClick={() => fileInputRef.current?.click()} disabled={uploading || streaming}>
  {uploading ? "..." : "📎"}
</button>
```

- [ ] **步骤 4：修改 handleSubmit 传 referenceResourceIds**

```ts
const { userMessage, reasoningMessage, assistantMessage } = buildPendingAssistantTurn(
  content, seed,
  pendingResources.map(r => ({
    id: r.resourceId, kind: r.kind, viewUrl: r.viewUrl, downloadUrl: r.downloadUrl,
    fileName: r.fileName, mimeType: r.mimeType, fileSize: r.fileSize,
    width: null, height: null,
  }))
);
setPendingResources([]);

// body 中加:
body: JSON.stringify(buildChatSendPayload({
  message: content, sessionId, promptId: selectedPromptId,
  agentId: currentAgentId,
  referenceResourceIds: pendingResources.map(r => r.resourceId),
})),
```

注意：`referenceResourceIds` 要在 `setPendingResources([])` 之前取值。

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat: chat input attachment upload with preview"
```

---

### 任务 12：历史图片选择入口

**文件：**
- 修改：`frontend/app/chat/page.tsx`

- [ ] **步骤 1：收集当前会话已生成的 IMAGE 消息**

```ts
const generatedImages = useMemo(() => {
  return messages
    .filter(m => m.messageType === "IMAGE" && m.resources && m.resources.length > 0)
    .flatMap(m => m.resources!.map(r => ({ ...r, messageId: m.id })));
}, [messages]);
```

- [ ] **步骤 2：实现选择弹窗**

点击 📎 时如果 `generatedImages.length > 0`，显示选择菜单（上传本地 / 从历史选择）。历史选择弹出缩略图网格，点击后将该资源加入 `pendingResources`。

- [ ] **步骤 3：Commit**

```bash
git add -A && git commit -m "feat: history image picker for reference images"
```

---

### 任务 13：全链路集成测试

- [ ] **步骤 1：运行所有后端测试**

运行：`cd backend && mvn test -q`
预期：全部 PASS

- [ ] **步骤 2：运行前端构建**

运行：`cd frontend && npm run build`
预期：构建成功

- [ ] **步骤 3：手动测试图生图完整链路**

1. 启动后端 + 前端
2. 上传图片 → 看到缩略图
3. 输入 `/image 戴墨镜` → 发送
4. 验证用户消息显示参考图 + AI 返回生成图
5. 刷新页面 → 验证历史消息正确展示

- [ ] **步骤 4：最终 Commit**

```bash
git add -A && git commit -m "feat: image-to-image feature complete"
```
