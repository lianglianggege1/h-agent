# 自动化模块重新设计与实施计划

日期：2026-09-09。代码核查基线：`c1b7520`。状态：已按本计划完成首轮实现与验证。

本文依据用户最新约束重写，替代本文件上一版的设计结论。原始问题见[用户整理的 Action / Result](../specs/2026-09-09-automation-xxl-job-unification-design.md)。项目未上线，没有历史数据兼容负担，直接按目标设计重构，不为了保留现有代码而保留两套执行体系。

## 1. 重新判断

上一版的核心错误，是把现有实现的限制当成应当保留的架构约束：因为 handler 现在只入队，就认为 XXL 不该表达业务结果；因为已经有独立运行会话，就增加任务进展存储来拼接上下文。这偏离了用户要的统一、可追溯和自然延续。

| 问题 | 最终决定 | 要解决的事情 |
| --- | --- | --- |
| 调度与执行数据割裂 | 所有自动化执行必须经过 XXL，包括立即运行 | 不存在绕过 XXL 的执行事件 |
| XXL 提前报成功 | handler 等待实际执行到终态，再回报对应结果 | 排队、开始、模型返回文字都不能直接当作完成 |
| 执行事件无法统一追溯 | 一个逻辑执行事件对应一个 AutomationRun，关联其 XXL 执行身份 | 两侧追踪同一次执行；日志行、回调次数不要求 1:1 |
| 每次运行新建 Session | 删除此行为，任务绑定并持续复用已有 Session | 上下文、工作区、用户看到的历史自然延续 |
| 工具不可用 | 完整复用绑定 Session 在创建时确定的权限上下文 | 自动化与用户在该会话中的普通对话具有完全相同的工具行为 |
| 聊天创建割裂 | 持久化提案卡片，默认一次“创建并开启” | 修改、确认、启用、同步状态都在聊天完成 |
| 结果不可见 | 同会话原位展示，不同目标才投递 | 不再制造孤立会话或复制同一份回答 |

XXL 保存调度及执行结果，应用保存任务内容、过程和业务结果；二者共享可关联的执行身份、成败结论和可检查的同步状态。不要求把业务内容全部迁入 XXL 数据库。

## 2. 目标调用链

~~~text
聊天确认 / 管理页保存
  → 任务绑定已有 Session
  → 同步对应 XXL Job

定时到点 ────────────────────────┐
立即运行 → XXL trigger ──────────┤
                                ↓
                      automationExecutionHandler
                                ↓
                  绑定执行事件、冻结快照、创建 Run
                                ↓
                        获得会话执行许可
                                ↓
                   在绑定 Session 中执行完整 Agent 轮次
                                ↓
                     持久化结果、终态、结果同步意图
                                ↓
                      向 XXL 回报真正成功或失败
~~~

立即运行的 HTTP 请求可以快速返回“已请求运行”，客户端按 requestId 查询后续 runId；XXL handler 必须覆盖完整执行。两种等待发生在不同位置，不能混淆。

后台只保留配置同步、消息更新、结果回报和故障恢复等维护扫描；它们不能创建或启动新的 Agent 执行。

## 3. XXL 是唯一执行入口

### 3.1 删除双路径

- 删除 AutomationPollingJob 的本地任务扫描、LOCAL 调度分支。
- 删除 controller/coordinator 中直接创建 MANUAL Run 并提交 worker 的路径。
- 删除 dispatchQueuedRuns() 独立领取业务执行的路径，避免 handler 结束后还有另一套执行生命周期。
- AutomationWorkerPool 没有其他职责时直接删除。Chat 内部线程池可以保留，但 handler 必须等待其结果并传播取消、超时。
- xxl-job.enabled=false 表示自动化运行不可用。开发与测试使用本地 XXL，不静默降级为直连 Agent。
- 暂停任务仍保留远端 Job，可以通过 XXL 手动运行；暂停只停止定时计划。首次配置未就绪时，立即运行显示等待配置，不改走本地。

### 3.2 执行事件的 1:1

