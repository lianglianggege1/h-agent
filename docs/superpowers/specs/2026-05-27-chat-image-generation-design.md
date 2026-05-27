# Chat 文生图设计

## 背景

当前项目已经具备以下能力：

1. 前端聊天页通过 `POST /api/chat/messages/stream` 以 SSE 接收 assistant 流式文本。
2. 后端使用 `langchain4j AiServices + StreamingChatModel` 承载聊天、记忆、guardrail 和 tool calling。
3. 聊天消息表 `chat_session_messages` 已预留 `message_type` 与 `payload_json` 字段，但当前前后端仍按纯文本消息渲染。

本次需求是在现有聊天产品内加入文生图能力，并满足以下业务约束：

1. 显式命令和自然语言自动触发都要支持。
2. 生成结果在聊天框中以独立图片消息展示。
3. 聊天框内要支持下载生成图片。
4. 图片必须落到我们自己的服务器存储，不依赖大模型返回的临时 URL。
5. 资源访问层需要抽象，当前支持本地文件，后续可平滑切换到 OSS。
6. 第一版固定单次生成 1 张图。
7. 接口与表结构按“可任务化”设计，但第一版仍走同步生成返回。

## 目标

1. 为聊天系统新增文生图能力，支持 `/image ...` 显式触发。
2. 为 `langchain4j` assistant 新增图片生成工具，支持自然语言自动调用。
3. 将图片结果落盘到本地服务器，并通过受管资源地址提供预览与下载。
4. 将聊天消息升级为类型化富消息，新增 `IMAGE` 消息类型。
5. 保持现有文本聊天主链路稳定，不重写整套流式架构。
6. 为未来接入 OSS、异步任务、多图生成预留扩展位。

## 非目标

1. 第一版不支持一次生成多张图。
2. 第一版不支持图片编辑、图生图、局部重绘。
3. 第一版不做独立任务队列、重试队列、回调通知。
4. 第一版不对用户开放宽高比、seed、负面提示词等高级参数。
5. 第一版不接入 OSS，只预留抽象和数据结构。

## 外部约束

### MiniMax 文生图接口

采用 MiniMax 文生图接口 `POST /v1/image_generation`。官方文档示例显示该接口支持：

1. `model`
2. `prompt`
3. `aspect_ratio`
4. `response_format`
5. `n`
6. `prompt_optimizer`

