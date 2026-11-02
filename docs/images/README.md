# README screenshots / README 截图

The current README tour was checked against the local application on 2026-11-02. Both languages share the same screenshots. The `.jpg` files show the shared workspace sidebar and current page controls; older `.png` captures are retained as historical assets and are no longer used in the tour.

本次导览于 2026-11-02 对照本地应用核对，中英文共用截图。新版 `.jpg` 展示统一工作区侧栏和当前页面控件；旧版 `.png` 作为历史素材保留，不再用于主导览。

| File / 文件 | Page / 页面 | Captured content / 截图内容 |
| --- | --- | --- |
| `demo-02-chat.jpg` | `/chat` | Sidebar, prompt selector, voice link, attachment and send controls / 侧栏、提示词选择、语音入口、附件和发送控件 |
| `demo-02-session-types.jpg` | `/chat` → 新会话 | Unified picker, approval selector, domain search and cards / 统一选择器、批准模式、领域搜索与卡片 |
| `demo-03-system-prompts.jpg` | `/me/system-prompts` | Prompt selection and editor / 提示词选择与编辑器 |
| `demo-03-knowledge.jpg` | `/me/knowledge` | Prompt selection, file upload, expanded manual entry / 提示词选择、文件上传、展开的手动录入表单 |
| `demo-04-agents.jpg` | `/agents` | Search, domain filters, Agent cards and topology links / 搜索、领域筛选、Agent 卡片与编排入口 |
| `demo-05-skills.jpg` | `/me/skills` → 新建 Skill | Creation form and release workflow description / 创建表单和版本流程说明 |
| `demo-09-automations.jpg` | `/automations` → 新建任务 | Session selection, instructions, schedule, initially disabled state / 会话选择、指令、计划和默认关闭状态 |
| `demo-10-outbound.jpg` | `/outbound` | Import and task creation forms; cropped before contact records / 名单导入与任务创建表单，裁去联系人记录 |

These captures use the browser's 1031 × 984 viewport. The outbound image is cropped to the top 480 px to exclude contact records. Keep the sidebar visible where possible, wait for data to load, and avoid capturing credentials, personal memory, or private contact details. Opening forms is enough for documentation; do not submit tasks, enable schedules, or place calls just to capture a screenshot.

本轮截图使用浏览器的 1031 × 984 视口；外呼截图截取顶部 480 px，以避开联系人记录。后续截图尽量保留侧栏，等待数据加载完成，避开凭据、个人记忆和私有联系人信息。展示表单即可，不需要为截图提交任务、启用计划或发起呼叫。

## Pending captures / 待补截图

| Proposed file / 建议文件名 | Capture condition / 截图条件 |
| --- | --- |
| `demo-02-hitl-runtime.png` | A real Harness approval card with a sanitized tool summary / 真实 Harness 批准卡片及脱敏工具摘要 |
| `demo-06-langfuse.png` | Access to a deployed Langfuse trace; hide private content / 已部署 Langfuse 的 Trace，隐藏私有内容 |
| `demo-07-minio.png` | Resource and Skill buckets without credentials / 资源与 Skill Bucket，不含凭据 |
| `demo-08-memory.png` | Harness memory page with non-sensitive sample content / 使用非敏感示例内容的 Harness 长期记忆页 |

Legacy captures include login, separate approval selection, Agent management, and car-rental topology. Recapture those pages before reusing them as evidence of the current UI.

旧素材包含登录页、独立批准模式选择、Agent 管理和租车拓扑。后续若要用来展示当前界面，请先重新截图。
