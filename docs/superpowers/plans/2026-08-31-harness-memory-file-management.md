# Harness 用户长期记忆文件管理实施计划

> **状态：** 已完成产品决策，可进入实现
>
> **日期：** 2026-08-31
>
> **关联 PRD：** `docs/prd/2026-08-08-harness-agent-prd.md`
>
> **关联原型：** `docs/prd/2026-08-08-harness-agent-mobile-prototype.html`

**目标：** 在“我的”中暴露当前认证用户唯一的 Harness `MEMORY.md`，支持安全查看、Markdown 阅读、章节定位、直接编辑、保存和取消；不改变 AgentScope Harness 已有的记忆提取、整理、注入或 Agent 写入链路。

**架构：** 新增一个 `HarnessMemoryDocumentManager` 深模块。它以当前认证用户为唯一 owner，在现有 `BaseStore` seam 后隐藏 AgentScope namespace、`workspace_files.value_json`、时间字段和 CAS 细节；HTTP interface 只暴露单文档 GET/PUT。前端 `/me/memory` 只处理阅读态、编辑态和版本冲突，不接触文件路径或存储键。

**技术栈：** Java 26、Spring Boot 4.0.6、AgentScope Java Harness 2.0.2、PostgreSQL、Next.js 16、React 19、TypeScript、React Markdown、JUnit 5、Mockito、Node test。

---

## 1. 已确认范围

### 1.1 本期交付

1. 在 `/me` 新增“Harness Agent 能力”分组和“用户长期记忆”入口。
2. 新增 `/me/memory` 页面，管理当前认证用户唯一的 `MEMORY.md`。
3. 默认显示渲染后的 Markdown；用户点击“编辑”后进入源码编辑态。
4. 从正文中的二级标题动态生成章节导航；导航只定位同一份文档，不拆分文件或字段。
5. 支持保存和取消：
   - 保存成功后以服务端响应作为新的本地基线；
   - 取消恢复进入编辑态前的已保存正文；
   - 页面离开前如有未保存修改，使用浏览器原生离开确认。
6. 展示最后更新时间；没有时间时显示“尚未保存”。
7. 文件不存在时返回默认模板但不立即落库，首次保存时创建。
8. 人工保存正文上限为 **65,536 UTF-8 bytes（64 KiB）**。
9. 使用 `workspace_files.version` 作为 revision，管理页 PUT 使用 CAS 防止丢失加载后的并发更新。

### 1.2 明确不在本期交付

- 不设计或修改长期记忆的生成、抽取、整理、注入和工具调用。
- 不修改 `MemoryConfig`、flush prompt、consolidation prompt、每日账本或保留周期。
- 不让通用助手、领域 Agent 或协作子 Agent新增记忆读取能力；本功能只对应 `harness-agent`。
- 不提供文件列表、新建入口、删除、重命名、多文件管理、导入、导出、全文搜索、版本历史、Diff 或恢复旧版。
- 不固定 Markdown Schema；“工作偏好、个人信息、项目知识、表达方式”只是默认模板章节。
- 不展示“用户修改 / Agent 生成”等修改来源。现有文件值没有可靠 writer 元数据，本期不猜测、不补写旁路元数据，也不改造 Agent writer。
- 不复用或修改 Mem0 的 `/api/memories` 记录管理接口。
- 不新增数据库表或 Flyway migration。
- 不修改现有 `/me/knowledge`、`/me/agents` 等路由以追平 PRD 中的旧命名。

### 1.3 核心不变量

1. owner 只来自 `AuthUserPrincipal.userId()`；请求体和路径不接受 user id。
2. 产品管理的是 Harness Workspace 的 `MEMORY.md`，不是 `com.h.backend.memory` 下的 Mem0 记录。
3. 前端永远看不到 namespace、item key、数据库行或真实文件路径。
4. 对不存在的文件，GET 返回 `exists=false`、`revision=0` 和默认模板；只有 PUT 才创建数据。
5. 新建只允许 `expectedRevision=0`，更新只允许等于当前 revision；不匹配统一返回冲突，绝不降级成无条件覆盖。
6. 成功保存后 revision 单调递增；客户端必须使用响应中的新 revision，不能本地自行加一。
7. 服务端和前端都按 UTF-8 bytes 校验 64 KiB；服务端是最终约束。
8. 保存任意合法 Markdown，包括空字符串；页面不强制保留任何标题。
9. Markdown 阅读态禁止原始 HTML，并继续使用现有安全链接规则。
10. `workspace_files` 中除标准文件字段外若存在未知字段，人工保存必须保留这些字段，避免破坏 SDK 的向前兼容数据。