并返回生成 `id`、图片数据以及成功/失败计数。[MiniMax 文生图 API](https://platform.minimaxi.com/docs/api-reference/image-generation-t2i)

第一版选择：

1. `model = image-01`
2. `n = 1`
3. `response_format = base64`
4. `aspect_ratio = 1:1`
5. `prompt_optimizer = true`

选择 `base64` 的原因是第一版必须将图片持久化到本地服务器；直接接收字节内容比依赖外部临时 URL 更稳。

### LangChain4j 工具调用

继续沿用当前 `AiServices + StreamingChatModel` 结构，通过 `@Tool` 暴露图片生成能力。LangChain4j 官方文档说明：

1. `@Tool` 方法可由模型按需调用。
2. `@ToolMemoryId` 可以将 AI Service 的 `@MemoryId` 传递给工具。
3. 当工具很少且始终相关时，普通工具暴露或 `ALWAYS_VISIBLE` 更适合，不必强依赖 tool search。

这正适合当前聊天架构的增量扩展。[LangChain4j Tools](https://docs.langchain4j.dev/tutorials/tools/)

## 设计决策

### 1. 双入口，共用一条图片生成链路

文生图支持两种入口：

1. 显式命令：用户输入 `/image 一只猫`
2. 自然语言：模型理解用户意图后自动调用图片工具

两种入口最终都调用同一个 `ImageGenerationService`，统一负责：

1. 调用 MiniMax
2. 将图片保存到资源存储
3. 持久化图片消息和资源记录
4. 生成前端所需的 `IMAGE` 消息 DTO

这样可以避免命令式入口与 tool 调用入口出现两套存储或消息格式。

### 2. 图片以独立消息展示

聊天框新增独立的 `IMAGE` 消息类型，不与文本消息混排在同一条 assistant 文本里。

原因：

1. 下载按钮和预览行为更清晰。
2. 历史消息回放更简单。
3. 失败态与占位态更容易管理。
4. 后续多图扩展可以在同类型消息内演进，不影响文本消息结构。

### 3. 前端只消费受管资源地址

数据库和前端都不保存、不依赖大模型返回的原始访问 URL。

统一由系统提供两类地址：

1. `viewUrl`：用于聊天框预览
2. `downloadUrl`：用于下载原图

当前由本地文件实现，未来可由 OSS 或网关实现，但前端契约保持不变。

### 4. 存储层抽象先于实现

资源访问抽象定义为独立接口，避免本地文件路径直接泄露到业务层。

第一版实现：

1. `LocalFileResourceStorage`

预留实现：

1. `OssResourceStorage`
2. `ManagedUrlResourceStorage`

这样后续迁移到 OSS 时，只需要替换存储实现和资源解析规则，不需要改聊天消息协议。

### 5. 业务按任务化建模，执行仍走同步

虽然第一版不引入后台任务队列，但生成服务内部仍按“任务 -> 资源 -> 消息”的思路组织。

原因：

1. 文生图耗时通常明显高于普通文本回复。
2. 未来切异步时，不希望重构数据库和服务边界。
3. 当前同步流程已足以满足第一版体验。

因此第一版表现为同步：

1. 用户请求进入后，前端先创建本地占位态
2. 服务端同步生成图片并落盘
3. 通过当前 SSE 响应返回一条 `image` 事件
4. 前端将占位态替换为正式图片消息

## 架构

### 后端模块

新增或调整以下模块：

1. `ImageGenerationService`
2. `MiniMaxImageClient`
3. `ResourceStorage`
4. `ChatResourceService`
5. `ImageGenerationTool`
6. `ChatMessagePayloadAssembler`

职责如下。

#### ImageGenerationService

职责：

1. 接收生成图片请求
2. 规范化 prompt 与生成参数
3. 调用 `MiniMaxImageClient`
4. 将返回图片字节交给 `ResourceStorage`
5. 写入图片资源记录
6. 写入 `IMAGE` 聊天消息
7. 组装 `ImageMessageDto`

接口建议：

```java
public interface ImageGenerationService {

    ImageGenerationResult generateImage(ImageGenerationCommand command);
}
```

`ImageGenerationCommand` 建议包含：

1. `userId`
2. `sessionId`
3. `promptId`
4. `prompt`
5. `triggerSource`
6. `requestedByMessage`

#### MiniMaxImageClient

职责：

1. 封装 MiniMax API 请求与响应解析
2. 屏蔽鉴权、基础 URL、超时、错误码细节
3. 返回标准化的图片生成结果

接口建议：

```java
public interface MiniMaxImageClient {

    MiniMaxImageGenerationResult generate(MiniMaxImageGenerationRequest request);
}
```

`MiniMaxImageGenerationResult` 建议至少包含：

1. `providerRequestId`
2. `imageBytes`
3. `mimeType`
4. `model`
5. `rawResponseJson`

#### ResourceStorage

职责：

1. 保存资源内容
2. 提供预览地址与下载地址
3. 支持按资源 ID 或存储键打开内容流

接口建议：

```java
public interface ResourceStorage {

    StoredResource save(ResourceSaveCommand command);

    ResourceContent open(String storageKey);

    String buildViewUrl(String resourceId);

    String buildDownloadUrl(String resourceId);
}
```

`StoredResource` 建议包含：

1. `storageType`
2. `storageKey`
3. `mimeType`
4. `fileName`
5. `fileSize`
6. `width`
7. `height`
7. `sha256`

#### ChatResourceService

职责：

1. 根据资源记录校验当前用户是否有访问权限
2. 将资源内容映射为 HTTP 响应
3. 为预览与下载接口统一设置响应头

#### ImageGenerationTool

作为 `langchain4j` 工具暴露给 assistant。

建议：

1. 标记为 `@Tool(searchBehavior = ALWAYS_VISIBLE)`
2. 使用 `@ToolMemoryId` 获取当前聊天上下文
3. 内部直接调用 `ImageGenerationService`
4. 返回短文本给模型，例如“图片已生成并发送到聊天中”

这样自然语言触发不会绕开现有 AI Service 框架。

#### ChatMessagePayloadAssembler

职责：

1. 将领域消息转换为统一 DTO
2. 为 `IMAGE` 消息拼出 `viewUrl`、`downloadUrl`
3. 封装 `payload_json` 与资源记录的映射逻辑

该模块用于避免控制器或 mapper 直接了解存储实现。

### 前端模块

新增或调整以下前端能力：

1. `ChatMessage` 类型升级为富消息联合类型
2. `apiStream` 新增 `onImage`
3. `chat-sessions` DTO 支持读取图片消息
4. 聊天页新增 `ImageMessageCard`

## 数据模型

### 聊天消息

继续使用 `chat_session_messages`，但前后端正式启用 `message_type`。

建议取值：

1. `TEXT`
2. `BLOCKED`
3. `IMAGE`

字段约定：

1. `role_code`
   - `user`
   - `assistant`
2. `content_text`
   - `TEXT`：保存文本内容
   - `BLOCKED`：保存拦截原因
   - `IMAGE`：可保存展示文案，也可为空；第一版建议保存原始 prompt，方便历史可读性
3. `payload_json`
   - `IMAGE`：保存轻量元数据

`IMAGE payload_json` 建议字段：

1. `prompt`
2. `provider`
3. `providerRequestId`
4. `model`
5. `aspectRatio`
6. `status`
7. `triggerSource`

### 资源表

新增 `chat_message_resources` 表。

建议字段：

1. `id`
2. `message_id`
3. `user_id`
4. `session_id`
5. `resource_kind`
6. `storage_type`
7. `storage_key`
8. `view_url`
9. `download_url`
10. `mime_type`
11. `file_name`
12. `file_size`
13. `width`
14. `height`
15. `sha256`
16. `created_at`

字段说明：

1. `resource_kind`
   - 第一版固定为 `IMAGE`
2. `storage_type`
   - `LOCAL_FILE`
   - `MANAGED_URL`
   - `OSS`
3. `storage_key`
   - 真实存储定位信息
   - 本地文件实现下不直接暴露给前端
4. `view_url` 与 `download_url`
   - 保存对前端稳定的访问地址
   - 即使后续换 OSS，也不依赖模型原始 URL

### 任务化预留

第一版可不单独建任务表，但 `payload_json.status` 与 `providerRequestId` 应保留。

若后续需要独立任务表，建议命名为 `image_generation_tasks`，与 `chat_message_resources` 形成一对多关系。

## 接口设计

### 1. 聊天流接口

继续使用：

`POST /api/chat/messages/stream`

请求体保持现状：

```json
{
  "message": "/image 一只白猫",
  "sessionId": "xxx",
  "promptId": 1
}
```

服务端行为：

1. 当消息以 `/image ` 开头时，不进入 LLM 文本回答链路。
2. 直接解析 prompt 并走 `ImageGenerationService`。
3. 通过 SSE 返回：
   - `image`：图片消息
   - `done`：结束

### 2. 资源预览接口

新增：

`GET /api/chat/resources/{resourceId}/content`

职责：

1. 鉴权
2. 校验资源归属
3. 返回图片内容
4. 设置 `Content-Type`
5. 支持浏览器直接预览

### 3. 资源下载接口

新增：

`GET /api/chat/resources/{resourceId}/download`

职责：

1. 鉴权
2. 校验资源归属
3. 返回附件下载头
4. 使用保存时记录的文件名

### 4. 历史消息接口

现有历史消息查询接口返回 DTO 需要扩展：

1. `messageType`
2. `payload`
3. `resources`

这样前端加载历史消息时能正确还原图片卡片。

## SSE 事件设计

在现有事件基础上新增：

1. `image`

完整事件集：

1. `chunk`
2. `blocked`
3. `error`
4. `image`
5. `done`

建议 `image` 事件数据：

```json
{
  "type": "image",
  "content": "",
  "message": {
    "id": "123",
    "role": "assistant",
    "messageType": "IMAGE",
    "content": "一只白猫",
    "payload": {
      "prompt": "一只白猫",
      "provider": "MINIMAX",
      "model": "image-01"
    },
    "resources": [
      {
        "id": "r1",
        "kind": "IMAGE",
        "viewUrl": "/api/chat/resources/r1/content",
        "downloadUrl": "/api/chat/resources/r1/download",
        "fileName": "generated-cat.png",
        "mimeType": "image/png",
        "width": 1024,
        "height": 1024
      }
    ]
  }
}
```

虽然第一版固定单图，事件数据仍使用 `resources` 数组，便于未来扩展。

## 详细流程

### 1. `/image` 命令路径

1. 前端提交聊天消息
2. `ChatService` 识别 `/image `
3. 校验命令后是否存在有效 prompt
4. 写入用户消息
5. 前端维持本地“正在生成图片...”占位态，服务端不额外持久化占位消息
6. 调用 `ImageGenerationService`
7. `ImageGenerationService` 调用 `MiniMaxImageClient`
8. 将 base64 解码后的图片内容保存到 `ResourceStorage`
9. 写入 `chat_message_resources`
10. 写入 `IMAGE` assistant message
11. SSE 发出 `image`
12. SSE 发出 `done`

### 2. 自然语言 tool 调用路径

1. 前端提交自然语言消息
2. `ChatService` 按现有流程调用 `HAssistant.streamChat(...)`
3. 模型判断需要生成图片时调用 `ImageGenerationTool`
4. 工具通过 `@ToolMemoryId` 获取当前上下文
5. 工具调用 `ImageGenerationService`
6. 图片资源与 `IMAGE` 消息落库
7. 工具返回短文本给模型
8. 模型可选择补一句简短说明，也可仅结束本轮输出
9. SSE 除 `chunk/done` 外，还需要把工具生成的图片消息同步发给前端

### 3. Tool 结果如何进入 SSE

这是本次改造里最需要明确的点。

由于当前 `TokenStream` 只显式暴露文本 chunk 回调，建议在工具执行过程中，由图片工具经由桥接层主动发出 `image` 事件，而不是等待模型文本流完成后再补发。

实现上建议通过“当前会话流事件发布器”完成，而不是让 tool 直接感知 Web 层。

可选实现方式：

1. 在 `ChatServiceImpl.streamChat(...)` 创建当前调用专属的 `ChatStreamEventPublisher`
2. 将其放入当前流调用可访问的上下文
3. `ImageGenerationTool` 执行成功后发布 `image` 事件

为降低复杂度，推荐：

1. 新增 `ChatStreamEventBridge`
2. 在单次流调用期间注册 publisher
3. tool 调用 `ChatStreamEventBridge.publishImage(...)`

这样能保持 tool 与具体 `FluxSink` 解耦。

## 前端设计

### 消息类型

将前端消息升级为联合类型：

```ts
type ChatMessage =
  | { id: string; role: "user"; type: "text"; content: string }
  | { id: string; role: "assistant"; type: "text"; content: string }
  | { id: string; role: "assistant"; type: "blocked"; content: string }
  | {
      id: string;
      role: "assistant";
      type: "image";
      content: string;
      resources: Array<{
        id: string;
        viewUrl: string;
        downloadUrl: string;
        fileName: string;
        mimeType: string;
        width: number | null;
        height: number | null;
      }>;
    };
```

### 占位态

首版建议保留统一 assistant 占位态，但图片生成时展示专用文案：

1. “正在生成图片...”

收到 `image` 事件后：

1. 删除或替换占位态文本消息
2. 插入正式 `image` 消息卡片

### 图片卡片

首版图片卡片包含：

1. 预览图
2. prompt 文案
3. 下载按钮

不做：

1. 二次编辑
2. 放大灯箱
3. 收藏、分享

## 配置设计

新增配置建议：

1. `minimax.image.base-url`
2. `minimax.image.api-key`
3. `minimax.image.model`
4. `minimax.image.aspect-ratio`
5. `minimax.image.prompt-optimizer`
6. `storage.local.base-dir`
7. `storage.public.base-url`

本地文件建议目录：

`<storage.local.base-dir>/generated-images/{yyyy}/{MM}/{dd}/`

文件命名建议：

`{sessionId}-{timestamp}-{random}.png`

## 错误处理

### 命令错误

场景：

1. `/image` 后没有 prompt

处理：

1. 不调用模型
2. 返回 `error` 事件
3. 不写 assistant 图片消息

### MiniMax 调用失败

处理：

1. 标记当前生成失败
2. 返回 `error` 事件
3. 保留用户消息
4. 不写无效资源记录

### 落盘失败

处理：

1. 视为本次生成失败
2. 若文件部分写入，立即清理
3. 不写入最终 `IMAGE` 消息

### 历史资源丢失

处理：

1. 历史消息仍显示图片消息卡片框架
2. 预览接口返回明确错误
3. 前端展示“资源暂不可用”

## 安全与权限

1. 资源访问必须鉴权。
2. 资源只能由所属用户访问。
3. 本地文件路径不得直接暴露给前端。
4. 下载文件名需要做安全过滤，避免响应头注入。
5. 仅允许服务端从可信配置目录读本地资源。

## 测试策略

### 后端单测

1. `/image` 命令识别
2. MiniMax 响应解析
3. base64 图片落盘
4. 资源 URL 生成
5. `IMAGE` 消息 DTO 组装
6. tool 调用 `@ToolMemoryId` 上下文映射

### 后端集成测试

1. 聊天流返回 `image` 事件
2. 资源预览接口鉴权
3. 资源下载接口返回下载头
4. 历史消息接口正确返回图片消息

### 前端测试

1. SSE `image` 事件解析
2. 图片卡片渲染
3. 下载按钮链接正确
4. 历史会话图片消息回放

## 实施顺序

1. 扩展数据模型与 migration
2. 新增资源存储抽象和本地文件实现
3. 接入 MiniMax 图片客户端
4. 实现 `ImageGenerationService`
5. 扩展 `ChatService` 支持 `/image`
6. 新增 `ImageGenerationTool`
7. 扩展 SSE 协议支持 `image`
8. 扩展历史消息 DTO
9. 前端富消息与图片卡片渲染
10. 增加资源预览/下载接口与测试

## 风险与缓解

### 1. Tool 生成图片后难以同步进当前 SSE

缓解：

1. 增加流调用级别的事件桥接层
2. 不让 tool 直接依赖 `FluxSink`

### 2. 本地存储路径与部署环境耦合

缓解：

1. 所有路径改为配置项
2. 业务层只依赖 `ResourceStorage`

### 3. 文本流与图片事件同时出现导致前端状态错乱

缓解：

1. 引入 `messageType`
2. 在前端显式区分占位态、文本消息、图片消息

## 结论

本方案采用“显式命令 + tool 自动调用”双入口，将图片生成、资源持久化、聊天消息渲染统一收口到同一条服务链路中：

1. 继续复用现有 `langchain4j` 聊天框架
2. 通过独立 `IMAGE` 消息类型承载图片结果
3. 通过受管资源地址支持预览与下载
4. 当前落本地文件，后续可平滑迁移到 OSS
5. 当前同步执行，后续可演进为异步任务
