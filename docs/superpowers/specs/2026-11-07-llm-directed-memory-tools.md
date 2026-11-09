# LLM 自主记忆：HAssistant 与 Harness 统一设计

日期：2026-11-07  
状态：重新设计；产品原则已确认，本文定义实现方案。代码已接入；验证记录见文末。

## 1. 目标与已确定原则

当前主模型自主判断何时回忆、记住什么、如何修正以及何时遗忘。Mem0 通过工具被访问。

- 不自动召回、不预检索、不注入记忆正文，不用后台 LLM 补记。
- 只有当前执行中的模型（包括协作 Agent）显式调用写入工具才创建或改变正式记忆。系统重试已提交的操作不构成新的记忆决策。
- 写入内容由主模型定稿，Mem0 使用 `infer=false`。不存在第二次提取或自主合并。
- 只按价值保存。关键背景、决定、纠正、重要经历、原因、最终进展和未完成事项都可能值得记住；不要求每轮或会话结束必存。
- 模型可以更新、取代、软删除、恢复。保留历史，不设置统一过期时间。
- 写入成功后即生效，不依赖整轮回答成功，不随回答中断回滚。
- 用户、稳定 Agent、根会话三种范围；同根会话协作者共享会话记忆，可主动检索当前用户的历史会话。
- 来源可追溯到已持久化的产品聊天消息。第一期不提供完整工具执行日志的原文检索。
- 首期覆盖普通 HAssistant、普通 Harness 及其协作路径。领域 Agent、电话专用路径保持现有策略。
- 既有 Mem0 记录继续可查；旧 MEMORY.md、每日账本成为按需读取的历史来源，不自动迁移。

## 2. 架构：一个记忆模块，两个框架 Adapter

```text
HAssistant / LangChain4j       Harness / AgentScope
          │                         │
   工具 Adapter               工具 Adapter
          └────────────┬────────────┘
                       │ 可信执行身份 + 工具请求
                  AgentMemory 模块
           ┌───────────┼────────────────┐
           │           │                │
     现有 Mem0Gateway  PostgreSQL       来源读取
     正文、语义搜索     归属、版本、状态   产品聊天消息
                       修订与操作记录    旧 Markdown
```

`AgentMemory` 是模型工具唯一依赖的应用模块。框架 Adapter 负责工具声明、执行身份和结果序列化，不分别实现归属检查、状态过滤和版本更新。

内部继续复用 `Mem0Gateway`、现有控制索引和带鉴权的会话读取能力。用户管理接口与模型工具共用操作规则，不复制出第二套增改删。

不要求重写领域 Agent 现有 `LongTermMemoryRuntime`，也不让新的显式工具走自动 capture outbox。模型已经提交记忆正文，不再回读整轮消息提取。

## 3. 模型看到什么

系统提示提供工具用途、可选范围、保存价值的准则；不提供实际记忆正文，也不命令模型每次先查或结束必存。

记忆是历史证据，可能过时，不是新的系统指令。模型判断其与当前任务是否相关，并在需要时查看来源。

### 3.1 工具接口

| 工具 | 输入重点 | 行为 |
| --- | --- | --- |
| `memory_search` | query、scopes、sessionRange、source、statuses | 按模型选择检索正式记忆或旧文件，返回有界结果 |
| `memory_get` | memoryId、可选 revision | 读取一条当前记忆或明确指定的旧版本 |
| `memory_save` | text、scope、可选 sourceRefs | 原样保存，自动记录当前执行来源 |
| `memory_update` | memoryId、expectedVersion、text、可选 sourceRefs | 修订同一事实，保留旧版本 |
| `memory_supersede` | memoryId、expectedVersion、text、可选 sourceRefs | 创建后继记忆并将旧记忆标记为已取代 |
| `memory_set_status` | memoryId、expectedVersion、status | 标记完成、过时、软删除，或恢复有效 |
| `memory_history` | memoryId、cursor | 分页读取修订与取代关系 |
| `memory_operation` | operationRef | 查询结果不明的已提交操作，不重新发起写入 |
| `memory_read_source` | sourceRef、cursor | 带鉴权读取来源消息或旧文件片段 |