## 2. 当前实现事实

### 2.1 Harness 文件记忆已存在

`HarnessAgentConfig` 已经完成以下装配：

- `MemoryConfig` 将对话提取到 `memory/YYYY-MM-DD.md`，再定期整理到 `MEMORY.md`；
- `RemoteFilesystemSpec` 使用 `IsolationScope.USER`，所以 `MEMORY.md` 跨该用户的 Harness 会话共享；
- `PostgresBaseStore` 把 Workspace 文件写入 PostgreSQL `workspace_files`；
- `workspace_files.version` 已支持跨 JVM 的原子 CAS。

当前 `MEMORY.md` 的存储地址是：

```text
namespace = ["agents", "harness-agent", "users", String.valueOf(userId), "root"]
item key = "/MEMORY.md"
```

`value_json` 的 SDK 标准形状是：

```json
{
  "content": "# 用户长期记忆\n...",
  "encoding": "utf-8",
  "created_at": "2026-08-31T06:00:00Z",
  "modified_at": "2026-08-31T06:30:00Z"
}
```

管理功能直接复用这份真相，不建立第二份正文投影。

### 2.2 与 Mem0 模块的关系

仓库中的 `com.h.backend.memory` 和 `/api/memories` 管理的是可搜索的 Mem0 细粒度记录，并且 Harness 明确不在该策略目录中。本页面管理的是 AgentScope Harness 原生 Markdown 文件，二者的 owner、数据形态、版本语义和运行链路都不同。

因此：

```text
/api/memories     -> Mem0 记录列表/搜索/CRUD/历史（保持不变）
/api/me/memory    -> 当前用户唯一 Harness MEMORY.md（本计划新增）
```

### 2.3 已接受的并发边界

Harness 的文件工具编辑路径支持 CAS，但 SDK 的 `WorkspaceManager.writeUtf8WorkspaceRelative` 会通过 snapshot upload 以 last-write-wins 写 `MEMORY.md`。本计划只约束管理页 PUT：

```text
GET revision=N
    -> 期间 Agent 写成 N+1
    -> 用户 PUT expectedRevision=N
    -> 409，用户内容不覆盖 N+1
```

反向顺序仍沿用 SDK 当前行为：用户保存成功后，Agent 后续整理可能再次覆盖整份 `MEMORY.md`。解决这种双向 writer 协调需要修改既有记忆写入链路，明确不属于本期。

当前仓库和已配置的 `/tmp/h-agent/harness-workspace` 都没有共享的下层 `MEMORY.md` 模板，因此空字符串 override 与 SDK 的模板 fallback 暂无冲突。如果未来向 Workspace 模板加入同名文件，必须先单独定义“用户保存空文件”是否应遮蔽模板；本计划不预先改变 SDK 的 blank fallback 语义。

## 3. 模块与 seam

```text
/me/memory 页面
    -> frontend/lib/harness-memory.ts
    -> GET /api/me/memory
       PUT /api/me/memory
    -> MeHarnessMemoryController
    -> HarnessMemoryDocumentManager
       |- owner 解析后只接收 userId
       |- 默认模板 / 64 KiB 校验
       |- namespace 与 /MEMORY.md 映射
       |- value_json 编解码与时间字段
       `- revision CAS
    -> BaseStore seam
       |- PostgresBaseStore（生产）
       `- InMemoryStore（模块测试）
```

### 3.1 `HarnessMemoryDocumentManager` 深模块

该模块只暴露两项能力：读取当前文档和按已观察 revision 保存全文。调用者不需要知道 SDK、PostgreSQL、路径、创建/更新差异或 CAS 细节。

```java
public final class HarnessMemoryDocumentManager {

    public HarnessMemoryDocument view(long userId);

    public HarnessMemoryDocument save(
            long userId,
            String content,
            long expectedRevision
    );
}

public record HarnessMemoryDocument(
        String content,
        long revision,
        boolean exists,
        Instant updatedAt
) {}
```

实现要求：

