# 聊天资源体系与图片生成首发设计

## 背景

当前系统已经具备文生图能力：用户可以通过 `/image` 命令或 LLM 工具调用触发 `ImageGenerationTool`，后端调用 MiniMax `POST /v1/image_generation`，生成图片落到自有资源存储，并以聊天消息返回前端。

这次需求不是只补一个图生图参数，而是要把聊天里的多媒体资源边界先定稳：

1. 现有文生图能力需要扩展为可选参考图生成，即“图片 + 描述词生成图片”。
2. 用户输入和 Agent 回复都需要支持资源附件。
3. 当前首发图片，后续还会加入视频、音频和其他文件。
4. 前端聊天流还会演进，不能把设计绑定到某一种现有气泡或 SSE 事件形态。
5. 上层聊天模型应保持接近 LangChain4j 的 `Content` / `Image` / `Audio` / `Video` 习惯，避免未来更换底层模型供应商时改动业务层。

因此，本设计将原“图生图功能扩展”升级为“聊天资源体系 + 图片生成首发”。图生图只是第一批落地能力，资源和内容对象需要先按多模态通用方式设计。

## 目标

1. 扩展现有图片生成链路，支持不传参考图的文生图和传 1 张参考图的图生图。
2. 建立统一聊天资源模型，让用户消息和 Agent 消息都能携带图片、视频、音频、文件等资源。
3. 上传接口只负责创建资源并返回 `resourceId`，不负责提前绑定 `messageId`。
4. 消息真正落库时，再把一个或多个资源绑定到这条消息。
5. 上层对象参考 LangChain4j：消息由多个 `Content` 组成，媒体对象统一表达 `url`、`base64Data`、`mimeType`，供应商适配层负责转换成 MiniMax、OpenAI 或其他模型需要的请求格式。
6. 前端按统一 `resources` / `contents` 渲染，不对图片生成写死特殊聊天流。

## 非目标

1. 第一版不实现视频生成、音频生成、音频识别或视频理解。
2. 第一版不支持一次传多张参考图给 MiniMax 图生图。
3. 第一版不做复杂附件管理、资源搜索、素材库分类。
4. 第一版不做后台异步任务队列，但资源和消息模型需要能承接后续异步生成。
5. 第一版不要求所有 LangChain4j `Content` 类型完整落地 PDF 等能力，只保留扩展口。

## 外部约束

### LangChain4j 内容模型

LangChain4j 在 `dev.langchain4j.data` 下的相关对象提供了很好的上层抽象参考：

1. `Content` 是消息内容的统一接口，通过 `ContentType` 区分 `TEXT`、`IMAGE`、`AUDIO`、`VIDEO`、`PDF`。
2. `Image` 表达图片数据，核心字段是 `url`、`base64Data`、`mimeType`，并带有图片生成后的 `revisedPrompt`。
3. `Audio` 表达音频数据，支持 `url`、`binaryData`、`base64Data`、`mimeType`。
4. `Video` 表达视频数据，支持 `url`、`base64Data`、`mimeType`。
5. `ImageContent`、`AudioContent`、`VideoContent` 把媒体对象包装成消息内容，模型适配层再决定如何发送给供应商。

我们的聊天层不应直接暴露 MiniMax 的 `subject_reference`、OpenAI 的图片输入格式或其他供应商私有字段。聊天层只表达“这条消息包含哪些内容和资源”，模型适配层负责供应商格式转换。

### MiniMax 图片生成接口

MiniMax 文生图和图生图共用 `POST https://api.minimaxi.com/v1/image_generation`。

纯文生图请求继续使用：

1. `model`
2. `prompt`
3. `aspect_ratio`
4. `response_format`
5. `n`
6. `prompt_optimizer`

图生图额外传 `subject_reference` 数组。当前可用结构：

1. `type`：必填，当前仅支持 `character`
2. `image_file`：必填，支持公网 URL 或 Base64 Data URL，例如 `data:image/jpeg;base64,...`

图片要求：

1. JPG、JPEG、PNG
2. 小于 10MB
3. 最佳效果是单人正面照片
4. 当前每次请求只支持一张参考图

