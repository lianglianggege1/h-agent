# 自动化任务（定时 Agent）技术设计

> 目标：为 h-agent 平台提供可审计、可恢复、不会重复接纳的定时 Agent 能力。聊天内自然语言写操作先生成提案，管理页由用户直接操作；LangChain4j / AgentScope 共用同一套任务、运行与投递语义；XXL-Job 只承载触发，不承载业务事实。
>
> 唯一产品原型：`docs/prd/2026-09-05-automation-proposal-prototype.html`。`automation-xxljob-prototype.html` 是历史入口，仅跳转到本原型，不再作为实现依据。

## 1. 设计原则与默认语义

1. **PostgreSQL 是唯一事实源**：任务定义、当前版本、启用名额、调度同步状态、运行、投递和审计均以本地库为准；XXL-Job 是可重建的调度投影。
2. **至少一次触发，至多一次接纳**：外部触发可能重复；平台用数据库唯一幂等键接纳一次运行，不能假设 XXL-Job 只调用一次。
3. **运行隔离**：每次自动化创建独立运行会话/工作区。来源会话只作为有截止时间的只读上下文，不复用活跃聊天会话执行。
4. **定义与运行快照分离**：编辑仅影响后续运行。已接纳运行使用创建时冻结的任务版本、指令、Agent、能力授权和投递目标快照。
5. **最小权限**：自动化不能因为“无人值守”获得更多权限。每次运行重新校验所有者、Agent、资源和凭证引用；高风险工具遵循明确的任务级授权或运行时审批策略。
6. **安全默认值**：新建任务默认关闭；每个用户同时最多开启 3 个；错过触发默认跳过、重叠运行默认跳过、Agent 失败默认不自动重跑、投递失败可自动重试。

### 1.1 用户可见的默认值

| 语义 | 默认值 | 原因 |
| --- | --- | --- |
| 新建状态 | `DISABLED`（关闭） | 用户需明确开启后才产生无人值守执行 |
| 开启上限 | 每个用户最多 3 个（包含同步中/同步失败） | 控制资源和意外副作用；两个 Agent 框架共用额度 |
| 时区 | 创建者 IANA 时区 | 避免服务器时区泄漏到产品语义 |
| DST 不存在时间 | 跳过本次 | 不把 02:30 静默改成别的时间 |
| DST 重复时间 | 只执行第一次 | 避免一次日程执行两次 |
| 错过触发（misfire） | `SKIP` | 恢复后不形成补跑风暴 |
| 重叠（上一轮未结束） | `SKIP`，记录原因 | Agent 任务通常有外部副作用 |
| 运行超时 | 15 分钟，可在平台上限内调整 | 防止永久占用执行槽 |
| Agent Run 重试 | 关闭；仅明确声明幂等的任务可开启 | 防止重复发信、写库或下单 |
| 投递重试 | 指数退避 + 抖动，达到上限后死信 | 投递与 Agent Run 解耦 |

创建/编辑确认界面必须展示人类可读规则、IANA 时区、未来三次触发、运行策略和授权范围；Cron 仅放在高级设置中。创建成功只表示任务已保存且处于关闭状态，不应显示“已生效”或“下次运行”。

## 2. 模块与接口

沿用既有 DDD 分层，但把复杂性收进四个深模块。REST、聊天工具、XXL-Job handler 都只调用这些模块的接口，不自行拼状态机。

```text
interfaces
  ├─ REST：任务、提案、开启/关闭、运行、取消
  ├─ Chat tools：写操作生成提案；读操作查询
  └─ XXL-Job handler：提交 TriggerEnvelope
application/domain
  ├─ AutomationDefinitionModule：提案、任务版本、授权和开启名额
  ├─ SchedulerProjectionModule：同步 outbox、XXL-Job adapter 与对账
  ├─ RunAdmissionModule：防重、配额、重入策略、运行协调
  └─ DeliveryModule：投递意图、Sink、重试与死信
infrastructure
  ├─ PostgreSQL
  ├─ XXL-Job Admin adapter
  ├─ LangChain4j / AgentScope execution adapters
  └─ Session / Email delivery adapters
```

关键接口保持小而稳定：

```text
confirmProposal(proposalId, expectedRevision, idempotencyKey) -> TaskView
enableTask(taskId, expectedRevision, idempotencyKey) -> TaskView
disableTask(taskId, expectedRevision, idempotencyKey) -> TaskView
submitTrigger(taskId, taskRevision, scheduledFor, triggerId) -> AdmissionResult
runNow(taskId, idempotencyKey) -> RunView
requestCancel(runId) -> RunView
reconcileSchedulerProjection(batchSize) -> ReconcileSummary
deliverPending(batchSize) -> DeliverySummary
```