1. 注入 `@Qualifier("harnessWorkspaceStore") BaseStore`，不要在模块里创建 DataSource、JDBC 连接或 AgentScope filesystem。
2. 将 `HarnessAgentConfig` 里的 `PostgresBaseStore` 提升为同名 Bean；`harnessDistributedStore` 与管理模块共享同一个 `BaseStore` 实例。
3. namespace 和 item key 是模块私有常量，Controller、DTO 和前端不得复制。
4. `view`：
   - `store.get(namespace, "/MEMORY.md") == null` 时返回虚拟默认文档；
   - 已存在时要求 `content` 为字符串且 `encoding` 缺失或等于 `utf-8`；
   - `modified_at` 缺失时返回 `updatedAt=null`；格式非法时记录不含正文的错误日志并返回安全的 500 错误。
5. `save`：
   - 先按 UTF-8 bytes 校验大小；
   - 读取当前 `StoreItem` 并核对 `expectedRevision`；
   - 新建时写入标准四字段，`created_at` 与 `modified_at` 均取同一个 `Instant.now()`；
   - 更新时复制当前 value map，替换 `content`、`encoding=utf-8`、`modified_at`，保留已有 `created_at` 和未知字段；
   - 调用 `putIfVersion`，CAS 返回 false 时抛 revision conflict；
   - 保存后再次读取并返回权威 revision 和时间，不自行推算版本。
6. 不提供 `delete`、`list`、`rename` 或无 revision 的 `put` 方法。

默认模板由后端作为唯一来源：

```markdown
# 用户长期记忆

## 工作偏好

## 个人信息

## 项目知识

## 表达方式
```

前端不得保留第二份默认模板，否则模板调整会产生双端漂移。

### 3.2 为什么不新增 Repository interface

`BaseStore` 已经是现成 seam，且存在生产 `PostgresBaseStore` 与测试 `InMemoryStore` 两个 Adapter。再包装一层只有 `get/putIfVersion` 的 Repository interface 只会形成浅模块。测试应直接从 `HarnessMemoryDocumentManager` 的 interface 验证行为，使用 `InMemoryStore` 替换本地可替代依赖。

## 4. HTTP interface

### 4.1 读取文档

```http
GET /api/me/memory
```

已存在响应：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "content": "# 用户长期记忆\n\n## 工作偏好\n- 默认使用中文",
    "revision": 7,
    "exists": true,
    "updatedAt": "2026-08-31T06:30:00Z"
  }
}
```

不存在响应仍为 HTTP 200：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "content": "# 用户长期记忆\n\n## 工作偏好\n...",
    "revision": 0,
    "exists": false,
    "updatedAt": null
  }
}
```

### 4.2 保存全文

```http
PUT /api/me/memory
Content-Type: application/json

{
  "content": "# 用户长期记忆\n...",
  "expectedRevision": 7
}
```

成功返回与 GET 相同的文档 DTO。首次保存必须提交 `expectedRevision=0`；成功后 `exists=true` 且 revision 为服务端实际值。

### 4.3 错误语义

| 场景 | HTTP | code | 前端行为 |
| --- | ---: | ---: | --- |
| 未认证 | 401 | `40100` | 保存回跳地址并跳转登录 |
| `content` 或 revision 缺失/非法 | 400 | `40001` | 保留编辑内容并显示参数错误 |
| 正文超过 64 KiB UTF-8 | 413 | `41301` | 保留编辑内容，显示当前 bytes/上限 |
| revision 不匹配 | 409 | `40920` | 不自动覆盖；提示重新加载最新内容 |
| 存储内容损坏 | 500 | `50020` | 显示安全错误，不把 namespace/value_json 返回浏览器 |
| BaseStore 不可用 | 503 | `50320` | 保留编辑内容，允许稍后重试 |

为这些错误定义 `HarnessMemoryDocumentException` 与错误种类，并由专用 `@RestControllerAdvice` 映射；不要把存储异常 message 或正文放进响应。

### 4.4 鉴权与缓存

- Controller 使用 `@AuthenticationPrincipal AuthUserPrincipal`，只把 `principal.userId()` 传给管理模块。
- GET/PUT 都返回 `Cache-Control: no-store`，避免私人记忆被浏览器或中间代理缓存。
- 不使用 path/query 中的 user id，不允许管理员跨用户读取。
- 本期不依赖 HTTP ETag；`revision` 是明确的领域字段和 PUT 前置条件。

## 5. 前端设计

### 5.1 `/me` 入口

将当前平铺卡片调整为两个视觉分组，但不改变旧链接：