MiniMax 约束只能出现在 `MiniMaxImageClient` 或图片生成适配层，不应该成为前端或聊天消息模型的基础字段。

## 核心设计

### 1. 资源和消息绑定解耦

上传接口只创建资源，不创建聊天消息，也不提前绑定 `messageId`。

用户选择本地图片、视频或音频时：

1. 前端调用 `POST /api/chat/resources/upload`
2. 后端保存文件并创建资源记录
3. 后端返回 `resourceId`、`kind`、`mimeType`、`viewUrl`、`downloadUrl` 等元数据
4. 前端把这个 `resourceId` 放入待发送附件列表

用户真正发送消息时：

1. `POST /api/chat/messages/stream` 携带 `resourceIds` 或 `referenceResourceIds`
2. 后端先写入用户消息，拿到真实 `messageId`
3. 后端再把这些 `resourceId` 绑定到该 `messageId`
4. 历史消息查询通过 `messageId` 加载资源并返回给前端

Agent 回复同理：

1. Agent 文本回复落库后，可以绑定由工具或模型产生的资源。
2. 图片生成工具生成的新图片在 `appendImageMessage` 或更通用的 `appendAssistantMessage(..., resourceIds)` 中绑定到 Agent 消息。
3. 未来视频、音频工具也复用同一套消息资源绑定机制。

### 2. 推荐数据模型

当前项目还未上线，资源体系先采用单表模型，避免过早拆出两张高度相似的表。`chat_message_resources` 表示“聊天资源记录”，其中 `message_id` 可空：

1. 上传阶段创建资源记录，`message_id = null`。
2. 消息落库后，把资源记录的 `message_id` 更新为真实消息 ID。
3. Agent 生成资源时已经知道消息上下文，可以在消息落库时直接插入带 `message_id` 的资源记录。
4. 如果未来出现同一个资源被多条消息复用、素材库、资源权限继承、资源清理策略复杂化等需求，再拆分为资源本体表和消息资源关系表。

资源表：`chat_message_resources`

| 字段 | 含义 |
|------|------|
| `id` | 资源 ID |
| `message_id` | 聊天消息 ID，可空；空表示已上传但还未发送 |
| `user_id` | 资源所有者 |
| `session_id` | 创建资源时所在会话，可空但建议上传时传入 |
| `resource_kind` | `IMAGE` / `AUDIO` / `VIDEO` / `FILE` |
| `storage_type` | `LOCAL_FILE` / `OSS` / `MANAGED_URL` |
| `storage_key` | 存储定位 |
| `view_url` | 预览地址 |
| `download_url` | 下载地址 |
| `mime_type` | MIME 类型 |
| `file_name` | 文件名 |
| `file_size` | 文件大小 |
| `width` | 图片或视频宽度 |
| `height` | 图片或视频高度 |
| `duration_ms` | 音频或视频时长，后续可补 |
| `created_at` | 创建时间 |

`sha256` 不是密钥，也不是加密字段，只是文件内容摘要，常用于去重、完整性校验或审计。当前没有去重和完整性校验需求，因此不作为首版必要字段。

### 3. 统一内容对象

后端上层引入接近 LangChain4j 习惯的聊天内容对象。名称可按项目风格调整，但语义建议如下。

```java
public sealed interface ChatContent permits TextChatContent, MediaChatContent {
    ChatContentType type();
}

public enum ChatContentType {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    FILE
}

public record TextChatContent(String text) implements ChatContent {
    @Override
    public ChatContentType type() {
        return ChatContentType.TEXT;
    }
}

public record MediaChatContent(
        ChatContentType type,
        String resourceId,
        String url,
        String base64Data,
        String mimeType,
        String fileName,
        Long fileSize,
        Integer width,
        Integer height,
        Long durationMs
) implements ChatContent {
}
```

关键约束：

1. 图片、音频、视频都使用同一种 `MediaChatContent` 结构。
2. `url` 是受管预览 URL 或供应商可访问 URL。
3. `base64Data` 只在模型适配层需要时按需生成，不默认塞进前端 DTO。
4. `mimeType` 是模型适配和前端渲染的主要分发依据。
5. `resourceId` 是业务引用，不与模型供应商绑定。

### 4. 供应商适配层负责转换