调度、权限、并发、运行和投递状态都隐藏在模块内部；调用方只处理稳定结果和领域错误。

## 3. 五条关键链路

### 3.1 创建、提案确认与开启额度

聊天中的创建/修改/开启/关闭/删除均先写 `automation_proposal`；管理页由用户直接发命令。两条入口最终调用同一应用模块。

**创建**在本地事务中写入 `DISABLED` 任务，不占开启名额，不注册/启动 XXL-Job，也没有 `next_run_at`。确认卡片和成功提示都应明确“已创建，当前关闭”。

**开启**必须在数据库内原子占用用户名额，不能先在前端计数：

```text
本地事务
→ 幂等校验 + 锁定/更新 automation_user_quota
→ enabled_count < 3 时原子 +1，否则回滚并返回 409
→ task: DISABLED → ACTIVE，revision +1
→ 写 scheduler_sync_outbox(desired_state=START)
→ 写 audit_event
```

推荐用 `automation_user_quota(user_id PK, enabled_count CHECK 0..3)` 的条件更新实现；任务状态变更与计数必须在同一事务。关闭或删除时原子释放名额并写 STOP/REMOVE 期望状态。定期对账 `enabled_count` 与 ACTIVE 任务数，漂移时告警并修复。

超过上限返回稳定错误：

```text
HTTP 409
code: AUTOMATION_ACTIVE_LIMIT_EXCEEDED
message: 最多同时开启 3 个自动化任务，请先关闭一个任务后再试。
```

同步中或同步失败的 ACTIVE 任务仍占名额，避免依赖异常时绕过限制。重复开启同一任务幂等成功且不重复计数；4 个并发开启请求最多只有 3 个成功。

### 3.2 调度同步：本地事务 + outbox

```text
任务开启/编辑/关闭/删除
→ 本地事务：写 task 新版本 + scheduler_sync_outbox + audit_event
→ 事务提交
→ SchedulerProjectionModule 幂等调用 XXL-Job Admin
→ 记录 xxl_job_id / synced_revision / sync_status
→ 定期对账并修复漂移
```

**禁止**把本地数据库提交与 XXL-Job HTTP 注册描述为同一事务。外部系统不能参加本地事务；同步失败时任务保留期望状态并显示 `SYNC_FAILED`，在同步成功前不显示“已开始调度”。

XXL-Job Admin 当前本机部署已确认是 **3.4.1**。登录页实际提交到 `/auth/doLogin`，任务管理页面实际调用 `/jobinfo/insert|update|delete|start|stop|trigger|pageList`。其中 delete/start/stop 的表单参数为 `ids` 数组，trigger 使用单个 `id`，均不是稳定、正式的任务管理 OpenAPI。`.docs/XxlJobTemplate.java` 及 DTO 是历史接入经验，只能参考调用形态，不能原样复制：其中 `/login`、`/jobinfo/add`、`/jobinfo/remove`、单值 `id` 和 `jobCron` 均与当前部署契约存在差异；当前调度配置字段应映射为 `scheduleConf`。

- 统一复用带连接池和连接/读取超时的 HTTP client；地址规范化，避免重复或缺少 `/`。
- Cookie 过期、401/302 回登录页时只自动重登一次并重放幂等请求；并发刷新登录态要串行化。
- 校验 HTTP 状态和响应 `code`；使用 typed exception，禁止吞异常后返回 `null`。
- 日志不得打印用户名、密码、Cookie、完整 executorParam 或可能含敏感信息的 DTO。
- 用显式表单映射代替反射 `BeanMapUtil`；对 3.4.1 的字段和返回格式做契约测试。
- Admin 地址和账号密码只从密钥配置读取；生产环境限制网络来源、使用专用低权限账号并绑定目标 jobGroup。执行器 `accessToken` 禁止使用默认值并需支持轮换。

由于这些 Admin 表单接口不接收 idempotency key，adapter 的“幂等”必须实现为**按期望状态收敛**，而不是简单重试 HTTP：

