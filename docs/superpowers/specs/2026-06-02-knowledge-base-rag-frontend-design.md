# 知识库上传 + RAG 检索前端 — 设计规格

- 日期：2026-06-02
- 范围：仅前端页面与接口对接
- 后端依据：`docs/superpowers/specs/2026-06-02-knowledge-base-rag-design.md`
- 目标页面：`frontend/app/me/knowledge/page.tsx`

## 1. 目标与边界

为已经实现的后端知识库能力增加前端管理界面，让用户可以围绕当前 SystemPrompt/Agent 管理 RAG 知识文档。

首版支持：

- 从聊天页当前 SystemPrompt 进入对应知识库
- 从“我的”页进入知识库管理
- 在知识库页面切换 SystemPrompt
- 上传 `md / txt / doc / docx / xls / xlsx` 文件
- 手动录入文本知识
- 查看当前 SystemPrompt 下的文档列表
- 删除文档
- 查看已完成文档的切片内容

首版不做：

- 拖拽上传
- 多文件批量上传
- 前端检索开关
- 聊天回答中展示 RAG 引用来源
- 重解析按钮

不展示重解析按钮的原因是后端当前文件类重解析会提示重新上传，手动类也没有持久化原文用于真正重建。首版保留删除与重新上传/重新录入的清晰路径。

## 2. 页面入口与路由

新增独立页面：

```text
/me/knowledge
```

支持可选查询参数：

```text
/me/knowledge?promptId=<id>
```

入口设计：

1. 聊天页当前 SystemPrompt 卡片右侧增加“知识库”入口。
   - 如果存在 `selectedPromptId`，跳转到 `/me/knowledge?promptId=<selectedPromptId>`。
   - 如果不存在 prompt，不展示该入口。
2. “我的”页增加“知识库管理”入口。
   - 跳转到 `/me/knowledge`。
   - 页面默认选择默认 SystemPrompt；如果没有默认，选择第一个。

## 3. 页面结构

`/me/knowledge` 采用 Agent 优先的单页管理布局，延续现有窄屏优先风格。

页面结构：

- 顶部区：
  - 返回“我的”
  - 标题“知识库管理”
  - 简短说明当前页面管理的是各 Agent 独立知识库
- SystemPrompt 切换区：
  - 横向 pill 列表
  - 当前选中项高亮
  - 切换后刷新文档列表
- 操作区：
  - 文件上传
  - 手动录入
- 文档列表：
  - 文件名
  - 来源类型
  - 状态
  - 文件类型
  - 文件大小
  - 字符数
  - 切片数
  - 创建时间
  - 失败原因
  - 删除
  - 查看切片
- 切片查看面板：
  - 由文档列表中的“查看切片”打开
  - 分页加载 segment
  - 展示 `text` 和 `metadata`

页面只管理知识库，不编辑 SystemPrompt，也不参与聊天消息流。

## 4. 前端 API

新增模块：

```text
frontend/lib/knowledge.ts
```

类型：

```ts
export type KnowledgeDocument = {
  id: number;
  fileName: string;
  sourceType: string;
  fileType: string | null;
  fileSize: number | null;
  charCount: number | null;
  segmentCount: number | null;
  status: string;
  errorMsg: string | null;
  createdAt: string;
};

export type KnowledgeSegment = {
  text: string;
  metadata: string;
};

export type ManualKnowledgePayload = {
  promptId: number;
  title: string;
  content: string;
};
```

请求函数：

```ts
export function listKnowledgeDocuments(promptId: number): Promise<KnowledgeDocument[]>;
export function uploadKnowledgeDocument(promptId: number, file: File): Promise<number>;
export function createManualKnowledge(payload: ManualKnowledgePayload): Promise<number>;
export function deleteKnowledgeDocument(docId: number): Promise<null>;
export function listKnowledgeSegments(
  docId: number,
  limit?: number,
  offset?: number,
): Promise<KnowledgeSegment[]>;
```

后端接口：

```text
GET    /api/knowledge/documents?promptId=<id>
POST   /api/knowledge/documents/upload     multipart: file, promptId
POST   /api/knowledge/documents/manual     json: promptId, title, content
DELETE /api/knowledge/documents/{docId}
GET    /api/knowledge/documents/{docId}/segments?limit=20&offset=0
```

现有 `apiFetch` 会设置 `Content-Type: application/json`，不适合 `FormData`。新增一个小封装 `apiFormFetch<T>()`，复用统一响应解析和 `credentials: "include"`，但不设置 JSON `Content-Type`。