图片生成服务接收通用 `MediaChatContent` 或 `resourceId`，内部转换为 MiniMax 所需格式。

纯文生图：

1. 没有参考媒体
2. `MiniMaxImageGenerationRequest.subjectReference = null`

图生图：

1. 使用第一张 `type=IMAGE` 且 `usageType=REFERENCE` 的资源
2. 通过 `ResourceStorage.open(storageKey)` 读取字节
3. 转为 Base64 Data URL
4. 构造 `SubjectReference("character", dataUrl)`
5. 只在 `MiniMaxHttpImageClient` 请求体里生成 `subject_reference`

未来换模型时，只替换适配层：

1. OpenAI 风格模型可以转成 `ImageContent` 或模型需要的图片输入格式。
2. 支持公网 URL 的供应商可以用 `url`。
3. 只接受 Base64 的供应商可以用 `base64Data`。
4. 业务层仍然只传 `MediaChatContent` 或 `resourceId`。

### 5. 消息 API

请求体建议从图片专用 `referenceResourceIds` 逐步升级为通用 `resources` 或 `resourceIds`。

第一版兼容方案：

```json
{
  "message": "帮我生成戴墨镜的版本",
  "sessionId": "xxx",
  "promptId": 1,
  "agentId": "standard",
  "referenceResourceIds": ["r1"]
}
```

目标方案：

```json
{
  "message": "帮我生成戴墨镜的版本",
  "sessionId": "xxx",
  "promptId": 1,
  "agentId": "standard",
  "resources": [
    {
      "resourceId": "r1",
      "usageType": "REFERENCE"
    }
  ]
}
```

目标方案的好处：

1. 同一条消息可以同时有普通附件和生成参考素材。
2. 图片、视频、音频不需要不同字段。
3. Agent 未来也能返回 `usageType=GENERATED` 或 `ATTACHMENT` 的资源。
4. 前端可以只按 `resources` 渲染，不关心资源来自用户还是 Agent。

### 6. 上传接口

`POST /api/chat/resources/upload`

职责：

1. 鉴权。
2. 接收 multipart 文件。
3. 校验 MIME 白名单和大小。
4. 保存到 `ResourceStorage`。
5. 写入资源本体。
6. 返回资源 DTO。

请求字段：

1. `file`：必填。
2. `sessionId`：建议传入，用于归属和清理，但不代表绑定消息。

响应：

```json
{
  "resourceId": "r1",
  "type": "IMAGE",
  "url": "/api/chat/resources/r1/content",
  "viewUrl": "/api/chat/resources/r1/content",
  "downloadUrl": "/api/chat/resources/r1/download",
  "fileName": "photo.jpg",
  "mimeType": "image/jpeg",
  "fileSize": 102400,
  "width": 1024,
  "height": 1024,
  "durationMs": null
}
```

上传接口不接收 `messageId`，因为上传发生时消息可能还不存在。强行传 `messageId` 会把前端交互流程绑死，也会阻碍用户先选附件再输入内容的体验。

### 7. 消息落库与资源绑定

用户消息：

1. `appendUserMessage(userId, sessionId, text, resources)`
2. 先落 `chat_session_messages`
3. 校验每个 `resourceId` 属于当前用户，且允许用于当前会话
4. 更新这些资源记录的 `message_id`，完成资源与消息绑定
5. 返回消息 DTO 时带上资源 DTO

Agent 回复：

1. 文本回复使用 `appendAssistantMessage(...)`
2. 带资源回复使用 `appendAssistantMessage(..., resourceIds)`
3. 图片生成可以继续保留 `appendImageMessage` 作为兼容封装，但内部应走同一套资源绑定逻辑
4. 未来视频/音频工具复用同一套方法，不再新增专用 `appendVideoMessage`、`appendAudioMessage`，除非有明确的领域语义

### 8. 工具调用

`ImageGenerationTool` 保留显式可选资源入参，但语义改成资源引用，不是 MiniMax 专用参考图。

```java
@Tool("根据描述生成图片。可选传入 referenceResourceId，表示基于该资源生成新图片；不传则为纯文生图。")
public String generateImage(
        @ToolMemoryId String memoryId,
        String prompt,
        String referenceResourceId
) {
}
```