1. 每个任务使用稳定远端标识 `automation:v1:<taskId>`，编码在结构化 `executorParam` 中；`revision` 是独立字段，不能进入稳定标识。远端 `jobDesc` 仅作人类展示。
2. worker 以任务维度加带租约的数据库锁，并在每个副作用前重读任务的当前 revision/期望状态；outbox 版本落后时直接完成，不允许旧 START/UPDATE 覆盖新的 STOP/DELETE。
3. 创建前在指定 jobGroup 中分页调用 `pageList`，按 handler + 解码后的稳定标识查找并接管已有 Job。insert 成功但响应丢失时必须重新查找，禁止盲目再次 insert。
4. 若对账发现多个相同稳定标识，先停止全部重复项，再保留一个 canonical ID、删除其余项并告警；随后按最新版本 update/start。所有修复写审计事件。
5. start/stop/delete 超时后先读取远端状态再决定是否重试；目标已经达到即视为成功，delete 的“已不存在”也视为成功。只有验证远端已删除后才清除本地 `xxl_job_id`。

3.4.1 页面传递 delete/start/stop 的逻辑字段名是 `ids`，表单在线上编码为一个或多个 `ids[]=<jobId>`；adapter 契约测试必须覆盖这个实际 wire format，以及 trigger 的单值 `id`。

创建投影默认 `triggerStatus=0`。首次开启时调用 insert 后 start；关闭用 stop，删除用 delete。所有路径、参数和响应都封装在版本化 adapter 内，升级 XXL-Job 前先跑契约测试。推荐配置：

XXL-Job 3.4.1 的 Cron 按调度中心单一时区解释，不能为每个 Job 保留任意 IANA 时区及完整 DST 语义。因此平台采用混合调度：`task.zone_id == automation.xxl-job.scheduler-zone-id` 时投影到 XXL-Job；其他时区不创建 ACTIVE 远端投影，由本地 `next_run_at` + 数据库租约扫描。管理页必须显示 `XXL_JOB` 或 `LOCAL`，不能把本地调度误报成“XXL 同步成功”。任务从 XXL 时区改到其他时区时先把旧投影收敛为 STOPPED，再由本地调度接管。

| XXL-Job 字段 | 值 | 说明 |
| --- | --- | --- |
| `scheduleType` | `CRON` | 用户规则转换为 3.4.1 支持的配置 |
| `scheduleConf` | 转换后的 Cron | 不使用历史 DTO 中的 `jobCron` 字段 |
| `glueType` | `BEAN` | 执行器只提供统一 handler |
| `executorHandler` | `automationDispatchHandler` | 只负责快速提交触发，不同步等待 Agent Run |
| `executorParam` | `taskId + taskRevision` | 旧版本触发会被本地接纳层拒绝 |
| `executorRouteStrategy` | `CONSISTENT_HASH` | 同一任务稳定路由；最终防重仍在数据库 |
| `executorBlockStrategy` | `DISCARD_LATER` | 第一层抑制重入；本地接纳层仍是事实判定 |
| `misfireStrategy` | `DO_NOTHING` | 与产品默认 SKIP 一致 |
| `executorFailRetryCount` | `0` | 避免调度器重试制造重复 Run |
| `executorTimeout` | 较短的派发超时 | Agent Run 超时由平台管理，不占用 XXL handler |

### 3.3 触发接纳与 Agent Run

```text
XXL-Job / 手动运行
→ TriggerEnvelope(taskId, taskRevision, scheduledFor, triggerId)
→ 本地事务：校验任务 ACTIVE、版本仍匹配、所有者与授权有效
             + 检查配额/重入/错过策略
             + INSERT run(status=QUEUED，唯一幂等键)
             + 冻结 ExecutionSpec 快照
→ worker 以 CAS 将 QUEUED 领取为 RUNNING，异步执行独立 run session
→ 记录 Artifact 与终态
→ 同一事务写 delivery 意图
```

计划触发幂等键为 `(task_id, task_revision, scheduled_for, trigger_type)`；手动运行由客户端提供 idempotency key。数据库唯一约束是最终防线，不依赖进程内锁。

`scheduledFor` 是冻结版本日程中的**逻辑触发时刻**，不是 handler 的到达时间，也不能直接使用 `Instant.now()`。`RunAdmissionModule` 根据 `taskRevision` 的 `ScheduleSpec`，以 XXL 触发/日志时间为观测值，归一化到不晚于该观测值的最近一个合法日程点并转为 UTC；仅允许落在 misfire 容忍窗口内的候选，超窗则记为 `SKIPPED_MISFIRE`。这样，同一日程点的重复、延迟回调都会收敛到同一个唯一键。XXL 的 log/trigger ID 只用于追踪和辅助诊断，不能替代主幂等键；adapter 必须用重复回调、延迟回调和 DST 边界做契约测试。