```text
基础能力
  SystemPrompt 管理
  知识库管理
  领域 Agent 管理
  我的 Skill（版本管理）

HARNESS AGENT 能力
  用户长期记忆 -> /me/memory
```

“用户长期记忆”卡片副文案为“查看和编辑 Harness Agent 的长期记忆”；加载 `/me` 时不额外请求记忆接口，不在入口卡片显示可能过期的时间。

### 5.2 页面状态机

```text
LOADING
  -> READ(content, revision)
  -> ERROR_RETRYABLE

READ
  -> EDIT(draft=content, baseline=content)

EDIT
  -> CANCEL -> READ(baseline)
  -> SAVE_PENDING

SAVE_PENDING
  -> success -> READ(server response)
  -> 409 -> CONFLICT(draft retained)
  -> other error -> EDIT(draft retained)

CONFLICT
  -> 重新加载 -> GET -> READ(latest)
  -> 继续编辑 -> EDIT(draft retained; save disabled until reload)
```

冲突后禁止使用旧 revision 重试。第一版不做自动三方合并，也不提供“强制覆盖”。

### 5.3 页面交互

- 容器沿用 `max-w-md`（448px）、米白背景、白色圆角卡片和橙色强调色。
- Header 提供“返回我的”、标题“用户长期记忆”和最后更新时间。
- 阅读态：
  - 使用共享 `MarkdownContent`；
  - `react-markdown` 保持 `skipHtml`；
  - 链接继续经过 `safeMarkdownHref`；
  - 没有正文时展示空状态，不把空字符串替换回模板。
- 编辑态：
  - 使用受控 `textarea`，保留换行和任意 Markdown；
  - 显示 UTF-8 byte 计数 `current / 65,536`；
  - 超限时禁用保存并显示提示；
  - 保存过程中禁用保存/取消，防止重复请求；
  - `Ctrl/Cmd + S` 触发保存并阻止浏览器默认动作。
- 取消只回退前端草稿，不发送请求。
- 保存成功后显示轻量“已保存”，更新阅读态、revision 与更新时间。

### 5.4 章节导航

新增纯函数 `extractMemorySections(content)`，只识别非代码围栏中的 ATX 二级标题：

```markdown
## 工作偏好
## 自定义章节
```

规则：

1. 顺序与正文一致，允许任意名称和重复标题。
2. 忽略 `#`、`###` 及 fenced code block 内的伪标题。
3. 没有二级标题时隐藏导航。
4. 阅读态点击导航，按标题出现序号定位对应的 `<h2>` 并 `scrollIntoView`。
5. 编辑态点击导航，把 textarea selection 移到标题起始 offset 并聚焦。
6. 导航不写回正文，不成为后端字段。

### 5.5 未保存修改保护

- `draft !== baseline` 时注册 `beforeunload`。
- 页面内“返回我的”在有修改时显示确认；确认离开只丢弃本地草稿，不调用 PUT。
- 保存失败、超限或冲突时均保留 draft。

## 6. 文件变更

### 6.1 后端新增

- `backend/src/main/java/com/h/backend/chat/domain/memory/HarnessMemoryDocument.java`
- `backend/src/main/java/com/h/backend/chat/domain/memory/HarnessMemoryDocumentException.java`
- `backend/src/main/java/com/h/backend/chat/application/HarnessMemoryDocumentManager.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/HarnessMemoryDocumentDto.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/SaveHarnessMemoryDocumentRequest.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/web/MeHarnessMemoryController.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/web/HarnessMemoryDocumentExceptionAdvisor.java`
- `backend/src/test/java/com/h/backend/chat/HarnessMemoryDocumentManagerTest.java`
- `backend/src/test/java/com/h/backend/chat/MeHarnessMemoryControllerTest.java`

### 6.2 后端修改

- `backend/src/main/java/com/h/backend/chat/infrastructure/config/HarnessAgentConfig.java`
  - 提取 `@Bean("harnessWorkspaceStore") BaseStore`；
  - `harnessDistributedStore` 注入并复用该 Bean；
  - Harness Agent 其余装配保持不变。

### 6.3 前端新增

- `frontend/app/me/memory/page.tsx`
- `frontend/lib/harness-memory.ts`
- `frontend/lib/harness-memory-state.ts`
- `frontend/lib/harness-memory.test.mjs`
- `frontend/lib/harness-memory-state.test.mjs`
- `frontend/components/markdown-content.tsx`