## 5. 数据流

页面初始化：

1. 调用 `getCurrentUser()` 确认登录。
2. 调用 `listSystemPrompts()` 获取可用 SystemPrompt。
3. 按优先级确定 `selectedPromptId`：
   - URL 中合法的 `promptId`
   - 默认 SystemPrompt
   - 第一个 SystemPrompt
4. 如果没有 SystemPrompt，显示空状态并引导去 `/me/system-prompts`。
5. 如果有 `selectedPromptId`，调用 `listKnowledgeDocuments(selectedPromptId)`。

交互数据流：

- 切换 SystemPrompt：更新 `selectedPromptId` 并刷新文档列表。
- 上传文件成功：清空文件选择，刷新文档列表。
- 手动录入成功：清空标题和内容，关闭录入区，刷新文档列表。
- 删除成功：刷新文档列表。
- 查看切片：打开面板并加载第一页；点击加载更多时增加 offset。

聊天检索不需要前端开关。后端已经按 `promptId` 将 RAG 接入 `HAssistant`，用户在对应知识库上传内容后，聊天自动检索。

## 6. 状态与错误处理

页面级状态：

- `authenticated`
- `loadingPrompts`
- `selectedPromptId`
- `documents`
- `documentsLoading`
- `message`
- `error`

上传区状态：

- `uploading`
- `selectedFile`

手动录入状态：

- `manualOpen`
- `manualTitle`
- `manualContent`
- `savingManual`

文档列表状态：

- `deletingDocId`

切片面板状态：

- `segmentsOpenDoc`
- `segments`
- `segmentsLoading`
- `segmentsOffset`
- `segmentsHasMore`
- `segmentsError`

错误处理：

- 鉴权失败：保存 redirect 到当前完整路径，然后跳转 `/auth/login`。
- 上传失败：展示后端 message，例如文件格式不允许、文件过大、解析失败。
- 手动录入失败：展示后端校验或业务错误。
- 列表失败：页面内展示错误，不清空 prompt 选择。
- 删除失败：保留原列表并展示错误。
- 切片失败：面板内展示错误，并允许重新加载。

空状态：

- 无 SystemPrompt：引导去 `/me/system-prompts` 创建。
- 当前 Agent 无文档：显示“暂无知识文档”，保留上传和手动录入入口。
- 文档 `FAILED`：展示失败原因，允许删除，隐藏查看切片。
- 文档 `PROCESSING`：展示处理中，禁用查看切片。

删除确认：

- 使用 `window.confirm` 做一次确认。
- 文案明确删除会移除该文档的知识库向量。

## 7. 视觉与交互约束

- 延续现有 `me` 与 `chat` 页面风格：窄屏优先、浅色背景、stone/amber 色系、圆角卡片。
- 操作按钮保持清晰：上传、保存手动录入、删除、查看切片。
- 不引入新 UI 库。
- 不新增全局 modal 系统。切片查看使用页面内展开面板，位于当前文档卡片下方。
- 长文本切片使用 `whitespace-pre-wrap`，限制面板高度并允许滚动。
- 文档文件名使用换行或截断，避免移动端溢出。

## 8. 测试与验收

验证命令：

```bash
cd frontend
npm run lint
npm test
npm run build
```

为 `apiFormFetch` 的 FormData header 行为补充单测。页面交互首版以浏览器手动验收为主。

浏览器验收：

- 未登录访问 `/me/knowledge` 会跳转登录，并保存返回路径。
- 从聊天页当前 Agent 进入 `/me/knowledge?promptId=<当前 promptId>`。
- 从“我的”页进入 `/me/knowledge`，默认选中默认或首个 Agent。
- 切换 SystemPrompt 会刷新文档列表。
- 上传允许类型文件后，文档出现在列表中。
- 手动录入文本后，文档出现在列表中。
- 删除文档后，列表移除该文档。
- 已完成文档可以查看切片内容。
- 后端错误 message 能展示给用户。

## 9. 实施文件清单

新增：

- `frontend/app/me/knowledge/page.tsx`
- `frontend/lib/knowledge.ts`

修改：

- `frontend/lib/http.ts`
- `frontend/app/chat/page.tsx`
- `frontend/app/me/page.tsx`
- `frontend/lib/http.test.mjs`

新增测试：

- `frontend/lib/knowledge.test.mjs`