运行状态机：

```text
ACCEPTED → QUEUED → RUNNING → SUCCEEDED | FAILED | TIMED_OUT | REJECTED_POLICY
                         ↘ CANCEL_REQUESTED → CANCELLED | SUCCEEDED | FAILED
触发未接纳：SKIPPED_DUPLICATE | SKIPPED_OVERLAP | SKIPPED_MISFIRE
```

`QUEUED` 必须落在 PostgreSQL，而不是仅存在于进程线程池。接纳提交后即使进程崩溃，周期分发器仍能重新发现该 Run；多个实例可以扫描同一批 ID，但只有一个实例能以条件更新完成 `QUEUED → RUNNING`。执行进程在领取后崩溃时不自动重跑 Agent（外部副作用通常不可证明幂等），超过执行上限后由恢复扫描以终态 CAS 收敛为 `TIMED_OUT` 并创建失败投递。这样选择的是“接纳不丢、执行默认至多一次”，而不是冒险重复外部副作用。

关闭/删除阻止新运行接纳，但不承诺终止正在执行的 Run；需要终止时单独调用 `requestCancel`，由执行 adapter 尽力取消。已接纳运行按快照完成并正确留痕。

### 3.4 Agent 执行上下文与权限

`ExecutionSpec` 至少冻结：

- `taskId/taskRevision/runId/scheduledFor`；
- instruction、Agent ID 与可解析的 Agent/Skill 版本；
- 来源会话 ID 与 `context_cutoff_at`；
- 允许的工具/资源范围、凭证引用和审批策略；
- overlap/misfire/timeout/retry 策略；
- delivery target 快照和数据保留等级。

执行 adapter 统一为 `execute(ExecutionSpec) -> RunResult`，在独立 run session 中运行。adapter 不得把原会话当作可变工作区；只加载截止时间之前、且当前用户仍有权访问的上下文。结果卡片携带 `run_session_id`，用户追问时显式进入该运行上下文。

凭证只保存引用，不复制 token/secret。接纳前先校验一次，执行前再解析当前有效凭证并重新鉴权；如果权限在接纳后被撤销、Agent 停用或资源已不可访问，已创建的 Run 以终态 `REJECTED_POLICY` 结束，并按普通失败结果生成可审计的 SESSION 投递，不发送业务 Artifact。V1 不引入新的 Run 暂停/恢复状态：高风险工具若要求人在回路且任务没有有效的持久授权，同样以 `REJECTED_POLICY` 结束，不能默认放行。未来若支持运行中审批，必须单独扩展状态机、截止时间和恢复幂等语义。

在任务级能力授权尚未实现的 V1，安全基线是：AgentScope 自动化固定使用 `DONT_ASK`，任何需要人工批准的动作直接拒绝；LangChain4j 自动化会话不暴露 Shell、文件系统、生成、Skill 或 MCP 工具，只允许无工具模型推理。不能复用普通聊天的全量工具集来“临时实现”无人值守能力。后续只有在确认页能展示并冻结 `allowed_tools / resource_scope / credential_refs`，且执行层按该快照强制过滤后，才可逐项开放工具。

### 3.5 Artifact 与投递

Run 与投递解耦，但不能把投递状态压在 `automation_run.delivery_status` 一个字段里：

```text
Run 终态 + Artifact
→ 同一事务创建 automation_delivery（每个 target 一条，保存目标快照）
→ DeliveryModule 以租约领取 PENDING/RETRYING
→ 传递 delivery_id 作为尝试关联/下游幂等键
→ DELIVERED | RETRYING(next_attempt_at) | DEAD_LETTER
```

V1 支持 `SESSION` 和 `NONE`：

- `SESSION`：向指定会话投递结果/失败卡片。
- `NONE`：不投递结果，只能在管理页查看；它**不表示**允许 Agent 把目的地写在 prompt 中后绕过平台投递与审批。Agent 使用外部工具仍受任务能力授权约束。

V1.5 增加 `EMAIL`，V2 增加 `WEBHOOK` 和多目的地。每个目的地独立记录状态和尝试；编辑任务后，旧 Run 仍按其投递快照发送，避免结果被投到新地址。