`memory_update` 用于同一事实的修正，例如用户纠正日期。`memory_supersede` 用于事情发生变化，例如从决定 A 改为决定 B；它保留两次决定的身份和关联。

删除通过 `memory_set_status(status=DELETED)` 执行，模型自主决定，无需逐条用户批准。恢复通过同一工具设为 ACTIVE，产生新的修订，不抹掉删除记录。若该记忆已有后继，返回关联信息和冲突，由模型明确决定如何处理，不能恢复出相互矛盾的两条当前结论。

`source` 默认为 MEM0；模型选择 LEGACY 才搜索旧 MEMORY.md / 每日账本。可以让模型连续调用两种来源，不在工具内部隐式扩展到另一来源。

`statuses` 默认 ACTIVE；显式选择才能搜索 COMPLETED、OBSOLETE、SUPERSEDED、DELETED。历史会话与历史版本是两个不同概念：前者由 `sessionRange` 控制，后者由 revision / statuses 控制。

### 3.2 返回结果

搜索结果包含：memoryId、正文、scope、status、version、更新时间、来源引用；有后继时附后继 ID。LEGACY 结果返回文件来源引用，不伪造 memoryId 或版本历史。

写入返回可核验的 memoryId、version、status；系统不能确认成功时，不返回成功。

统一区分：

- `OK`：操作完成，包括成功搜索但 items 为空。
- `CONFLICT`：版本已变化，模型可读取最新版本后重新决定。
- `UNAVAILABLE`：远端或依赖不可用，没有把故障伪装成无记忆。
- `PENDING`：远端结果不明，返回 operationRef，重试同一次调用不创建第二条。
- `NOT_FOUND`：记录不存在或当前身份不可访问，不泄露其他用户的存在性。

搜索和历史读取有分页、条数与字符预算。达到扫描预算时返回 truncated 标记；不能把被截断的搜索解释成已穷尽所有记忆。

## 4. 范围与可信身份

### 4.1 三种范围

| scope | 归属键 | 用途 |
| --- | --- | --- |
| USER | userId | 跨 Agent、跨会话有效的背景和偏好 |
| AGENT | userId + stableAgentId | 用户与一个稳定 Agent 的专属知识 |
| SESSION | userId + rootSessionId | 根会话及子会话共享的经历、决定和进展 |

SESSION 不包含当前执行 Agent ID，否则协作者会读到不同的记忆集合。写入者 ID 单独作为 provenance 保存。

稳定 Agent 包括系统定义和持久化的用户 Agent；临时协作者不创建永久 AGENT 身份。临时协作者可以使用 USER / SESSION，不能默默借用父 Agent 的 AGENT 归属。

`sessionRange`：CURRENT 仅当前根会话；HISTORICAL 仅其他根会话；ALL 包括两者。它只作用于 SESSION 搜索，不扩大 USER / AGENT 范围。检索历史会话不改变结果的原有 scope。

### 4.2 身份不由模型填写

由执行框架提供：userId、rootSessionId、actualSessionId、stableAgentId（可空）、sourceExecutionId、当前已持久化 userMessageId、toolCallId。

这些身份以请求上下文传递，不放在全局可变字段或仅依赖线程本地变量中；异步工具和协作执行也必须拿到各自正确身份。模型只提交语义范围和已获得的引用。

外部 Mem0 的 user/agent/run 字段只是存储编码，不能替代项目的 scope 校验。所有搜索、按 ID 读取、历史和来源读取都重新检查归属。

### 4.3 既有 RUN 兼容

当前 RUN 的实际键含 user + logicalAgent + rootSession。它不能直接重命名成新 SESSION，否则会造成协作共享语义错误。

新数据使用明确的 SESSION scope 和 rootSession 归属。旧 RUN 仍按原契约读取；模型选择会话范围时，模块可纳入经本地控制索引确认归属的旧 RUN，返回 legacyScope 标记和实际来源 Agent。无需批量改写旧正文。

## 5. 保存什么与如何关联来源

