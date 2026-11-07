# 文档工作流部署与验收

适用于 `standard-chat` 与 `harness-agent`。日期为 2026-11-06。

## 运行依赖

使用 JDK 26 构建。安装 LibreOffice，保证后台服务可以执行 `soffice --headless`，或配置 `CHAT_DOCUMENTS_SOFFICE_COMMAND` 为其可执行文件的绝对路径。生成器使用独立临时 profile，通过字体配置加载随包提供的 Noto Sans SC。临时文件在完成或失败后清理。

数据库由 Flyway 执行 `V20261105_01__document_workflows.sql`，增加会话授权、文档任务与原文件扩展名。现有资源由原文件名回填扩展名。

## 外部服务配置

通过部署环境变量或 Spring 的 `chat.documents` 配置提供以下字段。

| 环境变量 | 默认值与作用 |
| --- | --- |
| `MODELBEST_API_KEY` | 默认空。也可用 `CHAT_DOCUMENTS_MODELBEST_API_KEY` |
| `CHAT_DOCUMENTS_MODELBEST_BASE_URL` | `https://api.modelbest.cn/v1` |
| `CHAT_DOCUMENTS_OCR_MODEL` | `MiniCPM-OCR-1.0` |
| `CHAT_DOCUMENTS_JEV_MODEL` | `MiniCPM-OCR-Jev` |
| `CHAT_DOCUMENTS_THRESHOLD` | 暂定 `0.9`，上线前使用目标样本校准 |
| `CHAT_DOCUMENTS_BUDGET_SECONDS` | `600`，人工等待时间另计 |
| `CHAT_DOCUMENTS_MAX_BYTES` | `52428800`，上传入口也受 `chat.resources.upload.max-file-size` 限制 |
| `CHAT_DOCUMENTS_MAX_PAGES` | `200`，按本次请求范围计算 |
| `CHAT_DOCUMENTS_MAX_CHARACTERS` | `2000000` |
| `CHAT_DOCUMENTS_READ_CHARACTERS` | `40000`，Agent 按 `nextOffset` 继续读取 |

OCR 在配置 API Key 后默认可用，无需部署验证开关或会话开启操作。本地解析质量低且适合 OCR 时自动识别。OCR 完成后状态为 RECOGNIZED，正文可读取和用于用户请求的总结、生成，不自动调用 Jev。仅当用户明确要求审核或核对识别准确性时调用 review_ocr，创建 kind=REVIEW 的独立任务。复核任务保存原任务引用和候选快照，评分失败、低分或取消均不撤销原识别结果。文档卡锚定原附件消息，随对话滚动；正常处理仅显示进度，只有 Jev 有效低分才展开人工核对。技术故障展示重试入口。旧版 `CHAT_DOCUMENTS_CLOUD_VALIDATED` 和会话开关不再参与可用性判定。已有白名单配置 `chat.resources.upload.allowed-mime-types` 已由任意附件上传行为取代。媒体签名校验继续生效，HTML 等主动源文件强制下载。

OCR 复核使用 ModelBest 的 MiniCPM-OCR-Jev。仓库现有 TypeSafe Jev 的配置属于另一服务。

## 接口契约

按需要在本地把 PDF 页面渲染为 PNG，然后使用 `POST /v1/files` 上传，保存 `file_id`。调用 `POST /v1/document-parse/jobs` 时携带稳定 `Idempotency-Key`，保存作业 ID，并查询 `GET /v1/document-parse/jobs/{id}`。请求设置 `upright=false`，识别所有印章。返回的页数、尺寸、方向、块状态、区域坐标均需有效。部分成功结果继续封存。

使用同一区域的原图和候选文本调用 `POST /v1/chat/completions`，模型为 `MiniCPM-OCR-Jev`。单块请求通过 `messages` 传入图片和文字，扩展参数使用 `minicpm_ocr_jev: {mode: "score", category: "text|table|formula"}`；供应商返回的单项 `case_id` 为 `"0"`，本地块 ID 仅用于任务关联和日志。独立复核任务仍使用自身阈值决定是否需要人工核对。只接受 `verdict=scored` 且分数在 0–1 范围内的评分。缺原图、评分错误需要重试。装饰图片块不作为文字失败；疑似内容遗漏会追加整页 Jev 复核，低分再请求用户决定。表格使用 HTML 候选文本。

请求自由文本提示的能力未在托管文档接口中开放，因此重识别卡要求用户同意按原设置重跑。旧候选版本会失效。