### 6.4 前端修改

- `frontend/app/me/page.tsx`：增加能力分组与长期记忆入口。
- `frontend/app/chat/page.tsx`：改为从共享位置导入 `MarkdownContent`。
- 删除 `frontend/app/chat/markdown-content.tsx`，其实现无行为变化地迁移到共享位置。

本计划不修改任何 Flyway migration。

## 7. 实施任务

### Task 1：固定 Workspace Store seam

- [ ] 在 `HarnessAgentConfig` 中新增 `harnessWorkspaceStore(DataSource)` Bean，参数和当前内联 `PostgresBaseStore.builder(...)` 完全一致。
- [ ] 修改 `harnessDistributedStore` 注入该 Bean，删除方法内部重复构造。
- [ ] 保持 bean qualifier 明确，避免与其他 `BaseStore` 冲突。
- [ ] 运行现有 Harness Spring context 测试，确认 AgentState 与 Workspace 装配未变化。

### Task 2：实现深模块

- [ ] 先写 `HarnessMemoryDocumentManagerTest`：不存在、已有文件、默认模板、用户隔离、UTF-8 byte 上限、首次创建、成功更新、revision 冲突、CAS race、字段保留、损坏数据。
- [ ] 新增 `HarnessMemoryDocument` 与异常种类。
- [ ] 实现 namespace/key、value_json 编解码和 CAS 保存。
- [ ] 用注入的 `Clock` 或包内时间函数让时间断言稳定；生产使用 UTC `Instant`。
- [ ] 测试只通过 manager interface 观察结果，不断言其私有辅助方法。

关键测试矩阵：

| 初始状态 | expectedRevision | 结果 |
| --- | ---: | --- |
| 不存在 | 0 | 创建成功，revision=1 |
| 不存在 | >0 | 409 conflict |
| revision=N | N | 更新成功，revision=N+1 |
| revision=N | 0 或其他值 | 409 conflict |
| 读取 N 后另一 writer 写 N+1 | N | CAS false，409，不覆盖 N+1 |

### Task 3：暴露单文档 HTTP interface

- [ ] 新增 GET/PUT DTO 与 Jakarta Validation。
- [ ] 新增 `MeHarnessMemoryController`，owner 只取 principal。
- [ ] 新增异常 Advisor，落实 409/413/500/503 映射和安全消息。
- [ ] 为两种响应加 `Cache-Control: no-store`。
- [ ] 写 WebMvc 测试覆盖认证用户、请求体、状态码、响应 DTO 和 principal owner 传递。
- [ ] 明确断言不存在 list/delete/history 等路由。

### Task 4：实现前端客户端与纯状态逻辑

- [ ] 在 `harness-memory.ts` 定义 `HarnessMemoryDocument`、`getHarnessMemory()`、`saveHarnessMemory()`。
- [ ] PUT 只发送 `content + expectedRevision`。
- [ ] 使用 `TextEncoder` 实现 UTF-8 byte 计数，覆盖中文、emoji 和 ASCII。
- [ ] 实现章节提取，正确忽略 fenced code block 内的 `##`。
- [ ] 为 GET、PUT、revision、409、byte 计数和章节提取编写 Node tests。

### Task 5：共享 Markdown 阅读模块

- [ ] 把现有 `app/chat/markdown-content.tsx` 原样迁到 `components/markdown-content.tsx`。
- [ ] 更新聊天页 import，先运行已有 Markdown link 测试与前端 build，证明聊天渲染无回归。
- [ ] 记忆页复用共享模块，不新增允许 HTML 的第二套 renderer。

### Task 6：实现 `/me/memory`

- [ ] 按现有管理页模式完成 auth guard 和登录回跳。
- [ ] 实现 loading、read、edit、saving、conflict、retryable error 状态。
- [ ] 实现阅读/编辑切换、保存、取消、byte 计数和最后更新时间。
- [ ] 实现阅读态滚动定位与编辑态光标定位。
- [ ] 实现未保存修改离开确认和 `Ctrl/Cmd + S`。
- [ ] 冲突时保留 draft，但在重新 GET 前禁止保存。

### Task 7：接入“我的”并完成验收