一条记忆表达一个可独立理解、检索的事实或事件，必要的原因与适用范围一起保存。事实类型不强制形成复杂分类表。

例如：

> h-agent 的记忆检索完全由当前 LLM 自主决定，不做自动召回。该约束适用于当前这次架构设计。

> 已决定采用 B：A 依赖公网，无法满足当前部署条件。登录流程已验证，文件上传仍待验证。

模型不能把建议写成用户已确认的决定，也不能把项目约束扩大成用户的永久偏好。系统可以校验格式和长度，不再用另一个 LLM 判断或改写。

来源包含根会话、实际会话、已存在的消息 ID、执行 ID、写入者和时间。当前用户消息可在工具调用时附加；尚未落库的最终助手回复不能被伪造成已存在的来源。

模型可以附上已读取过的其他 sourceRef，模块校验当前用户的访问权。系统自动附加当前执行来源只说明记忆产生于哪里，不声称该来源验证了记忆的全部内容。

回查复用带归属检查的产品聊天读取能力，不向模型暴露无 owner 校验的内部消息读取端口。来源被删除或不存在时明确返回 SOURCE_UNAVAILABLE，不生成替代原文。

## 6. Mem0 与 PostgreSQL 的职责

Mem0 保存当前正文并负责语义候选检索；PostgreSQL 保存稳定本地 ID、归属、当前状态/版本、来源、操作记录及不可变修订正文。

修订正文用于历史回查和故障恢复，不形成另一套在线语义检索。控制索引决定哪些远端候选可以返回给当前用户。普通搜索必须过滤失效、已删除及已取代记录；不能仅依赖 Mem0 返回前 K 条，再无声丢弃所有结果。模块按预算补取候选，预算不足时明确返回 truncated。

不假定现有 Mem0 history 可以完整恢复历史：当前 Java 适配器和部署源码的历史字段存在差异。新操作使用项目修订记录。旧记录尚无本地历史时，只展示能够核实的历史，不伪造变更前正文。

不添加与本期无关的图谱、自动重要性评分、自动遗忘定时器或后台记忆规划模型。

## 7. 写入一致性与并发

接口语义：只有工具报告 OK，才说明该操作已正式提交，可以按 ID 读取并参与后续检索。语义排序仍决定某个查询是否命中，不承诺每次进入前 K 条。

操作键由可信执行 ID + toolCallId 构成，操作记录保存完整归属和参数摘要。同一调用的传输重试重放原结果；不同调用不能仅因正文相同而被当作同一次操作。重复内容是否合并仍由模型判断。

每个记忆的写操作串行化并校验 expectedVersion；有结果不明的在途操作时，后续修改不能越过它。数据库版本计数本身不能保证远端修改原子性。

先记录待执行操作和恢复所需内容，再调用 Mem0，确认远端结果后提交新的本地状态与修订。远端成功、本地未提交时，从操作记录恢复，不重复创造记忆。取代操作完整提交之前不能报告成功；搜索期间依照操作状态排除不确定的候选。

Mem0 与 PostgreSQL 无跨库事务，因此不能承诺 exactly-once。远端超时时返回 PENDING，由后台对账推进同一个操作。后台只完成已由模型发起的操作，不自行决定新增、修改或删除记忆。对账未完成期间不得向模型宣称写入成功。

读写使用相同的当前版本和状态检查。删除是本地生命周期变化，不立即永久删除远端正文；默认工具读取必须尊重该状态，显式历史读取和恢复仍可访问。

## 8. 两条执行流程的接入

### HAssistant

- 在 ToolProvider 中绑定每次调用的可信身份，注册同一组记忆工具。
- 去掉 standard-chat 的 Mem0 自动 augmentation 和成功回合 capture。
- 保留知识库当前配置，不借本次改动重新开启当前已停用的知识库检索。
- 领域 Agent 的 recall/capture 策略不受此次 standard-chat 切换影响。

### Harness