| 身份 | 职责 |
| --- | --- |
| eventId / runId | 一次逻辑执行，业务层唯一；Run 可直接使用 eventId 作为 ID |
| manualRequestId | 用户一次立即运行操作的幂等身份，重复 HTTP 请求复用 |
| schedulerInstanceId + jobId + logId | XXL 执行关联，用于定位与结果回报，不能只存无来源的裸 logId |

手动请求先保存 requestId 与配置快照引用，调用 XXL trigger 时传入；定时入口通过 XXL 执行身份定位事件。XXL 调度侧传递逻辑 scheduledFor，以 taskId + revision + UTC 日程点作为定时防重依据，不能把到达时间误当计划时间。handler 首次绑定创建 Run，重复到达读取既有 Run，不能再次调用 Agent。

重复到达时，原 Run 已结束就读取同一终态；原 Run 仍在执行就等待其结果，不能提前返回成功。等待过程不再次占用会话执行许可，也不创建第二个根 Agent 执行。

网络重发可以产生多条传输记录，它们关联同一个逻辑事件，不增加实际执行次数。用户主动重跑是新的事件，必须再次经过 XXL，并保存 retryOfRunId。

不变量：每一个已开始的 AutomationRun 都具有 XXL 关联；每个逻辑事件最多启动一个根 Agent 执行；协作 Agent 属于该次执行过程，不另算一次自动化任务执行。

禁用、旧版本、重叠、重复等前置结果明确标记“未执行”和原因，不能计为成功完成任务。进入 handler 的请求留本地结果；XXL 在到达 handler 前就拒绝或派发失败的请求，通过请求/执行查询对账展示，不伪造已执行的 Agent Run。

### 3.3 立即运行协议

新增 gateway 方法 triggerOnce(taskRef, manualRequestId)，封装 /jobinfo/trigger。参数包含 taskId、revision、triggerType=MANUAL、requestId、协议版本。定时参数明确为 SCHEDULED。校验 jobId 与任务映射，不允许客户端或模型指定任意远端 Job。

MANUAL 不执行 Cron 日程归一化、misfire 校验，也不推进下次周期时间。相同 requestId 的不确定网络重试仍使用原参数，不能生成新的执行身份。

原生 Admin trigger 是异步请求，HTTP 成功不代表已经派发或拿到了 runId。请求状态包括 REQUESTED / DISPATCHING / DISPATCHED / FAILED / UNKNOWN，handler 到达后绑定 Run。UNKNOWN 先查证再重发；超时重发必须通过数据库事件幂等约束防止重复执行。

XXL Admin 原生“执行一次”按钮也要正确标识 MANUAL。当前 executor 上下文没有直接暴露 Admin TriggerType，需在集成层查询该执行来源或扩展受管任务的触发信封。不能把无 requestId 的人工操作误当成 Cron；来源未确认时先解析再执行。

### 3.4 handler 等待真实完成

