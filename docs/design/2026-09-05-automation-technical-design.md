# 自动化任务（定时 Agent）技术设计

> 目标：为 h-agent 平台提供「定时触发 Agent 运行」的自动化能力。聊天内自然语言 CRUD、管理页面、双框架执行（LangChain4j / AgentScope）、XXL-Job 调度、投递解耦。产品交互见 `docs/prd/2026-09-05-automation-proposal-prototype.html`（五个视图：用户操作图 / 机制时序图 / 投递解耦架构 / 聊天交互 / 管理页面）。

## 1. 总体架构

四层，沿用项目既有 DDD 分层，新建 `automation` 模块（骨架已存在，本文档定义目标形态）：

```
┌─ interfaces ── REST（管理页）+ 双框架工具声明（聊天入口）
├─ application ── 任务服务 + 提案服务 + 运行协调器 + 投递器
├─ domain ────── 任务 / 调度 / 运行 / 提案 聚合
└─ infrastructure ── XXL-Job 接入层 + 执行适配器 + 持久化（PostgreSQL）
```

核心数据流（两条链路，以投递意图落库为交接点）：

```
聊天自然语言 → Agent 解析五要素 → 提案（PENDING）
→ 用户确认（REST）→ 任务落库 + 注册 XXL-Job
→ 到点触发 → 协调器派发 → 执行适配器 → Agent Run → 产出 Artifact
→ 投递意图落库（delivery_status = PENDING）── Run 链路结束
→ 投递器消费 PENDING → Sink 分发（会话 / 邮箱 / 无）→ 投递状态 + Trace
```

## 2. 技术选型与目的

| 技术                                           | 用途             | 为什么 / 达到的目的                                                                                                             |
| -------------------------------------------- | -------------- | ----------------------------------------------------------------------------------------------------------------------- |
| **XXL-Job**（公司集群，B' 拓扑）                      | 到点触发           | 复用公司调度基础设施（故障转移、admin 可见可控）；每个任务一个 job，`executorHandler` 统一为 `automationDispatchHandler`，`executorParam = automationId` |
| **Spring** **`CronExpression`**              | 到期计算           | XXL-Job 只发心跳，next\_fire\_time 自己算，时区语义自己控                                                                               |
| **PostgreSQL**                               | 任务 / 提案 / 运行历史 | 领域事实源；XXL-Job 只是调度投影，允许漂移、定期对账                                                                                          |
| **提案表 + 确认端点**（自建）                           | 写操作把关          | 替代 HITL：Agent 只提案不生效，确认走轮次外 REST；双框架同构，零引擎依赖                                                                            |
| **LangChain4j / AgentScope 双执行适配器**          | Agent 执行       | 需求 1：h-agent 与 harness-agent 各自支持；适配器统一接口，运行时按任务 `runtime` 字段路由                                                         |
| **投递器模式**（Dispatcher + Sink SPI + 轻量 outbox） | 产物送达           | 需求 2/3：投递意图先落库再分发，Run 与投递两条链路解耦；失败只重试投递不重跑 Run；对齐 OpenClaw channel/none 语义                                              |
| **Langfuse**                                 | 观测             | 每次运行关联 Trace，管理页运行历史可跳转                                                                                                 |
| **Next.js 管理页**                              | 手动 CRUD        | `/automations` 页面（已存在），补齐创建弹窗、运行历史、投递状态                                                                                 |

## 3. 核心设计

### 3.1 调度接入（XXL-Job B' 拓扑）

- 每个生效任务 = 公司 XXL-Job 的一条 job，注册时带上任务名便于运维辨识。

- 执行器侧统一 handler：`automationDispatchHandler`，参数为 `automationId`——任务语义（用户归属、投递、历史）不进 XXL-Job，全部自己库。

- 对账：管理页只读展示 `xxl_job_id` 映射；XXL-Job 侧被人工删除的任务，下次编辑/重启时惰性补注册。

- 版本适配：公司集群 ≤ v3.4.2 无官方任务 CRUD OpenAPI，沿用团队既有实践（admin 接口 + Cookie 模板，`XxlJobTemplate`），**必须修复三个已知缺陷**：Cookie 401 不重登、异常吞掉返回 null、登录态刷新。

### 3.2 提案-确认（替代 HITL）

- Agent 工具只写 `automation_proposal` 表（PENDING），不碰任务表、不注册 XXL-Job。

- 确认/取消是纯 REST（`POST /proposals/{id}/confirm|cancel`），前端卡片直调，Agent 不参与。

- confirm 与任务创建、XXL-Job 注册同一事务边界；生效后向会话注入一条系统消息，Agent 下轮自然可见。

- 24 小时未确认自动过期；查询类工具直接读任务表实时回答。