官方说明见 [文档解析 API](https://platform.modelbest.ai/console/docs/api/document-parse) 和 [Jev API](https://github.com/OpenBMB/MiniCPM-OCR/blob/main/docs/minicpm_ocr_jev_api_zh.md)。

## 自动化验证

离线测试覆盖本地 HTML、DOCX、混合 PDF，草稿隔离、确认版本、低分与评分失败的区别、生成依赖约束、托管 HTTP 契约和实际中文 DOCX/PDF 渲染。缺字形回归样本同时包含可提取中文与不可显示字形，以防止仅验证文本提取造成误放行。

`DocumentRepositoryTest` 需要一次性 PostgreSQL，设置 `DOCUMENT_TEST_DATABASE_URL`。测试会清理该数据库中的测试表，应指向专用临时数据库。

真实接口探针只发送程序生成的英文测试页。在系统环境或本地 `.env` 中配置 `MODELBEST_API_KEY` 后，显式启用 `document.live-test` 运行。探针通过与应用相同的 `.env` 配置绑定入口取得凭据，可选 `MODELBEST_BASE_URL` 系统环境变量切换探针目标。

```bash
mvn -pl backend -am -DargLine=-Djava.awt.headless=true \
  -Ddocument.live-test=true -Dtest=ModelBestLiveContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

真实探针通过后，再用有已知正确答案的目标样本检查正文、表格、公式、扫描件和空白页，记录本地 GOOD 的误放行情况、覆盖异常、Jev 分数、人工复核比例，确定阈值。在两套运行时各验证上传、低分接受、人工修正、重识别、刷新恢复、取消和文件交付。

## 当前边界

覆盖检查通过页面状态和原图暗像素与返回区域的重合检测明显遗漏。像素检查仅触发额外整页 Jev 复核，不直接强制人工修订。整页检查作为独立完整性证据，不重复拼入放行正文，仍需代表性样本验收。复杂表格、修订记录、批注和原版式可能需要用户选择简化输出。多帧图片要求转为 PDF，以避免只读取第一帧。

任务通过数据库锁、版本和租约协调。处理重启后复用持久化的云端文件及作业 ID。资源、聊天消息与生成任务完成状态在同一事务内提交。崩溃的对话续跑展示人工继续入口，以复用已有工具任务。

归档会话的待续跑任务以 CANCELLED 结束，保留已识别内容。领取按最近调度时间轮转，释放租约时更新时间，避免繁忙会话或历史任务反复占据队首。排查 RECOGNIZED + QUEUED 且没有新 Agent Run 的任务时，同时检查队首任务与所属会话状态。续跑开始、结束、归档取消均记录 INFO，错误事件记录 ERROR，抛出的异常附完整堆栈。

## 分层、配置来源与日志定位

Controller 位于 `document/interfaces/web`，只接收认证用户并调用 `document/application`。业务服务组合领域状态和基础设施适配器。普通数据库操作由 MyBatis-Plus Entity / BaseMapper 承担，幂等插入、行锁与任务领取使用 `mapper/document/*.xml`。生产文档代码不使用 JdbcTemplate。

`DocumentEnvironmentPostProcessor` 复用共享 `EnvFileLoader`，在绑定 `chat.documents` 之前读取当前目录 `.env`，不存在时读取父目录 `.env`，支持从仓库根目录或 backend 目录启动。`.env.example` 提供全部文档配置示例。显式 Spring 配置与系统环境优先于本地文件。真实 `.env` 被 Git 忽略，属性对象的字符串表示也排除密钥。

任务提交时保存当前 TraceId，异步领取后恢复日志 MDC 的 `traceId` 和 `documentTaskId`，在作用域退出时清理。可按 `traceId` 联查提交、云端请求、状态转换、人工核对和检查点日志，或按 `taskId` 从持久化任务定位同一条链路。后台恢复沿用原任务的追踪标识。

INFO 区分新建提交（submitted）与幂等复用（reused，包含状态和版本），记录云端调用耗时、状态变化、Jev 块 ID / 分数 / verdict / 是否为整页检查和人工确认。WARN 记录过期检查点。ERROR 记录云端拒绝、连接/超时、文档处理和后台续跑失败，使用 Throwable 参数输出完整异常堆栈、cause 和 suppressed 异常，不再手动拼接 StackTraceElement 数组。正文、OCR 候选、图片 Base64、密钥和供应商原始响应均不进入日志。Jev 请求额外记录模型、块 ID、类别、是否整页检查、图片尺寸、PNG 字节数和文字字符数；非 2xx 响应记录操作名、请求/响应大小、供应商请求 ID，以及限长脱敏后的错误字段（code/type/param/message/detail）。供应商回显的请求对象会被忽略，文字候选、数据 URL 和密钥会被遮盖；非 JSON 错误正文不会直接输出。任务详情仅增加失败阶段，供应商诊断留在后台日志。

LLM HTTP 请求/响应日志压缩为单行，JSON 正文去掉缩进，保留 JSON 字符串内的换行转义及框架对凭据头的遮盖。文档云端请求超时覆盖响应正文读取阶段（默认 60 秒），正文最多 16 MiB，超时保留 HttpTimeoutException 原因链。

重复调用 parse_document 会命中旧任务，并不自动重试 FAILED 状态。需要在文档卡使用重试动作重新排队；确认日志出现新的 ModelBest 请求后再判断新一轮结果。

排查 HTTP 400 时，先按 TraceId 找到 `ModelBest request rejected`，核对 operation 和 error，再关联此前的 `ModelBest Jev review` 查看失败块及图片大小。`/files` 成功、OCR 作业成功且已进入 VERIFYING 时，不应将复核 400 解释为原文件打不开，也不应仅靠转换 PDF/JPG 重试。

`V20261106_01__document_schema_comments.sql` 使用独立迁移给两张文档表、全部字段和附件扩展名补充数据库注释，可兼容已经执行建表迁移的数据库。

`V20261106_02__automatic_document_ocr.sql` 标记历史授权表停用。工作调度会在凭据就绪时自动恢复旧版待开启或服务未配置的任务，已取消的请求保持取消。低分人工确认及评分失败约束继续执行。

`V20261106_03__recheck_ocr_coverage.sql` 仅重新排队旧版像素覆盖误判导致失败、尚未人工决定且未取消的任务，复用云端作业重新执行 Jev。迁移不放行草稿，也不覆盖人工决定。

### Jev 返回 content_policy_violation

这是供应商内容审核拒绝，不是请求超时。任务详情保留审核拒绝分类和供应商请求 ID，独立复核标记失败，原识别正文仍可读，并按不可自动重试故障处理。不要将其归因于 PDF 损坏、服务读不到文件，也不要尝试改写文字或改变图片来绕过审核。携带请求 ID 联系供应商核查。

脚本与服务的对比必须使用同一个实际裁剪块和同一份 OCR 候选，而不是合成图片或整页原图；在不记录凭据及证件正文的前提下比较 HTTP 状态、错误码和供应商请求 ID。2026-11-06 的毕业证任务中，服务的第二块为 37×185 PNG（10699 字节），独立 Java 与 Python 对该相同块均收到 HTTP 400 / content_policy_violation，不能用此前合成图片返回 200 推断真实材料也会通过。

## 按需质量复核与人工纠错

两套 Agent 均通过 review_ocr(taskId) 主动创建复核任务，只有用户明确要求才调用，不能因为内容重要就自行调用。parse_document(taskId) 查询或分页读取任务；复核任务通过 sourceTaskIds 关联识别任务。识别与复核具有独立状态、预算和取消操作。历史失败任务保持原状态，可从文档卡重试识别；历史正在 VERIFYING 的 PARSE 任务按新默认策略完成识别，不再继续自动评分。

有效评分低于阈值时，独立复核进入 WAITING_CONFIRMATION，保留原图/文字对照、接受原识别和人工修改功能。人工确认后修正版保存在 REVIEW 任务，状态 CONFIRMED，通过该任务读取修正版；原 PARSE 任务不变。评分技术错误不视为低分；HTTP 错误展示失败和重试入口，不提供低分接受操作。

## Harness 文档等待与空最终回复

模型边界会检查当前最后一条工具结果：文档仍在后台识别、等待人工确认或已失败时，下一次模型请求设置 tool_choice=none，让本轮以进度或诊断说明结束，避免反复查询耗尽 SDK 迭代预算。新用户消息或已完成识别的结果不受此前 pending 状态限制。SDK 在达到最大迭代次数时发出的无工具总结请求也显式设置 none，避免模型从历史消息中继续生成工具调用。

2026-11-06 的 run 247 在文档轮询耗尽迭代次数后，最后的模型响应仍为 stop_reason=tool_use，最终 Msg 的 generateReason=MAX_ITERATIONS 且没有正文。后台识别已完成，该错误属于 Agent 收尾流程。缺失最终文字时记录 generateReason、未执行工具数量和结果是否存在；不把思考内容或工具调用伪装成完成答复。
