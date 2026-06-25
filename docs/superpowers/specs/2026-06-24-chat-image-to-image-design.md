# 图生图功能扩展设计

## 背景

当前系统已具备文生图能力（`POST /v1/image_generation` + `ImageGenerationTool`），通过 `/image` 命令或 LLM 工具调用触发，生成图片并以 `IMAGE` 消息类型展示在聊天流中。

本次需求是扩展为图生图能力，并面向未来多模态（视频、音频）做通用化设计。核心约束：

1. 扩展现有文生图接口，不新建 API 端点。
2. 参考图输入采用双通道：本地文件上传 + 历史已生成图片选择。
3. 参考图通过 MiniMax `subject_reference` 参数传入，支持 Base64 Data URL。
4. `/image` 命令和非 `/image` 普通消息都支持图生图。
5. 非 `/image` 消息带图时，由 LLM 工具调用自主决定是否生成图片。
6. 先支持 1 张参考图，多张留待以后。
7. 前端聊天流需通用化：用户消息和 AI 回复都能携带并渲染多媒体资源（图片、未来视频/音频）。

## 目标

1. 为 MiniMax 图片生成请求增加 `subject_reference` 支持，实现图生图。
2. 新增通用文件上传端点，支持本地上传参考图（当前仅图片，后续扩展视频/音频）。
3. 前端聊天输入框支持上传本地文件 + 选择历史生成图片作为附件。
4. 用户消息在聊天记录中展示附件资源（参考图缩略图等）。
5. `ImageGenerationTool` 入参增加可选 `referenceResourceId`，LLM 自主决定是否传参。
6. 前端渲染层通用化：按 `mimeType` 分发渲染（图片/视频/音频/文件）。
7. 保持现有文生图链路不受影响。

## 非目标

1. 不支持一次传入多张参考图。
2. 不实现视频/音频生成功能（仅预留通用化结构）。
3. 不做参考图的裁剪、滤镜等编辑功能。
4. 不做历史图片搜索或分类。

## 外部约束

### MiniMax 图生图接口

与文生图共用 `POST https://api.minimaxi.com/v1/image_generation`，图生图额外传 `subject_reference` 数组。

`ImageSubjectReference` 结构：

1. `type`：string，必填，仅支持 `character`（人像）
2. `image_file`：string，必填，支持公网 URL 或 Base64 Data URL（`data:image/jpeg;base64,...`）

图片要求：

1. 格式：JPG、JPEG、PNG
2. 大小：小于 10MB
3. 最佳效果：单人正面照片
4. 每次请求仅支持一张参考图