XXL-Job 3.4.1 的 JobThread 执行 handler，并在退出执行段后提交结果回调。当前应用的提前返回是自身集成方式导致。[官方 JobThread](https://github.com/xuxueli/xxl-job/blob/3.4.1/xxl-job-core/src/main/java/com/xxl/job/core/thread/JobThread.java)

新增或重构为 AutomationExecutionModule.execute(trigger) → ExecutionOutcome：返回时，Agent 本轮已结束且结果已持久化。handler 显式调用 handleSuccess / handleFail / handleTimeout。XXL 上下文操作留在 handler 线程，不依赖 Reactor/通用线程池继承上下文。

| 本轮结果 | 应用状态 | XXL 结果 |
| --- | --- | --- |
| 执行完成，结果已保存 | SUCCEEDED | success，附 runId 与摘要 |
| 运行错误、必要工具失败、确定的策略阻断 | FAILED / REJECTED_POLICY | fail，附原因与 runId |
| 超时 | TIMED_OUT | timeout |
| 用户取消 | CANCELLED | fail，明确“已取消” |
| 前置拒绝或跳过，未执行 | NOT_EXECUTED + reason | 非业务成功，明确原因与关联事件 |
| 尚未结束或结果未持久化 | 非终态 / 结算异常 | 不能 success |

成功由结构化 ExecutionOutcome 和确定的执行信号判定。普通工具失败后恢复成功，不必判整轮失败；任务被权限阻断、无法完成却只返回解释文字，不能算成功。Agent 的业务完成报告需区分“完成”“无法完成”，可机械验证的任务增加结果检查。不声称仅靠通用模型文字就能证明所有业务目标完成。

任务指令要求发送消息、生成文件时，这些动作属于执行成功条件。平台额外通知独立展示投递状态，不用重跑 Agent 修复通知。工具调用需要批准时，进入该 Session 原有的批准状态机；XXL 执行保持未完成，直到用户处理或执行超时。

### 3.5 超时、并发和恢复同步重构

当前 gateway 写死 executorTimeout=30，应用执行上限是 15 分钟，两者必须同时修正。默认建议：会话/容量等待 5 分钟、Agent 执行 15 分钟、结果结算预算 1 分钟、XXL 外层保护 22 分钟，由统一配置派生。

同一 task 保留单活跃 Run 数据库约束。XXL 阻塞策略按“不重叠”语义配置，不使用覆盖执行偷偷打断共享会话。会话等待属于当前 XXL 事件，handler 保持未完成；等待超限明确失败。

取消、超时需要传至真实 Agent/tool 执行，并撤销本轮执行许可。Thread.interrupt 不等于所有外部动作已经停止；未确认停止时不释放会话给下一轮。迟到回调不得更新下一轮状态。

Run 终态和待回报结果同事务保存。正常结果走 XXL 原生回调；数据库恢复器补查、补报，绝不直接重跑 Agent。原生回调已有重试，但进程退出前的内存窗口仍需要持久化结算依据。[官方回调实现](https://github.com/xuxueli/xxl-job/blob/3.4.1/xxl-job-core/src/main/java/com/xxl/job/core/thread/TriggerCallbackThreadHelper.java)

Admin 回调 HTTP 成功只是接受处理，内部会拒绝重复终态；失联监控可能先写失败。因此回调返回成功不等于两侧已一致。[官方 JobCompleteHelper](https://github.com/xuxueli/xxl-job/blob/3.4.1/xxl-job-admin/src/main/java/com/xxl/job/admin/business/scheduler/thread/JobCompleteHelper.java)

实施必须包含可鉴权的结果查询与对账：回报后确认远端结果。若远端先落系统推定的失联失败，而本地已有确定终态，为受管 Job 提供带 eventId、版本和审计的受控校正入口；只修正推定状态，不覆盖另一份已确认业务终态，不重复触发子 Job。真实冲突保留待处理，不伪报一致。需要的小范围 Admin 扩展纳入交付，不退回双执行路径。

### 3.6 时区在 XXL 内完成

删除 LOCAL 时不能悄悄丢掉现有 IANA 时区需求，也不能把所有 Cron 当成上海时间。

目标方案：为受管 XXL Job 增加任务时区字段，创建、编辑、预览和真实触发统一使用它。XXL 3.4.1 的 CronExpression 支持设置时区，而当前 Cron 调度策略未传入任务时区，应在 Admin 做明确扩展。[Cron 策略](https://github.com/xuxueli/xxl-job/blob/3.4.1/xxl-job-admin/src/main/java/com/xxl/job/admin/business/scheduler/type/strategy/CronScheduleType.java)、[CronExpression](https://github.com/xuxueli/xxl-job/blob/3.4.1/xxl-job-admin/src/main/java/com/xxl/job/admin/business/scheduler/cron/CronExpression.java)

预览和执行使用同一 Cron 规则，IANA 标识严格校验。DST 默认规则：不存在的本地时间跳过，重复的本地时间只执行一次；库行为用测试验证，必要去重放在 XXL 调度侧。不能增加应用定时扫描作为捷径。

## 4. 去掉每轮新建 Session

### 4.1 绑定与复用

- AutomationTask.sessionId 必填，ExecutionSpec 冻结同一身份。
- 聊天创建默认绑定当前 Session，从服务端上下文解析。
- 管理页默认选择当前会话，可切换已有会话；没有会话时先完成一次正常会话创建，不在执行时制造临时会话。
- runner 删除 createSession()，使用绑定 Session 的 Agent、prompt、工作区和模型记忆。
- 每轮仍创建独立 AgentRun/AutomationRun，保证执行状态、时间、轨迹可追溯。
- 任务 Agent 从会话派生，删除与 session 可能冲突的重复配置。更换 Agent 需明确重绑定兼容会话，不能配置显示新 Agent 而执行旧 Agent。

需要独立上下文的任务，由用户主动选择独立会话。默认不再增加任务进展表、摘要接力机制，或用历史聚合代替实际 Session 复用。

### 4.2 真正延续模型上下文与工作区

验证两个 runtime 都加载 Session 的持久化状态，包括历史消息、压缩摘要、文件和必要工具状态。聊天记录可见不等于模型已读取；缓存清空、进程重启后也必须连续。

沿用现有会话压缩机制，不另建自动化记忆产品。每轮输入带任务名、执行时间、taskId，明确自动化来源。多个任务共享会话意味着共享上下文，不能声称它们有独立记忆；任务指令和日程使用本轮冻结快照，工具权限始终由绑定 Session 的权限上下文决定，历史消息本身不能修改权限。

### 4.3 用户聊天与自动化共用会话许可

现有 ChatStreamConcurrencyGuard 已按实际 Session 互斥，但抢不到许可就报错。扩展为统一执行许可：自动化可以有界等待；用户看到“自动化正在运行，可排队发送或取消本轮”。多个任务绑定同一会话遵守相同规则。

只保留一份会话许可：不能 automation 拿锁后再让 ChatService 二次抢锁，通过执行命令交接同一 permit。已提交的排队用户消息可靠保存并显示待处理，不覆盖流式消息；公平性防止新自动化不断插队。排队消息尚未成为模型输入，不提前记为已完成聊天轮次。

归档只影响列表整理，绑定任务仍能恢复原状态并运行；后台执行不假冒用户重新激活会话。删除会话时同一命令停用绑定任务，旧回调拒绝执行；不能静默换新 Session。正在执行的会话先取消并收敛，再删除状态。

### 4.4 权限上下文直接复用

自动化不拥有独立的工具权限模型。任务不保存 tool allowlist、denylist、capability grant 或自动化批准模式，也不在确认卡片中再次要求用户确认一套工具权限。

执行时通过 task.sessionId 解析并完整复用该 Session 在创建时确定的上下文：Agent Definition 及版本、工具集合、Skill/MCP 能力、ApprovalMode、PermissionContext、工作目录、资源可见范围和协作 Agent 的继承关系。正常聊天如何发现工具、检查参数和执行权限，自动化就走同一条路径。权限变化若产品未来允许发生，也只能修改 Session 本身；绑定该 Session 的后续自动化自然使用新状态。

这里删除的是自动化专属的二次校验，并不绕过正常 Session 已有的权限机制。工具自身的路径检查、用户资源归属校验、Agent Definition 能力范围等继续存在，因为普通聊天也受这些规则约束。

具体代码调整：

- 删除 AutomationExecutionSessionRegistry，以及 ChatModelConfig 中“自动化 Session 返回空工具集”的三个分支。
- AgentScopeAutomationAdapter 不再强制 DONT_ASK；ChatBackedAutomationRunner 不再接收或覆盖 approvalMode。
- ChatService 从绑定 Session 解析原 approvalMode 与 PermissionContext，按普通聊天的调用链执行；不能复制一份简化权限对象，也不能在执行后回写替换 Session 权限。
- LangChain4j、AgentScope、动态工具与协作 Agent 均使用普通聊天现有的 provider、权限计算及继承逻辑，不新增 AutomationToolPolicy、CapabilityGrant 或 ExecutionSpec 工具字段。
- ExecutionContext 仍可携带 runId 和 AUTOMATION 来源，供日志、消息和结果关联；任何工具 provider 或权限判断都不得用该来源改变结果。

若 Session 的模式是 BYPASS，自动化同样 BYPASS；若是 EXPLORE，自动化同样只读；若是 DONT_ASK，需要询问的调用同样拒绝；若是 DEFAULT/ACCEPT_EDITS 并产生 ASK，则创建与普通聊天相同的批准卡片，Run 与 XXL 保持执行中。用户批准后恢复同一个 AgentRun、AutomationRun 和 XXL 执行事件；拒绝则按实际 Agent 结果结束；超过统一超时则标记 TIMED_OUT。不能为了“无人值守”偷偷将 ASK 改成 DENY，也不能另设一套自动化白名单。

## 5. 工具可用性的根因修复

工具不可用的根因不是缺少任务级默认值，而是执行创建了新 Session，并且又对自动化身份增加了特殊限制。去掉这两层差异后，工具可用性由用户创建 Session 时的选择自然决定。

- LangChain4j：同一个 memoryId 使用和普通聊天相同的静态工具、Skill provider、MCP provider 与资源范围。
- AgentScope：同一个 userId/sessionId 恢复同一 AgentState 和 PermissionContext，不覆盖 mode 或规则。
- 工具调用、工具结果和批准事件继续写入同一 Session，使下一次定时执行与用户后续对话都能看到一致历史。
- 确定的权限拒绝和工具失败仍以结构化事件进入 ExecutionOutcome，禁止依靠模型回复中的文字判断成败。

需要验证的核心等价关系是：在同一 Session、相同输入和相同外部状态下，用户点击发送与 XXL 触发自动化所获得的工具列表、权限决定和资源范围完全一致；允许的差异只有触发来源、runId、时间和界面标识。

## 6. 聊天确认与结果展示

CREATE 卡片默认按钮“创建并开启”，展示做什么、什么时候、绑定会话、该会话当前批准模式、下次运行。批准模式只用于说明，不形成任务副本，也不在这里重新授权。确认事务写 task、session 绑定、额度占用、scheduler outbox、proposal 状态。

同步远端配置时卡片显示“正在配置”；确认 start 后显示“已开启”。失败重试原任务，不重复创建。更新/停用/删除提案按具体操作确认，不一律开启。

卡片持久化，SSE 只通知变化；刷新、离线、多端可恢复。修订增加 proposalRevision，旧版本不能批准新内容。24 小时过期只使操作失效，卡片仍可见，用户可继续对话产生新修订。

Agent 在 task.sessionId 内的结果就是用户可见结果，消息关联 runId，展示自动化来源与状态；失败也追加一次明确结果。

- deliverySessionId 等于 task.sessionId：原位关联结果，不再追加一份相同回答，投递视图标记“已在执行会话展示”。
- 不同目标：复用 DeliveryModule 投递摘要与原运行链接，按幂等键去重。
- NONE：不额外通知，执行会话与管理页仍可见。它不表示允许绕过权限自行发送消息。

补齐 AgentScope 提案漏传来源 sessionId 的缺口；新模型将会话绑定作为必填规则，避免两个工具默认值再次分叉。聊天页以持久化增量查询和活跃页面通知/轮询更新，按消息 ID、revision 合并，不能覆盖草稿或 streaming 输出。

## 7. 实施顺序与验收

### P0：真实 XXL 完整执行契约

1. 测试环境启动实际使用的 XXL 3.4.1，构造耗时成功、异常、拒绝、取消、超时场景。
2. 先证明“Agent 尚在执行时 XXL 不能已成功”，再替换快速入队模式。
3. 验证 trigger、来源、查询、回调真实契约。当前 gateway pageList 参数与官方 offset/pagesize 存在差异，需一起纠正，不沿用只匹配错误请求的 mock。
4. 将必要 Admin 扩展纳入版本管理与构建，覆盖任务时区、手动来源、结果对账，不留作“未来适配”。

验收：真实 Admin 在任务执行中保持未完成，结束后准确显示成功、失败、超时；接口差异均有契约测试。

### P1：唯一入口与执行事件闭环

涉及 controller、XxlJobAdminSchedulerGateway、AutomationXxlJobHandler、RunAdmissionModule、AutomationRunCoordinator、持久化和 XXL 扩展。

1. 统一触发信封、手动请求身份与事件关联。
2. handler 等待 execute()，可靠保存 Run 终态、结果与回报意图。
3. 调整超时、并发、取消、请求幂等与远端结果对账。
4. 删除 LOCAL、手动直连、独立执行扫描和无用 worker。
5. 各时区任务全部由 XXL 调度，预览、执行及 DST 规则一致。

验收：每次执行两侧可追溯；网络重发不执行第二次；Admin 不可用不会本地偷跑；进程退出后可对账，恢复不重跑业务动作。

### P2：Session 与权限上下文完整复用

涉及 AutomationTask/ExecutionSpec、两个 runtime adapter、ChatBackedAutomationRunner、ChatService、会话许可和消息存储。

1. task.sessionId 必填，Agent 从会话派生，runner 删除 createSession。
2. 删除自动化专属工具过滤、DONT_ASK 覆盖和任务能力策略；复用 Session 的完整权限上下文。
3. ExecutionContext 只做运行追踪，不得参与工具发现或权限判定；用等价测试覆盖两种 runtime、Skill/MCP 和协作 Agent。
4. ASK 复用普通批准卡片及恢复链路，XXL 等待同一执行终态；完成会话有界等待、公平执行、归档恢复、删除联动停用和重启恢复。
5. 删除同会话重复投递，消息原位关联 Run。

验收：连续运行 10 次，sessionId、AgentState 和工作区不变，Run 有 10 个；第二轮能读取第一轮文件与对话；重启仍延续；同一操作在聊天和自动化中的工具列表及权限决定一致；ASK 可批准后恢复；Session 批准模式不被自动化改写；两个任务与用户聊天不会并发破坏会话。

### P3：卡片确认与可见结果

涉及 AutomationProposalModule、DTO/controller、消息 payload、前端请求/事件、聊天页与自动化详情。

1. 持久化卡片、revision、结构化工具返回及不阻塞 Agent 的通知。
2. 默认创建并开启，支持原子确认、远端同步进度、修改、取消和过期恢复。
3. 后台消息更新、断线恢复、完整运行详情及 XXL 关联。
4. 删除跳管理页再开启的旧提示、默认 NONE 的不一致入口和独立运行会话展示。

验收：一句指令、一张卡片、一次确认完成创建开启；立即运行经过 XXL，在原会话完成工具工作并显示结果；重复确认与投递不重复。

### P4：故障验收与删除旧设计

1. 验证终态持久化前后崩溃、回报丢失、失联推定与迟到回调冲突、取消后迟到输出、共享会话抢占。
2. 验证同一 Session 在交互与自动化路径中的权限等价；BYPASS、EXPLORE、DONT_ASK、DEFAULT/ACCEPT_EDITS 及 Skill/MCP/协作者都保持会话原语义。
3. 删除旧兼容构造器、feature flag、多路径测试；同步重写旧技术设计与术语。
4. 未上线阶段统一整理表结构与迁移，不写历史回填或旧快照兼容分支；重建测试库验证全新安装。本次规划不删除本地数据库。

验收：代码不再存在自动化每轮 createSession、MANUAL 直连和 LOCAL 调度入口；完整业务验证通过才声称问题已解决。

## 8. 验证方法与最终 Result

测试覆盖 PostgreSQL 唯一约束/事务、真实 XXL 状态、两个 runtime 的权限管线、Session 重启恢复与浏览器交互。用确定性模型桩驱动工具，避免随机回复掩盖缺陷。

实施阶段基础命令：

~~~bash
source ~/.profile
mvn -o -pl backend -am test -Dtest='*Automation*,RunAdmissionModuleTest,SchedulerProjectionModuleTest' -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.rerunFailingTestsCount=0 -DforkedProcessTimeoutInSeconds=45 -Dstyle.color=never
~~~

已执行自动化相关后端测试与前端 lint/build；真实 Admin 集成仍需在配置了 XXL-Job Admin 的环境中执行，不用 mock 结果替代线上契约验证。

最终 Result：

1. 所有自动化执行由 XXL 发起，同一事件在两侧可追溯、结论一致，派发成功不冒充业务成功。
2. Agent 真实完成前 XXL 保持未完成；业务失败、策略拒绝、取消、超时准确回报。
3. 同一任务持续使用绑定 Session，历史、上下文、工作区连续，不再每轮新建 Session。
4. 自动化完整复用用户创建 Session 时确定的工具和权限上下文，不存在第二套自动化工具过滤或授权配置。
5. 聊天一次确认即创建并开启，过程和结果原位可见，同会话不重复投递。