执行规则：

1. `referenceResourceId` 为空时，纯文生图。
2. 非空时，工具先校验资源属于当前用户。
3. 资源必须是图片类型，当前 MiniMax 首发只取一张。
4. 工具把资源交给图片生成服务，图片生成服务再转为 MiniMax `subject_reference`。

普通消息带资源时，不在 `runChatStream` 写死意图判断。Executor 只给 LLM 增强上下文：

```text
用户附带了以下资源：
- r1: IMAGE, image/jpeg, usage=REFERENCE

如需基于参考图生成新图片，请调用 generateImage 并传入 referenceResourceId。
```

这段增强信息不写入用户原始消息，只用于本次模型调用。

### 9. 前端渲染

前端渲染层以统一 `resources` 为核心，不再以 `IMAGE` 消息类型决定一切。

建议类型：

```ts
export type ChatMessageResource = {
  id: string;
  type: "IMAGE" | "AUDIO" | "VIDEO" | "FILE";
  usageType?: "ATTACHMENT" | "REFERENCE" | "GENERATED" | "THUMBNAIL";
  url?: string;
  viewUrl: string;
  downloadUrl: string;
  fileName: string;
  mimeType: string;
  fileSize?: number;
  width?: number;
  height?: number;
  durationMs?: number;
};
```

`MediaContent` 按 `mimeType` 或 `type` 渲染：

1. `image/*` 或 `IMAGE`：图片预览
2. `video/*` 或 `VIDEO`：`<video controls>`
3. `audio/*` 或 `AUDIO`：`<audio controls>`
4. 其他：文件卡片 + 下载

用户消息和 Agent 消息都使用同一个 `MediaContent`。

`messageType=IMAGE` 可以作为兼容旧数据的展示语义继续存在，但新资源渲染不应依赖它。长期建议用：

1. `role` 表达消息归属：`user` / `assistant` / `system`
2. `messageType` 表达消息语义：`TEXT` / `REASONING` / `MEDIA` / `SYSTEM`
3. `resources` 表达资源内容和渲染数据

## 数据流

### 本地上传 + `/image`

1. 用户选择图片。
2. 前端上传文件，获得 `resourceId=r1`。
3. 前端发送 `/image 戴墨镜的版本`，携带 `resources=[{resourceId:r1, usageType:REFERENCE}]`。
4. 后端写入用户消息。
5. 后端绑定 `r1` 到用户消息。
6. `/image` 命令直接调用图片生成服务。
7. 图片生成服务读取 `r1`，适配成 MiniMax `subject_reference`。
8. MiniMax 返回生成图。
9. 后端保存生成图资源。
10. 后端写入 Agent 媒体消息并绑定生成图资源。
11. SSE 返回资源消息事件，前端按 `MediaContent` 展示。

### 本地上传 + 普通消息

1. 用户上传图片并发送“帮我生成戴墨镜版本”。
2. 后端写入用户消息并绑定资源。
3. Executor 给 LLM 增强资源上下文。
4. LLM 自主决定是否调用 `generateImage(prompt, referenceResourceId)`。
5. 如果调用，工具生成图片并写入 Agent 资源消息。
6. 如果不调用，正常返回文本，用户附件仍保留在用户消息气泡里。

### 历史生成图再利用

首版单表模型下，一个资源记录只绑定一条消息，因此不直接支持把历史消息里的同一个 `resourceId` 再绑定到新消息。历史生成图再利用有两条后续路线：

1. 拆出资源本体表和消息资源关系表，让同一个资源可被多条消息引用。
2. 将历史资源复制为新的资源记录，再绑定到当前消息。

在聊天流尚未确定之前，首版先聚焦本地上传资源和当轮生成资源的绑定。

## 错误处理

### 上传失败

1. 未登录或登录态失效：返回 `40100 Unauthorized`，前端提示重新登录或刷新会话。
2. MIME 不支持：返回业务错误，前端展示“不支持该文件类型”。
3. 文件过大：返回业务错误，前端展示大小限制。
4. 存储失败：返回上传失败，不创建资源记录。

### 资源绑定失败