- [ ] `/me` 重排为“基础能力 / Harness Agent 能力”两组。
- [ ] 保留所有现有入口的真实路由，不顺手重命名。
- [ ] 在 390px 手机视口和桌面视口验证页面，内容区最大宽度保持 448px。
- [ ] 完成下节自动化和手工验收。

## 8. 测试与验证

### 8.1 后端自动化

```bash
mvn -pl backend -Dtest=HarnessMemoryDocumentManagerTest,MeHarnessMemoryControllerTest test
mvn -pl backend test
```

至少覆盖：

- 两个 user id 映射到不同 namespace，不能互读互写；
- GET 不存在不会创建数据库项；
- 首次保存使用 revision 0；
- revision CAS 冲突不覆盖服务端正文；
- 中文和 emoji 按 UTF-8 bytes 计算；
- 65,536 bytes 接受，65,537 bytes 拒绝；
- 空 Markdown 可以保存；
- `created_at` 更新时保持，`modified_at` 更新；
- 额外 value_json 字段在人工保存后仍存在；
- 错误响应不含 namespace、item key、正文或底层异常信息。

### 8.2 前端自动化

```bash
cd frontend
npm test
npm run lint
npm run build
```

至少覆盖：

- GET/PUT 路径、HTTP method 和 request body；
- 保存使用服务端返回 revision；
- 409 可被页面识别为冲突；
- UTF-8 byte 计数；
- 二级标题顺序、重复标题、无标题和 code fence；
- Markdown 安全链接测试继续通过；
- 聊天页更换共享 import 后能够 build。

### 8.3 手工验收

1. 新用户打开 `/me/memory`：看到默认模板、“尚未保存”，数据库没有新增项。
2. 编辑并首次保存：页面回到阅读态，刷新后内容仍在。
3. 删除全部正文并保存：刷新后仍为空，不重新出现默认模板。
4. 添加、删除、重命名二级标题：章节导航同步变化。
5. 点击阅读态导航滚动到章节；编辑态点击导航把光标移到标题。
6. 制造未保存修改后取消：恢复保存前正文。
7. 制造未保存修改后返回：出现离开确认。
8. 输入中文/emoji 超过 64 KiB：前端阻止保存；绕过前端直接 PUT 仍返回 413。
9. 浏览器 A 读取 revision N，浏览器 B 保存 N+1，A 再保存：A 收到冲突且 B 的内容未被覆盖。
10. 用户 A 与用户 B 分别保存：两者内容完全隔离。
11. Markdown 中放入 `<script>` 和 `javascript:` 链接：阅读态不执行、不生成危险链接。
12. 断开数据库后保存：draft 保留，页面提示稍后重试。

## 9. 可观测性与发布

### 9.1 日志

只记录以下结构化字段：

- action：`harness_memory.view` / `harness_memory.save`；
- userId（按项目既有日志规范处理）；
- expectedRevision / actualRevision；
- contentBytes；
- outcome：success / conflict / too_large / unavailable / corrupt。

禁止记录 Markdown 正文、`value_json`、JWT 或完整 namespace。

### 9.2 指标

若项目已有 Micrometer 入口，增加：

- `harness_memory_management_requests_total{action,outcome}`；
- `harness_memory_management_save_bytes`；
- `harness_memory_management_latency_seconds{action}`。

指标不是接口成功的前置条件，采集失败不能影响 GET/PUT。

### 9.3 发布顺序

1. 先发布后端 GET/PUT。
2. 用测试用户验证现有 `MEMORY.md` 可读取且不改变内容。
3. 再发布前端入口和页面。
4. 无数据迁移、回填或双写步骤。
5. 回滚前端只会隐藏入口；回滚后端不会影响 Harness 原有记忆运行。

## 10. 完成定义

- 当前认证用户可以从“我的”进入并查看自己的 Harness `MEMORY.md`。
- 阅读态正确渲染任意 Markdown，危险 HTML/链接不会执行。
- 用户可以进入编辑态、保存、取消，并在刷新后看到保存结果。
- 章节导航从正文动态生成，不形成第二份分类数据。
- 文件不存在时不因 GET 被创建，首次 PUT 原子创建。
- 所有人工保存都携带 revision；冲突不会覆盖新内容。
- 64 KiB UTF-8 上限在前后端一致执行。
- 只显示最后更新时间，不显示无法可靠证明的修改来源。
- 不新增表、不修改 Mem0、不修改 Harness 记忆写入链路。
- 后端测试、前端测试、lint、build 和手工验收全部通过。