- 注册项目工具，替换内置记忆工具，避免同名工具同时存在。
- 关闭 memory hooks、自动 MEMORY.md 注入、周期整理及压缩前 memory flush。
- 保留当前工作上下文、压缩摘要及会话原始日志。日志写入独立于记忆提取，不因关闭 hooks 消失。
- 协作 Agent 继承工具能力，绑定自己的实际会话/执行身份和同一个 rootSessionId。
- 旧 Markdown 通过 LEGACY 来源主动检索；不作为系统提示注入。
- 原页面保留历史文件查看能力并明确说明其历史来源身份；不将其修改误报为 Mem0 写入。

关闭内置 memory tools 同时会移除 SDK session_search/session_history；新项目工具负责本期已承诺的产品聊天来源回查。电话专用路径不注册新工具。

## 9. 实施顺序与验收

这是同一期的工程顺序，不缩减已确认范围：

1. 增加工具数据契约、可信执行身份、SESSION 语义、生命周期和修订/操作存储。
2. 在共享模块完成带归属检查的搜索、显式写入、冲突处理、历史/来源读取和 LEGACY 读取。
3. 增加两个 Adapter，接入 HAssistant 与 Harness（含协作身份），将日志 offload 从记忆维护中解耦。
4. 模块和 Adapter 就绪后，再关闭这两个入口原有自动召回/写入；同步调整说明及历史来源入口。
5. 验证普通聊天无隐式记忆调用、显式工具的读写/历史/删除恢复、协作共享、历史会话过滤、跨用户隔离以及超时重试行为。

关键验收：

- 模型不调用工具时，零记忆检索、零记忆写入，包括压缩与回合结束阶段。
- 相同 rootSession 的父子 Agent 能读到共同 SESSION 记忆；不同 rootSession 默认互不可见，模型明确检索历史时可访问当前用户历史。
- 用户归属校验覆盖搜索、按 ID、历史、恢复及来源读取。
- 成功写入在回答失败后仍可读取；结果不明不会报告成功或重复写入。
- 更新、取代、删除保留可核实的修订；默认搜索只返回有效状态。
- 没有匹配结果、依赖故障和扫描预算耗尽是不同的返回情况。
- 产品原文可以按需回查；旧文件没有被自动注入、改写或迁移。

## 实现补充

- 正文修订使用新的 Mem0 快照，本地事务切换当前远端 ID；旧向量仍由本地当前记录/生命周期过滤，不被默认工具搜索或领域召回泄漏。
- 远端结果不明时按精确 operation_key 过滤查找已提交快照，不盲目重复 add。Mem0 暂不支持相应过滤时操作保持 PENDING，不会谎报成功。
- 用户管理接口的更新和删除复用同一写入模块；新版本删除为软删除。
- Harness 的 Explore 模式继续禁止记忆变更；普通模式下记忆工具自主运行，不逐条请求批准。显式声明没有相应能力的用户 Agent 不被强行授予记忆工具。

## 验证记录（2026-11-07）

- Java 26 下后端编译通过。
- 15 个后端测试类、110 项测试通过，无失败、无跳过。其中新增测试覆盖 PostgreSQL 迁移与操作重放、并发重放、超时对账、SESSION 共享、版本/取代/删除恢复、跨用户隔离、来源读取、两框架工具适配、HTTP 过滤契约与独立日志落盘。
- PostgreSQL 17 使用临时 Docker 实例，每个数据库测试用独立 schema；验证后清理实例，未操作应用数据库。
- 前端记忆页面相关 30 项测试通过，修改页面的 ESLint 检查通过。
- 未进行真实 LLM 与已部署 Mem0 的端到端联调。远端行为通过受控 Gateway 和本地 HTTP 契约测试验证。

复现后端集成测试时，将 `AGENT_MEMORY_TEST_JDBC_URL` 指向专用测试 PostgreSQL（测试用户 postgres、密码 memory-test-only）。数据库测试只创建并清理独立随机 schema；未设置该变量时这些集成测试会被跳过。

```bash
JAVA_HOME=/path/to/jdk26 AGENT_MEMORY_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/memory_test \
  mvn -pl backend -am -Dtest=AgentMemoryToolsTest,MemoryToolAdaptersTest,MemorySourcesTest,Mem0MemoryFilterContractTest,ConversationLogMiddlewareTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