### 3.3 执行路由（双框架）

- 领域模型上 `runtime` 枚举：`LANGCHAIN4J` / `AGENTSCOPE`。

- 执行适配器接口统一：`dispatch(task) → runId`。两个实现把自动化跑成**一次带完整上下文的聊天回合**（sessionId 沿用任务所属会话，模型/工具/记忆与人工聊天完全一致——这是「到点后 Agent 像收到普通消息一样干活」的关键）。

- 运行产出统一为 **Artifact**：结果文本 + 文件资源（复用既有会话资源体系）。

### 3.4 投递（Dispatcher + Sink + 轻量 outbox）

投递与主流程解耦，两条链路以「投递意图落库」为唯一交接点（对应原型视图 ③）：

- **Run 链路**：到点 → Agent Run → 产出 Artifact（结果文本 + 文件资源）→ 写 `automation_run.delivery_status = PENDING` → **Run 结束**。Run 不等待、不感知任何投递目的地。

- **投递链路**：投递器扫描 PENDING 记录 → 按任务创建时声明的 `delivery_type` 调用对应 Sink → 更新投递状态。状态机：`PENDING → DELIVERED / RETRYING / FAILED`。

关键语义：

- **轻量 outbox**：投递意图先落库再执行——进程在投递前崩溃，重启后扫描 PENDING 补发，产物不丢。不上消息队列，线程池 + 记录重扫即可。

- **会话是常驻观测通道**：无论任务选了什么投递目的，会话里永远有一条轻量运行记录（"运行完成，已发邮箱" / "运行完成，邮箱投递失败已重试"）——否则任务静默失败用户无从知晓。

- **投递目的是创建时声明的结构化字段**：管理页弹窗单选 + 聊天提案卡片展示，不是埋在 prompt 文本里。模型 `delivery_type` 枚举 + `delivery_config` JSON（如邮箱 `{"to": [...]}`），为多目的地预留结构、V1 不做。

- **Sink 分期**：V1 `SESSION`（默认）+ `NONE`（Agent 自行投递，目的地写 prompt 里用工具完成，平台只记运行状态）；V1.5 `EMAIL`（SMTP，正文 + 附件，独立重试）；V2 `WEBHOOK`、多目的地扇出。

- **明确边界**：平台只保证 Sink 枚举内的投递；任意长尾目的地（数据库、内部系统）走 NONE + Agent 工具，平台不为其背投递保证。

### 3.5 聊天工具（双框架同构）

两套声明、一个服务：

- LangChain4j：`@Tool` 声明，提案工具 + 查询工具。

- AgentScope：`@ToolParam` 声明，同一语义。

- 声明文案必须写清“仅提案、需用户在卡片确认后生效”，约束模型行为。

## 4. 数据模型（要点）

| 表                     | 关键字段                                                                                                                                                                                           |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `automation_task`     | id、user\_id、session\_id、name、instruction（自然语言 prompt，可引用 Skill）、schedule（type/cron/interval/zone）、runtime、delivery\_type、delivery\_config（JSON，如邮箱收件人）、status、xxl\_job\_id（映射只读）、next\_run\_at |
| `automation_proposal` | id、user\_id、session\_id、action（CREATE/UPDATE/PAUSE/RESUME/DELETE）、payload\_json（含投递目的）、status（PENDING/CONFIRMED/CANCELLED/EXPIRED）、expires\_at                                                 |
| `automation_run`      | id、task\_id、status、started\_at/finished\_at、duration、output\_text、delivery\_status（PENDING/DELIVERED/RETRYING/FAILED）、trace\_id                                                                |

## 5. 实施路径

1. **P1 地基**：提案表 + confirm/cancel 端点 + 双框架工具改提案模式；投递器骨架（PENDING 落库 + 重扫 + 仅 SESSION sink）。
2. **P2 调度**：XXL-Job 执行器接入（统一 handler + 注册/对账 + 模板三缺陷修复），替换现有 @Scheduled 轮询。
3. **P3 体验**：管理页创建弹窗（含投递目的选项）、运行历史 + Trace 跳转 + 投递状态、聊天卡片（提案/结果/失败）。
4. **P4 扩展**：邮箱 sink；之后 webhook、多目的地。

## 6. 关键风险

- **XXL-Job admin 接口为非官方 API**，版本升级可能变动——集中在 `XxlJobTemplate` 一处隔离，对账兜底。

- 提案过期扫描、投递重试等后台任务在单实例内用现有 @Scheduled 即可，多实例时借 XXL-Job 分片。

- Agent 误解析调度语义（cron 算错）——提案卡片展示解析结果 + 下次运行时间，错误在确认前可见。