投递保证按 Sink 区分：`SESSION` 由平台控制，可借助唯一约束做到用户可见结果至多一次；Email/SMTP 等不支持幂等键的外部 Sink 只能保证**至少一次尝试**，发送成功但确认超时时，重试可能造成重复邮件。Webhook 应发送 `Idempotency-Key: <delivery_id>`，但只有下游兑现该契约时才能避免重复。管理页与审计日志必须显示尝试次数和“可能重复”，不能把 `delivery_id` 描述成跨所有 Sink 的 exactly-once 保证。

## 4. 领域模型与持久化

领域层使用类型而不是让调用方操作任意 JSON；持久化可以使用 JSONB，但必须由领域类型负责解析与校验。

| 实体 | 关键内容 |
| --- | --- |
| `automation_task` | owner、name、status、current_revision、next_run_at、scheduler_sync_status、retention_policy |
| `automation_task_revision` | `ScheduleSpec`、`ExecutionPolicy`、`AgentRef`、instruction、`CapabilityGrantRef`、`DeliveryTarget[]` |
| `automation_user_quota` | user_id、enabled_count（0..3）、revision |
| `automation_proposal` | action、typed payload、base_task_revision、status、expires_at、confirmed_by |
| `scheduler_sync_outbox` | task/version、desired_state、attempt、next_attempt_at、last_error |
| `automation_run` | idempotency_key、task_version、scheduled_for、trigger、status、spec_snapshot、queue/start/finish time、trace_id |
| `automation_run_attempt` | attempt、status、error_class、started/finished；仅启用 Run 重试时产生多条 |
| `automation_artifact` | run、resource ref、classification、retention/expires_at |
| `automation_delivery` | run、typed target snapshot、status、attempt、next_attempt_at、last_error |
| `automation_audit_event` | actor、action、target、before/after revision、request/idempotency key、time |

关键约束：

- proposal confirm 和开启/关闭命令都有唯一幂等键；
- ACTIVE 状态变更与用户 enabled_count 在同一事务；
- scheduled run 唯一 `(task_id, task_revision, scheduled_for, trigger_type)`；
- delivery 使用全局唯一 `delivery_id` 做关联；SESSION 严格去重，外部 Sink 明示至少一次及可能重复；
- 所有领取任务使用有期限租约和 fencing token，过期 worker 不能覆盖新 worker 的结果；
- 删除为软删除，保留期内运行和审计可追溯，之后按策略清理/匿名化。

## 5. 容量、观测与运维

- 启用上限是接纳规则，不替代运行配额；运行前仍检查用户/租户并发、每日 Run/Token/费用额度，全局执行池按租户公平排队。
- 记录并告警：scheduler sync lag、trigger lag、queue latency、run duration、skip/reject 原因、成功率、投递积压/死信、单位用户成本。
- 日志、Trace、Artifact 和错误消息做敏感信息脱敏；管理页只显示用户有权访问的 Trace。
- `runNow` 不改变周期节奏；默认遵循同一权限、配额、重入和超时策略。关闭任务是否允许手动运行必须由产品显式决定，V1 默认不允许。
- 管理页明确区分：任务开关状态、调度同步状态、Run 状态、投递状态，避免用一个“失败”概括四种问题。

## 6. 实施路径与验收

1. **P1 可靠定义**：默认关闭、原子开启额度、提案/确认幂等、任务版本、typed policy、scheduler sync outbox 与对账；聊天写工具改为只提案。
2. **P2 可靠运行**：TriggerEnvelope、数据库唯一防重、接纳/重入/错过策略、独立 run session、超时与取消、配额。
3. **P3 可靠投递**：Artifact、独立 delivery/attempt、SESSION/NONE、重扫与死信。
4. **P4 产品与观测**：管理页四类状态、未来三次触发、运行策略/权限摘要、Trace、审计和告警。
5. **P5 扩展**：EMAIL、WEBHOOK、多目标；只有出现第二个真实 adapter 时才增加新的 seam。

最低验收场景：创建 10 个任务均为关闭；重复开启同一任务不重复计数；4 个并发开启请求只有 3 个成功；关闭/删除释放名额；同步失败仍占名额且可恢复；insert 成功但响应丢失不创建重复 Job；乱序 START/UPDATE 不能覆盖较新的 STOP/DELETE；以及重复触发、重复确认、编辑/关闭/删除与旧触发竞态、DST 跳变、misfire、上一轮未结束、进程在 Run 完成/投递前后崩溃、投递重复、接纳后权限撤销、配额耗尽、超时和取消。