1. `resourceId` 不存在：本次发送失败，提示资源不存在或已过期。
2. 资源不属于当前用户：拒绝绑定。
3. 资源类型不符合使用场景：例如图生图只接受图片，返回明确错误。

不要静默降级为纯文生图。用户明确附带参考图时，静默降级会造成结果不可解释。

### 模型调用失败

1. MiniMax 返回错误：SSE 返回 `error`，不写生成媒体消息。
2. 参考图读取失败：SSE 返回 `error`，不调用 MiniMax。
3. 工具调用异常：Agent run 标记失败，并返回用户可理解的错误。

## 安全与权限

1. 上传、预览、下载、消息绑定都必须鉴权。
2. 资源读取必须校验 `resource.userId == currentUserId`。
3. 资源绑定必须校验用户对资源有权限。
4. 上传文件名需要过滤路径分隔符和控制字符。
5. MIME 白名单和文件大小限制由配置控制。
6. 未绑定消息的临时资源需要定时清理。

## 配置

第一版图片首发：

```yaml
chat:
  resources:
    upload:
      allowed-mime-types: image/jpeg,image/png
      max-file-size: 10485760
```

未来开放视频和音频时追加：

```yaml
chat:
  resources:
    upload:
      allowed-mime-types: image/jpeg,image/png,video/mp4,audio/mpeg,audio/wav
```

## 测试策略

### 后端

1. 上传接口：鉴权、MIME 校验、大小校验、资源本体写入。
2. 消息绑定：用户消息绑定资源，Agent 消息绑定资源，非法资源拒绝。
3. 历史消息：按 `messageId` 返回正确资源列表。
4. MiniMax 适配：有参考图片时构建 `subject_reference`，无参考图片时保持文生图请求。
5. 工具调用：`referenceResourceId` 为空和非空两条路径。
6. 错误路径：资源不存在、类型不匹配、读取失败。

### 前端

1. 上传后生成待发送资源，发送后清空。
2. 用户消息立即展示附件。
3. 历史消息恢复附件展示。
4. `MediaContent` 按图片、视频、音频、文件分发渲染。
5. 历史生成图可作为参考资源再次发送。

## 实施顺序

1. 使用单表资源模型，允许上传记录 `message_id` 为空。
2. 上传接口改为只创建资源，不接收 `messageId`。
3. 消息请求支持通用 `resources`，兼容旧的 `referenceResourceIds`。
4. `appendUserMessage` 支持消息落库后绑定资源。
5. `appendAssistantMessage` 或统一消息写入服务支持 Agent 消息绑定资源。
6. 图片生成服务从资源模型读取参考图片，适配成 MiniMax `subject_reference`。
7. `ImageGenerationTool` 使用 `referenceResourceId`，但不暴露 MiniMax 字段。
8. 前端输入框使用统一待发送资源列表。
9. 前端用户消息和 Agent 消息统一使用 `MediaContent`。
10. 增加临时未绑定资源清理策略。

## 设计修正点

相较原图生图方案，本版做了以下修正：

1. 不再要求 `/upload` 传 `messageId`。上传时消息还不存在，强行传会破坏聊天输入体验。
2. 不再把上传接口写入 `message_id = null` 视为异常；它是当前单表模型下的未绑定资源状态。
3. 不再通过复制资源记录表达绑定；绑定应更新原资源记录的 `message_id`。
4. 不再让前端按 `IMAGE` 消息类型推断所有媒体展示。统一按 `resources` 的 `mimeType` / `type` 渲染。
5. 不再让 MiniMax 的 `subject_reference` 进入聊天层对象。供应商私有格式只存在于适配层。
6. 不再静默降级参考图失败。用户提供参考资源时，失败应可见。

## 结论

本方案以 LangChain4j 的多模态内容模型为参考，把聊天系统抽象为“消息包含内容，内容可引用资源”。图片生成只是第一批能力：纯文生图不带资源，图生图带一个图片参考资源；未来视频、音频和文件都复用同一套资源对象、消息绑定和前端渲染机制。

这样设计后，底层模型供应商可以从 MiniMax 切换到 OpenAI、其他图像模型或多模态模型，上层聊天流仍只面对统一的 `ChatContent` 和 `ChatMessageResource`，不会因为供应商请求格式变化而大面积改动。
