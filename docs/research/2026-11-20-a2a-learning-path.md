# h-agent 的 A2A 学习路径评估

七个场景都值得保留。建议用三条业务链路承载它们：远端写作练任务与流，文档处理练文件和异步交付，Matrix 团队练发现、委派和授权。先验证协议交互，再把结果投影到聊天界面，这样能区分问题出在哪一层。

## 协议基线

仓库导入 LangChain4j BOM `1.22.0`。本地依赖中，`langchain4j-agentic-a2a` 解析为 `1.22.0-beta32`，依赖 A2A Java SDK `1.4.0.Final`，其中 `AgentInterface.CURRENT_PROTOCOL_VERSION` 是 `1.0`。`A2AAgentCardFactory` 用它声明 JSON-RPC 接口，目前显式关闭流、推送和扩展卡片。`JsonRpcA2ATransportWrapper` 目前只接受 `message/send` 与 `SendMessage`。建议以 A2A v1.0.1 规范为参考，线上版本标识使用 `A2A-Version: 1.0`。AgentCard 的 `version` 表示 Agent 自身版本。兼容旧客户端时，分别验证两版的请求名、数据结构和返回格式。 [A2A 1.0.1 规范](https://a2a-protocol.org/v1.0.1/specification/) · [A2A 0.3 规范](https://a2a-protocol.org/v0.3.0/specification/)

| 操作 | 0.3 JSON-RPC | 1.0 JSON-RPC |
| --- | --- | --- |
| 发消息 | `message/send` | `SendMessage` |
| 流式发消息 | `message/stream` | `SendStreamingMessage` |
| 查询任务 | `tasks/get` | `GetTask` |
| 取消任务 | `tasks/cancel` | `CancelTask` |
| 重新订阅任务 | `tasks/resubscribe` | `SubscribeToTask` |
| 获取扩展卡片 | `agent/getAuthenticatedExtendedCard` | `GetExtendedAgentCard` |

## 第一条链路：创作助手（2 → 1）

**2. 让远端拥有多轮任务。** 先用 `SendMessage` 跑通「写个太空故事」→ 远端在 `Task.status.message` 询问受众与风格 → 用户补充 → 同一任务完成。首次请求让服务端分配 `taskId`。补充时使用新的 `messageId`，带上原 `taskId` 和 `contextId`。用 `GetTask` 验证等待输入、恢复及最终产物，并验证未知任务、上下文不匹配和向终态任务发消息的错误响应。同一会话里的后续改写可以沿用 `contextId` 开新任务，并引用此前的任务。仓库当前的 `HumanInTheLoop` 位于 backend，`A2AAgentExecutor` 只保存调用结束时的记录，后者需要能读回中断的任务以及恢复所需的输入。 [A2A 任务生命周期](https://a2a-protocol.org/v1.0.1/topics/life-of-a-task/) · [A2A 1.0.1 规范第 3.4 节](https://a2a-protocol.org/v1.0.1/specification/)

**1. 再给同一任务加流。** 直接展示 `creative-writer` 的初稿，或者明确标记后续编辑版为独立产物。服务端先发任务快照，模型输出一段就发 `TaskArtifactUpdateEvent`。同一产物使用稳定的 `artifactId`，后续分片通过 `append` 追加，`lastChunk` 标记产物分片已结束，任务完成由任务状态表达。首段应在模型生成结束前到达。backend 接收事件后转成现有聊天流。当前 typed proxy 的调用接口返回 `String`，本地依赖源码有流监听器；可用监听器桥接，或直接用 A2A SDK 的事件接口。

断线时对活动任务调用 `SubscribeToTask`，用首个任务快照校准本地已收内容，再应用新事件。重连期间已经完成则用 `GetTask` 取最终产物。若产品需要按偏移量准确重播，需要自行约定游标或事件持久化。关闭 SSE 连接与请求取消任务是两个操作。 [A2A 流式与异步操作](https://a2a-protocol.org/v1.0.1/topics/streaming-and-async/) · [A2A 1.0.1 规范第 3.1.6、3.5 和 4.2.2 节](https://a2a-protocol.org/v1.0.1/specification/)

## 第二条链路：文档助手（4 → 3）

**4. 先把文件和结构化数据跑通。** 用一页小 PDF 验证文件输入，回传 `data` Part，包含页码、正文、OCR 状态及质量提示。给业务 JSON 定义 `schemaVersion` 和校验规则。1.0 的文件 Part 使用 `raw` 或 `url`，可附 `filename` 和 `mediaType`，结构化 Part 使用 `data`。卡片可按技能声明 `application/pdf` 输入和 `application/json` 输出。随后用插画 Agent 验证反方向，文本输入，返回带 `image/png` 的文件产物。MinIO 对象私有存储，Agent 可使用短期、受限的 HTTPS 下载链接或受信的资源网关。链接需要覆盖异步任务真正读取文件的时间窗口。聊天可以复用项目已有的资源访问方式来渲染图片。 [A2A 文件交换和结构化数据示例](https://a2a-protocol.org/v1.0.1/specification/)

**3. 再用扫描件验证异步交付。** backend 用 `SendMessage` 并设 `configuration.returnImmediately: true`，立即拿到任务 ID 和当时状态；`submitted`、`working` 都是合理演示结果。先用 `GetTask` 轮询验证结果与取消，再用 `taskPushNotificationConfig` 或 `CreateTaskPushNotificationConfig` 注册 backend 的 webhook。推送采用 `StreamResponse` 形状，可携带完整任务或更新事件。backend 验证来源，幂等记录进度，再按需 `GetTask` 获取完整结果。关闭浏览器后能否看到通知依赖应用侧的持久化通知、回到会话时的补发或浏览器通知机制。可以展示「关闭页面 → 远端完成并回调 → 重新打开页面看到结果」。 [A2A 推送配置与负载](https://a2a-protocol.org/v1.0.1/specification/) · [流式与异步操作](https://a2a-protocol.org/v1.0.1/topics/streaming-and-async/)

取消走 `CancelTask`，UI 根据服务端返回状态区分已取消和已经完成。以项目已有的 Tika/PDFBox、OCR 和文档任务体系为执行基础。PostgreSQL 可以保存协议任务、业务任务映射、产物引用和回调配置。要让服务重启后继续执行，还要有工作认领、恢复机制和可重试的回调投递。验收包括重复回调、回调掉线后的轮询、取消与完成竞争、进程重启后的同一任务查询和执行恢复。加入 `ListTasks`，可在任务中心练习按状态过滤、游标分页和主体隔离。业务任务有副作用时，需明确提交去重契约，A2A 对 `SendMessage` 的幂等性允许实现自行决定。 [A2A 任务查询、取消与幂等](https://a2a-protocol.org/v1.0.1/specification/)

## 第三条链路：Matrix 团队（5 → 6 → 7）

**5. Manager 按卡片选择 Worker。** 示例中可发布 `style-review` 与 `style-score` 两个 skill，再加入第二个同时支持评分的 Agent，使用不同的版本或输出类型。当前仓库的 Nacos 依赖是 `3.2.4`，`NacosA2AClientFactory` 已按名称、版本获取卡片和实例端点。Nacos 3.2 文档将基于 skill、tag 的模糊检索列为后续能力。建议让 Manager 枚举允许的候选，读取卡片后按 `skills[].id`、`tags`、媒体类型和能力筛选，再解析在线端点。具体技能如何选择，可以先用任务文字表达。如果要可靠地精确路由，双方可约定结构化请求字段。AgentCard 的 skill ID 本身并未定义调用方法。Matrix 房间可展示任务状态，应用端保留 `roomId` 到远端 Agent、`contextId`、`taskId` 的映射。发布第二张卡片后，无需改 Manager 的 Worker 名单就可被选中，是最清楚的验收。 [A2A Agent 发现](https://a2a-protocol.org/v1.0.1/topics/agent-discovery/) · [Nacos 3.2 Agent 注册与查询](https://www.nacos.io/docs/v3.2/manual/user/ai/agent-registry/)

**6. 让审核 Agent 委托下游，并使用它的交付物。** `backend → style-editor → fact-checker` 合理。建议提供一份事实表或参考资料，fact-checker 返回 `claim`、`verdict`、`evidence`，编辑器用结果修改草稿或标注证据不足。这样可验证下游产物确实参与了上游决策。由各自服务生成任务 ID，应用端记录父子任务映射。`contextId` 用于会话关联，跨 Agent 共享同一值取决于双方约定和目标 Agent 是否接受。`referenceTaskIds` 可在接收方能解析相关任务时使用。每一跳用 W3C `traceparent` 传播追踪上下文，为出站调用创建 span。Langfuse 验收同一 trace 中的两段客户端/服务端调用、正确的父子关系，以及事实核查失败时的上游处理。 [A2A 上下文与任务标识](https://a2a-protocol.org/v1.0.1/specification/) · [W3C Trace Context](https://www.w3.org/TR/trace-context/)

**7. 用权限驱动扩展卡片变化。** 公开卡片发布基础技能及认证方案，设置 `capabilities.extendedAgentCard`。认证后用 `GetExtendedAgentCard` 取得该调用方可见的技能。长篇小说模式适合做低成本演示，可以让普通用户和高级用户读取不同卡片，并直接尝试调用以验证运行时授权。现有 `JwtTokenProvider` 可提供身份机制参考，跨服务调用建议使用短期、限定受众及能力范围的凭据。Task 的读取、订阅、续接、取消也按主体或租户授权。若想再练 `AUTH_REQUIRED`，可让已启动的文档任务因受限文件的授权缺失而暂停，待用户完成授权后继续同一任务。 [A2A 认证授权和扩展卡片访问控制](https://a2a-protocol.org/v1.0.1/specification/)

## 验证顺序

1. 用固定的 A2A 1.0 JSON-RPC 客户端验证公开卡片、简单 `Message` 响应和带 `Task` 的响应。
2. 用同一写作任务验证中断、补充、流式产物和重连。
3. 用 PDF 验证文件与 `data`，再用扫描 PDF 验证推送、状态查询和取消。用插画验证文件产物。
4. 接入 Matrix、Nacos 技能筛选、多跳和扩展卡片。若会接入真实用户或资料，在接入前验证访问控制。
5. 用 A2A Inspector 或不同 SDK 的客户端复测，选择与目标规范版本匹配的 TCK 验证已声明能力及错误响应。若还想练协议扩展，可以给文档任务加一个可选的页级 OCR 进度扩展，练习 `AgentCard.capabilities.extensions`、URI 和 `A2A-Extensions` 协商。 [A2A Inspector](https://github.com/a2aproject/a2a-inspector) · [A2A TCK](https://github.com/a2aproject/a2a-tck) · [A2A Extensions](https://a2a-protocol.org/v1.0.1/topics/extensions/)