[MiniMax 图生图 API](https://platform.minimaxi.com/docs/api-reference/image-generation-i2i)

### 现有实现可复用点

| 现有资产 | 位置 | 复用方式 |
|---------|------|---------|
| `MiniMaxImageGenerationRequest` | `backend/.../image/` | 新增 `subjectReference` 字段 |
| `MiniMaxHttpImageClient` | `backend/.../image/` | 请求体构建改为可变 Map，条件加入 `subject_reference` |
| `ImageGenerationCommand` | `backend/.../service/` | 已有 `sourceResourceId` 字段 |
| `ImageGenerationTool` | `backend/.../tools/` | 入参加 `referenceResourceId`，@Tool 描述更新 |
| `chat_message_resources` 表 | 数据库 | `resource_kind` 字段已支持任意字符串 |
| `ChatMessageResourceMapper` | `backend/.../mapper/` | `selectByResourceId` 按 ID 查 storageKey |
| `ResourceStorage` | `backend/.../storage/` | `open(storageKey)` 读取字节流 |
| `ChatResourceService` | `backend/.../service/` | 鉴权 + 归属校验已封装 |
| `UiChatMessage.resources` | `frontend/lib/` | 已有字段，`toUiChatMessage` 已透传 |

## 设计决策

### 1. 通用文件上传端点

新增 `POST /api/chat/resources/upload`（multipart/form-data），接受任意文件，返回 `resourceId` + 元数据。

设计理由：

1. 前端先上传拿到 resourceId，再随消息发送，避免在消息体里塞 Base64。
2. 端点通用化，当前开放 `image/*`，后续按需放开 `video/*`、`audio/*`。
3. 上传的资源初始不关联任何消息，发送消息时通过 `referenceResourceIds` 关联。

后端根据 mimeType 自动归类 `resource_kind`：

1. `image/*` → `IMAGE`
2. `video/*` → `VIDEO`
3. `audio/*` → `AUDIO`
4. 其他 → `FILE`

### 2. 消息请求携带附件引用

`ChatMessageRequest` 新增可选字段 `referenceResourceIds`（`List<String>`）。

前端流程：

1. 用户选择本地文件 → 调用上传端点 → 拿到 resourceId
2. 用户选择历史图片 → 已有 resourceId
3. 发送消息时，`referenceResourceIds` 随消息一起提交

后端处理：

1. `appendUserMessage` 扩展：非空时查原上传记录 → 复制资源记录关联到用户消息（`resource_kind=REFERENCE`）
2. 图片生成链路：按 resourceId 查 `storageKey` → `ResourceStorage.open()` → 读字节 → 转 Base64 Data URL → 传入 `subject_reference`

### 3. LLM 工具调用决定意图

非 `/image` 消息带参考图时，不在 `runChatStream` 硬编码意图判断，而是让 LLM 在正常 agent 对话流程中自主决定是否调用 `ImageGenerationTool`。

实现方式：

1. `ImageGenerationTool.generateImage` 签名改为 `(memoryId, prompt, referenceResourceId)`
2. `referenceResourceId` 为可选参数，LLM 不传则为纯文生图
3. executor 在传给 LLM 的消息中注入参考图 ID 提示（不污染存储的用户消息原文）：
   ```
   {用户描述词}
   [系统：用户附带了一张参考图（资源ID: {id}），如需基于此图生成新图片，调用 generateImage 并传入该资源ID]
   ```
4. LLM 从增强消息中获取 ID，调用工具时显式传入

设计理由：

1. `referenceResourceId` 是请求级业务参数，非记忆组成部分，不应与 memoryId 耦合。
2. 显式入参比隐式注入（registry）更清晰。
3. LLM 自主判断意图比硬编码规则更灵活。

### 4. 前端渲染通用化

当前前端 `ImageMessageContent` 只渲染 `<img>`。改为通用 `MediaContent` 组件，按 `mimeType` 前缀分发：

1. `image/*` → `<img>` 标签
2. `video/*` → `<video controls>` 标签
3. `audio/*` → `<audio controls>` 标签
4. 其他 → 文件图标 + 下载链接

用户消息气泡和 AI 媒体消息都使用同一个 `MediaContent` 组件。

### 5. 用户消息展示附件

`RenderableTurn` 的 `user` 类型新增 `resources` 字段：

```ts
{ kind: "user"; id: string; content: string; resources?: ChatMessageResource[] }
```

`toRenderableTurns` 中 user 分支透传 `resources`。渲染层在文字下方展示附件缩略图/媒体。

## 架构

### 后端改动

#### ① 新增：资源上传 Controller + Service

`POST /api/chat/resources/upload`

职责：

1. 接收 multipart 文件
2. 校验 mimeType 白名单（当前仅 `image/jpeg`、`image/png`）
3. 校验文件大小（当前 ≤ 10MB）
4. 调用 `ResourceStorage.save()` 落盘
5. 写入 `chat_message_resources` 表（`messageId=null`，待消息发送时关联）
6. 返回 `{ resourceId, kind, viewUrl, downloadUrl, fileName, mimeType, fileSize }`

#### ② 修改：`ChatMessageRequest`

```java
public record ChatMessageRequest(
    @NotBlank String message,
    @NotBlank String sessionId,
    Long promptId,
    String agentId,
    List<String> referenceResourceIds  // 新增，可空
) {}
```

#### ③ 修改：`ChatSessionService.appendUserMessage`

扩展签名：

```java
Long appendUserMessage(Long userId, String sessionId, String message, List<String> referenceResourceIds);
```

实现逻辑（在现有 `appendUserMessage` 基础上追加）：

```java
// 现有逻辑不变：存用户文本消息，拿到 messageId
Long messageId = persistMessage(session, message);

// 新增：关联参考图资源
if (referenceResourceIds != null && !referenceResourceIds.isEmpty()) {
    for (String resourceId : referenceResourceIds) {
        // 查原上传记录（上传时 messageId=null, resource_kind=IMAGE）
        ChatMessageResourceEntity original = chatMessageResourceMapper.selectByResourceId(resourceId);
        if (original == null || !userId.equals(original.getUserId())) {
            continue;
        }
        // 复制新记录关联到用户消息（不修改原记录）
        ChatMessageResourceEntity ref = new ChatMessageResourceEntity();
        ref.setId(UUID.randomUUID().toString());
        ref.setMessageId(messageId);
        ref.setUserId(userId);
        ref.setSessionId(sessionId);
        ref.setResourceKind("REFERENCE");
        ref.setStorageType(original.getStorageType());
        ref.setStorageKey(original.getStorageKey());
        ref.setViewUrl(original.getViewUrl());
        ref.setDownloadUrl(original.getDownloadUrl());
        ref.setMimeType(original.getMimeType());
        ref.setFileName(original.getFileName());
        ref.setFileSize(original.getFileSize());
        ref.setWidth(original.getWidth());
        ref.setHeight(original.getHeight());
        ref.setSha256(original.getSha256());
        ref.setCreatedAt(now);
        chatMessageResourceMapper.insert(ref);
    }
}
```

关键约束：采用"一次上传、多次引用"实践，上传记录和引用记录是两条独立记录，指向同一 `storageKey`（同一份文件），禁止直接修改原记录的 `messageId` 或 `kind` 字段。

查询时 `selectByMessageIds` 按 `messageId` 查资源，用户消息的 `messageId` 下会查到 `kind=REFERENCE` 的记录，随消息返回前端。

#### ④ 修改：`MiniMaxImageGenerationRequest`

新增 `SubjectReference` 内部记录：

```java
public record MiniMaxImageGenerationRequest(
    String model, String prompt, String aspectRatio,
    String responseFormat, int n, boolean promptOptimizer,
    SubjectReference subjectReference  // 新增，可为 null
) {
    public record SubjectReference(String type, String imageFile) {}
}
```

#### ⑤ 修改：`MiniMaxHttpImageClient`

请求体构建从 `Map.of(...)` 改为可变 `HashMap`：

1. 现有 6 个字段照常放入
2. `subjectReference` 非 null 时，构建 `"subject_reference": [{ "type": ..., "image_file": ... }]` 加入

#### ⑥ 修改：`ImageGenerationTool`

```java
@Tool("根据描述生成图片并发送到聊天。若用户提供了参考图资源ID，传入该ID将基于参考图"
    + "生成保留人物特征的新图；不传 referenceResourceId 则为纯文生图。")
public String generateImage(
    @ToolMemoryId String memoryId,
    String prompt,
    String referenceResourceId  // 新增，可选
) {
    // referenceResourceId 可能为 null（纯文生图）
    // 非 null 时：查 storageKey → 读字节 → Base64 Data URL → 构建 SubjectReference
}
```

工具内部调用 `ImageSubAgentService` 时，使用完整构造函数传 `sourceResourceId`：

```java
imageSubAgentService.generateImage(
    new ImageSubAgentCommand(
        context.userId(), context.sessionId(), context.promptId(),
        prompt, "TOOL",
        referenceResourceId,  // sourceResourceId
        null,                 // parentImageMessageId
        "GENERATE"            // operationType
    )
);
```

`ImageSubAgentServiceImpl` 已正确将 `command.sourceResourceId()` 透传到 `ImageGenerationCommand.sourceResourceId()`，无需修改。

#### ⑦ 修改：`ImageGenerationServiceImpl`

`generateImage` 方法内：

1. `command.sourceResourceId()` 非 null 时（来自 /image 命令路径或工具调用）：
   - `ChatMessageResourceMapper.selectByResourceId(sourceResourceId)` 查 storageKey
   - `ResourceStorage.open(storageKey)` 读取字节
   - 转 Base64 Data URL：`data:image/jpeg;base64,{encoded}`
   - 构建 `SubjectReference("character", dataUrl)` 传入 `MiniMaxImageGenerationRequest`
2. 为 null 时走纯文生图（现有逻辑不变）

需要新增依赖注入 `ChatMessageResourceMapper`。

#### ⑦.5 修改：`ChatServiceImpl.emitImageCommandEvents`

`/image` 命令路径需要处理 `referenceResourceIds`：

1. `emitImageCommandEvents` 签名新增 `List<String> referenceResourceIds` 参数
2. 非空时取第一个 ID 作为 `sourceResourceId` 传入 `ImageGenerationCommand`
3. 调用链路：`ChatController.stream()` → `ChatServiceImpl.streamChat()` → `emitImageCommandEvents(sink, userId, promptId, sessionId, message, referenceResourceIds)`

#### ⑧ Executor 消息增强

`referenceResourceIds` 从请求到 LLM 消息的传递路径：

1. `ChatMessageRequest.referenceResourceIds()` → `ChatController.stream()`
2. `ChatController` 将 `referenceResourceIds` 传入 `ChatServiceImpl.streamChat()`
3. `ChatServiceImpl` 将 `referenceResourceIds` 传入 executor 调用（通过现有参数结构或新增参数）
4. `HAssistantStreamingExecutor` 在构建 LLM 消息时：
   - 检查 `referenceResourceIds` 是否非空
   - 非空时，在用户消息后追加系统提示（不修改存储层的用户消息原文）
   - 提示内容包含参考图资源 ID，引导 LLM 在需要时调用 `generateImage` 并传入该 ID

具体实现建议：executor 的 `streamChat` 方法签名新增 `List<String> referenceResourceIds` 参数（可空），在调用 `chatMemory.add()` 之前，若 `referenceResourceIds` 非空，构造增强用户消息：

```
{用户原始描述}
[系统：用户附带了一张参考图（资源ID: {id}），如需基于此图生成新图片，调用 generateImage 并传入该资源ID]
```

### 前端改动

#### ① 新增：文件上传 API

```ts
export function uploadChatResource(file: File) {
  const formData = new FormData();
  formData.append("file", file);
  return apiFetch<UploadedResource>("/api/chat/resources/upload", {
    method: "POST",
    body: formData,
  });
}
```

注意：此接口不能用 `Content-Type: application/json`，需要 multipart/form-data。`apiFetch` 需要支持或单独处理。

#### ② 修改：聊天输入框

在 `<textarea>` 左侧/上方新增：

1. 文件上传按钮（当前仅接受图片 `accept="image/jpeg,image/png"`）
2. 已选附件预览区（缩略图 + 删除按钮）
3. 历史图片选择入口（从当前会话已生成的 IMAGE 消息中选择）

状态新增：

```ts
const [pendingResources, setPendingResources] = useState<UploadedResource[]>([]);
```

发送时：

```ts
referenceResourceIds: pendingResources.map(r => r.resourceId)
```

发送后清空 `pendingResources`。

#### ③ 修改：`RenderableTurn` user 类型

```ts
| {
    kind: "user";
    id: string;
    content: string;
    resources?: ChatMessageResource[];
  }
```

#### ④ 修改：`toRenderableTurns` user 分支

```ts
turns.push({
  kind: "user",
  id: current.id,
  content: current.content,
  resources: current.resources ?? [],
});
```

#### ⑤ 新增：通用 `MediaContent` 组件

替代现有 `ImageMessageContent`，按 mimeType 渲染：

```tsx
function MediaContent({ content, resources }: {
  content: string;
  resources: ChatMessageResource[];
}) {
  return (
    <div className="space-y-3">
      {resources.map((resource) => (
        <MediaItem key={resource.id} resource={resource} alt={content || resource.fileName} />
      ))}
    </div>
  );
}

function MediaItem({ resource, alt }: { resource: ChatMessageResource; alt: string }) {
  if (resource.mimeType.startsWith("image/")) {
    return <img src={resource.viewUrl} alt={alt} className="..." />;
  }
  if (resource.mimeType.startsWith("video/")) {
    return <video src={resource.viewUrl} controls className="..." />;
  }
  if (resource.mimeType.startsWith("audio/")) {
    return <audio src={resource.viewUrl} controls className="..." />;
  }
  return <a href={resource.downloadUrl}>{resource.fileName}</a>;
}
```

#### ⑥ 修改：用户消息气泡渲染

用户消息渲染区从纯 `turn.content` 改为：

```tsx
{turn.kind === "user" ? (
  <div className="space-y-2">
    {turn.resources && turn.resources.length > 0 ? (
      <MediaContent content={turn.content} resources={turn.resources} />
    ) : null}
    <p>{turn.content}</p>
  </div>
) : ...}
```

#### ⑦ 修改：`buildChatSendPayload`

```ts
export function buildChatSendPayload(params: {
  message: string;
  sessionId: string;
  promptId: number | null;
  agentId: string;
  referenceResourceIds?: string[];  // 新增
}) { ... }
```

#### ⑧ 修改：`buildPendingAssistantTurn`

userMessage 增加 resources 透传，用于即时展示（乐观更新）：

```ts
userMessage: {
  id: `user-${seed}`,
  role: "user",
  messageType: "USER",
  content,
  resources: pendingResources,  // 新增
}
```

## 数据模型

### 资源上传记录

复用现有 `chat_message_resources` 表，上传时：

1. `message_id`：null（未关联消息）
2. `resource_kind`：按 mimeType 自动归类（`IMAGE` / `VIDEO` / `AUDIO` / `FILE`）
3. 其余字段正常填写

消息发送时，复制记录并关联：

1. `message_id`：指向用户消息 ID
2. `resource_kind`：改为 `REFERENCE`

### 用户消息资源记录

用户消息关联的资源在 `chat_message_resources` 中：

1. `resource_kind = REFERENCE`
2. `storage_key`、`view_url` 等与原上传记录一致
3. `message_id` 指向用户消息

### AI 生成图片资源记录

不变，`resource_kind = IMAGE`。

## 接口设计

### 1. 资源上传

`POST /api/chat/resources/upload`

请求：`multipart/form-data`，字段 `file`

响应：

```json
{
  "resourceId": "r-upload-1",
  "kind": "IMAGE",
  "viewUrl": "/api/chat/resources/r-upload-1/content",
  "downloadUrl": "/api/chat/resources/r-upload-1/download",
  "fileName": "photo.jpg",
  "mimeType": "image/jpeg",
  "fileSize": 102400
}
```

### 2. 聊天流（现有，扩展请求体）

`POST /api/chat/messages/stream`

```json
{
  "message": "帮我生成戴墨镜的版本",
  "sessionId": "xxx",
  "promptId": 1,
  "referenceResourceIds": ["r-upload-1"]
}
```

`referenceResourceIds` 为可选字段，不传或空数组时行为与现有一致。

### 3. 资源预览/下载

不变，复用现有 `GET /api/chat/resources/{resourceId}/content` 和 `GET /api/chat/resources/{resourceId}/download`。

## SSE 事件设计

不变。图生图结果仍通过现有 `image` 事件返回 `IMAGE` 消息。

## 详细流程

### 1. 本地上传 + /image 命令路径

1. 用户在输入框选择本地图片文件
2. 前端调用 `POST /api/chat/resources/upload` → 返回 `resourceId`
3. 前端展示缩略图预览
4. 用户输入 `/image 戴墨镜的版本` 并发送
5. 前端调用 `POST /api/chat/messages/stream`，body 含 `referenceResourceIds: ["r1"]`
6. 后端 `appendUserMessage`：存文本 + 关联资源记录（`REFERENCE`）
7. 识别 `/image` 命令 → 直接走 `ImageGenerationService`
8. `sourceResourceId` 非空 → 查 storageKey → 读字节 → Base64 → 构建 `SubjectReference`
9. 调用 MiniMax API
10. 存储生成图 → IMAGE 消息 → SSE `image` 事件

### 2. 本地上传 + 普通消息路径

1-5 同上
6. 后端 `appendUserMessage`：存文本 + 关联资源记录
7. 非 `/image` → 进入 LLM agent 流程
8. executor 注入增强消息：`{用户描述}\n[系统：用户附带参考图（资源ID: r1），如需生成新图调用 generateImage]`
9. LLM 决定调用 `generateImage(memoryId, "戴墨镜的人物", "r1")`
10. 工具内部：查 r1 → Base64 → SubjectReference → MiniMax API
11. IMAGE 消息落库 → SSE `image` 事件 → 工具返回短文本 → LLM 可选补充说明

### 3. 历史图片选择路径

1. 用户点击已生成的图片消息（已有 resourceId）
2. 前端将 `resourceId` 加入 `pendingResources`
3. 后续流程与"本地上传"相同（跳过上传步骤）

### 4. 纯文生图（无参考图）

与现有流程完全一致，`referenceResourceIds` 为空，`subjectReference` 为 null。

## 前端设计

### 输入框布局

```
┌──────────────────────────────────┐
│ [📎] [缩略图1 ×] [缩略图2 ×]    │  ← 附件预览区（有附件时显示）
│ ┌──────────────────────────┐ [发送] │
│ │ 输入描述词...              │       │
│ └──────────────────────────┘       │
└──────────────────────────────────┘
```

1. 📎 按钮：点击弹出选择菜单（上传本地文件 / 从历史图片选择）
2. 上传本地文件：触发 `<input type="file" accept="image/jpeg,image/png">`
3. 历史图片选择：弹出当前会话已生成的 IMAGE 消息缩略图列表
4. 已选附件以缩略图展示，可点 × 移除

### 用户消息气泡

```
┌──────────────────┐
│  [参考图缩略图]    │  ← MediaContent 渲染
│  帮我生成戴墨镜的  │  ← 文本内容
│  版本             │
└──────────────────┘
```

### AI 图片消息气泡

不变，继续使用 `MediaContent`（原 `ImageMessageContent`）渲染。

## 错误处理

### 上传失败

1. 文件超过 10MB → 前端拦截，提示"文件大小不能超过 10MB"
2. mimeType 不在白名单 → 后端返回 400，前端提示"暂不支持该文件类型"
3. 存储失败 → 后端返回 500，前端提示"上传失败，请重试"

### 参考图读取失败

1. resourceId 对应的文件不存在 → `ImageGenerationServiceImpl` 降级为纯文生图
2. 读取字节失败 → 同上降级

### MiniMax 调用失败

与现有文生图错误处理一致：返回 `error` 事件，不写 IMAGE 消息。

## 安全与权限

1. 上传端点必须鉴权。
2. 上传文件做 mimeType 白名单校验（当前仅 `image/jpeg`、`image/png`）。
3. 文件大小限制 10MB。
4. 文件名做安全过滤（去除路径分隔符、控制字符）。
5. `referenceResourceIds` 中的每个 ID 必须校验归属（只允许引用自己的资源）。

## 配置设计

### 上传白名单（新增）

```yaml
chat:
  resources:
    upload:
      allowed-mime-types: image/jpeg,image/png
      max-file-size: 10485760  # 10MB
```

后续扩展时只需在 `allowed-mime-types` 中追加 `video/mp4`、`audio/mpeg` 等。

## 测试策略

### 后端单测

1. 资源上传：mimeType 校验、文件大小校验、存储落盘
2. `appendUserMessage` 带 `referenceResourceIds`：资源记录关联正确
3. `MiniMaxHttpImageClient`：`subject_reference` 条件构建正确
4. `ImageGenerationTool`：`referenceResourceId` 为 null 和非 null 两种路径
5. Base64 Data URL 构建正确

### 后端集成测试

1. 上传 → 发消息带 referenceResourceIds → 用户消息关联 REFERENCE 资源
2. 图生图完整链路：上传 → /image 命令 → MiniMax mock → IMAGE 消息含资源
3. 历史图片选择：使用已生成图片的 resourceId 作为参考

### 前端测试

1. 文件上传 → 缩略图预览 → 发送后清空
2. 用户消息展示参考图缩略图
3. 历史图片选择 → 发送
4. 通用 `MediaContent` 按 mimeType 分发渲染

## 实施顺序

1. 后端：资源上传端点（Controller + Service + 配置）
2. 后端：`ChatMessageRequest` 加 `referenceResourceIds`
3. 后端：`appendUserMessage` 扩展支持关联资源
4. 后端：`MiniMaxImageGenerationRequest` 加 `SubjectReference`
5. 后端：`MiniMaxHttpImageClient` 请求体构建改为可变 Map
6. 后端：`ImageGenerationTool` 加 `referenceResourceId` 入参
7. 后端：`ImageGenerationServiceImpl` 支持参考图 Base64 读取
8. 后端：executor 消息增强（注入参考图 ID 提示）
9. 前端：上传 API + 输入框附件功能
10. 前端：`RenderableTurn` user 加 resources
11. 前端：通用 `MediaContent` 组件
12. 前端：用户消息气泡展示附件
13. 前端：历史图片选择入口

## 风险与缓解

### 1. Base64 大文件导致请求体过大

10MB 图片 Base64 编码后约 13.3MB，MiniMax API 可能有请求体大小限制。

缓解：第一版先测试实际可用性。若超限，考虑先上传到临时图床获取公网 URL。

### 2. LLM 不传 referenceResourceId

LLM 可能忽略参考图 ID，不调用工具或直接文生图。

缓解：优化 @Tool 描述和 executor 注入的系统提示，明确引导 LLM 在用户附带参考图时优先使用图生图。

### 3. 上传的孤立资源积累

用户上传后未发送消息的资源没有 messageId，会成为孤立记录。

缓解：定时清理任务，删除 `message_id IS NULL` 且创建时间超过 24 小时的资源记录及对应文件。

## 结论

本方案在现有文生图基础上，通过扩展 `subject_reference` 参数实现图生图，同时面向多模态做通用化设计：

1. 通用文件上传端点，按 mimeType 自动归类。
2. 消息请求携带 `referenceResourceIds`，后端关联到用户消息。
3. `ImageGenerationTool` 显式入参 `referenceResourceId`，LLM 自主判断意图。
4. 前端通用 `MediaContent` 组件，用户消息和 AI 消息统一渲染。
5. 当前仅开放图片上传，结构上已为视频/音频预留扩展位。
