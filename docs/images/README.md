# README Screenshot Checklist / README 截图清单

Most application screenshots below have already been captured and embedded in `README.md` and `README_zh.md`. Rows marked TODO are intentionally reserved for interfaces that require a real tool approval or access to a separately deployed service. Keep private keys, access tokens, personal memory content, and other sensitive data outside the captured area.

下表中的大部分应用截图已经保存并插入 `README.md` 和 `README_zh.md`。标记为“待补”的项目需要真实工具批准场景或单独部署服务的访问权限，因此继续保留明确位置。截图时请避开私钥、Token、个人记忆内容和其他敏感信息。

| File / 文件 | Suggested page / 建议页面 | Capture / 截图重点 |
| --- | --- | --- |
| `demo-01-login.png` | `http://localhost:3000/` | Captured: email login form / 已截图：邮箱登录表单 |
| `demo-02-chat.png` | `/chat` | Captured: messages, prompt selector, composer / 已截图：消息区、提示词选择、输入框 |
| `demo-02-session-types.png` | `/chat` → `新会话` | Captured: General, Domain, Collaborative Agent entries / 已截图：通用、领域、协作 Agent 入口 |
| `demo-02-hitl-approval.png` | `/chat` → `新会话` → `协作 Agent` | Captured: five approval modes / 已截图：五种批准模式 |
| `demo-02-hitl-runtime.png` | A Harness run waiting for approval / 等待批准的 Harness Run | TODO: real approval card with sanitized tool summary / 待补：含脱敏工具摘要的真实批准卡片 |
| `demo-03-system-prompts.png` | `/me/system-prompts` | Captured: prompt selector and editor / 已截图：提示词选择与编辑器 |
| `demo-03-knowledge.png` | `/me/knowledge` | Captured: upload/manual entry and document list / 已截图：上传/手动录入与文档列表 |
| `demo-04-agents.png` | `/me/agents` | Captured: Agent cards and execution types / 已截图：Agent 卡片与运行类型 |
| `demo-04-car-rental-hitl-topology.png` | `/me/agents/car-rental-assistant` | Captured: topology with `Human / askUser` / 已截图：包含 `Human / askUser` 的拓扑 |
| `demo-05-skills.png` | `/me/skills` | Captured: new Skill form and version workflow description / 已截图：新建表单与版本流程说明 |
| `demo-06-langfuse.png` | Langfuse trace detail | Agent/generation/tool observation tree; redact content if needed / Agent、Generation、Tool 观测树，必要时遮挡内容 |
| `demo-07-minio.png` | MinIO Console | Resource and Skill buckets without credentials / 资源与 Skill Bucket，不显示凭据 |
| `demo-08-memory.png` | `/me/memory` | Harness `MEMORY.md` editor with non-sensitive sample content / Harness `MEMORY.md` 编辑页，使用非敏感示例 |

The current browser captures use a consistent 1280 px viewport; long management/topology pages use full-page capture. Future external-console screenshots should use a similar width and color scheme.

当前浏览器截图统一使用 1280 px 宽度，较长的管理页和拓扑页使用整页截图。后续补充外部控制台截图时，建议保持相近宽度和一致配色。
