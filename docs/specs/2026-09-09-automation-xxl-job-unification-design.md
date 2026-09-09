# 自动化模块：立即运行走 XXL-Job + Session 复用 + 聊天卡片确认 + 执行可见性

## STAR 描述

### Situation（现状）

当前自动化模块采用**调度与执行解耦**的架构，存在五个问题：

#### 问题一：立即运行绕过 XXL-Job，数据割裂

- **定时调度**走 XXL-Job：Admin 按 CRON 触发 → `automationDispatchHandler` → `coordinator.submitScheduled()` → 创建 `SCHEDULED` 类型 Run
- **立即运行**绕过 XXL-Job：`POST /api/automations/{id}/runs` → `coordinator.runNow()` → 直接创建 `MANUAL` 类型 Run → 本地线程池执行

执行历史全部存储在 PostgreSQL 的 `automation_runs` 表中，XXL-Job 侧只保存调度日志。手动触发在 XXL-Job Admin 上完全不可见，无法从调度器层面回答"这个任务到底执行了几次"。

#### 问题二：每次执行都新建 Session，无上下文延续

`ChatBackedAutomationRunner.run()`（第 41 行）每次执行都调用 `chatSessionService.createSession()`，通过 `UUID.randomUUID().toString()` 生成全新 sessionId。整个链路中没有任何 session 复用机制：

```
AutomationRunCoordinator.execute()
  → adapter.execute(spec)
    → ChatBackedAutomationRunner.run()
      → chatSessionService.createSession()    ← 每次新建
        → UUID.randomUUID().toString()
```

这导致：
- Agent 每次执行都丢失上下文，无法参考上一次执行的结果
- 对于需要跨运行累积记忆的任务（如"检查上次遗留的问题是否修复了"）无法正常工作
- 同一自动化任务的执行历史散落在多个独立 session 中，用户在聊天界面无法看到完整的执行脉络

#### 问题三：聊天中创建自动化任务需跳转页面，体验割裂

当前聊天中创建自动化任务的流程：

```
用户在聊天中要求 Agent 创建定时任务
  → Agent 调用 create_automation_task 工具
  → 生成 PENDING 提案（24h 有效）
  → 工具返回纯文本提示："请到自动化管理页确认后才会创建"
  → 用户跳转到 /automations 管理页面
  → 在"待确认提案"区域点"确认"
  → 任务创建，但 enabled = false（默认关闭）
  → 用户再手动点开关启用
  → 任务才开始被调度
```

三个痛点：
1. **需跳转页面**：聊天中不能直接确认，打断对话流
2. **确认后默认关闭**：还需手动开启，两步操作
3. **工具只返回纯文本**：没有结构化的交互卡片，用户看不到任务参数预览

项目中已有一套"服务端主动推送交互卡片"的机制（审批卡片），通过 SSE 推送 `action_required` 事件，前端接收后渲染带按钮的卡片。但审批卡片是阻塞式的（Agent 挂起等待决策），提案卡片不需要阻塞 Agent 流。

#### 问题四：自动化执行时 Agent 工具全部被拒，无法完成实际任务

XXL-Job 定时触发后，`AutomationXxlJobHandler.dispatch()` → `coordinator.submitScheduled()` → `execute()` → `ChatBackedAutomationRunner.run()` 正确执行了。但 Agent 执行时所有工具调用都被拒绝，实际日志（2026-09-09 11:05）：

```
11:05:00.150  xxl-job register JobThread success, jobId:7, handler:AutomationXxlJobHandler#dispatch
11:05:00.245  [HarnessExecutor] Agent执行开始 userId=116, sessionId=18f9ceee-745a-455f-80bb-099628eeedd8, runId=49
11:06:36.307  [HarnessExecutor] Agent执行完成 userId=116, runId=49, replyLength=1968
```

Agent 的实际输出：

> "工具调用持续被拒。当前沙箱环境的工具权限被规则拦截（create_automation_task、read_file、glob_files 等都返回 Permission denied by rules）"

根因：`AutomationExecutionSessionRegistry` 在自动化执行期间将 sessionId 加入标记集合，`DynamicToolProvider` 在工具查找时检测到自动化会话后返回空工具集，导致 Agent 所有工具调用都被拒绝。自动化执行变成了"空跑"——Agent 被唤醒但没有工具可用，只能输出一段无意义的文本。

#### 问题五：执行结果不可见，用户聊天界面无任何记录

即使 Agent 成功执行，用户也看不到执行结果。因为执行创建了一个全新的孤立 session（如 `18f9ceee-745a-455f-80bb-099628eeedd8`），不是用户的聊天 session。这个 session 在用户的聊天列表中不显示，用户无法知道自动化任务是否执行、执行结果如何。

实际日志佐证：

```
sessionId=18f9ceee-745a-455f-80bb-099628eeedd8  ← 自动化执行新建的 session
runId=49                                         ← 执行完成
replyLength=1968                                 ← Agent 输出了内容
→ 用户聊天界面：无任何显示
```

这是问题二（Session 每次新建）的直接后果——执行结果落在一个孤立 session 中，用户聊天界面完全不可见。需要将自动化执行结果投递到用户可见的位置（如复用用户聊天 session 或通过投递机制推送结果卡片）。

### Task（问题）

1. **调度器上看不到完整执行历史**：XXL-Job Admin 上只能看到 CRON 定时触发的调度记录，手动触发完全不可见。运维需要跨两个系统拼凑执行全貌。
2. **业务执行记录与 XXL-Job 调度记录不是 1:1 对应**：手动运行只有本地 `automation_runs` 表有记录，XXL-Job 无对应。
3. **Session 每次新建**：同一自动化任务的多次执行之间没有上下文延续，Agent 无法在历史消息基础上继续工作。
4. **聊天中创建任务体验割裂**：需跳转页面确认 + 默认关闭需手动开启 + 无交互卡片。
5. **自动化执行时工具全部被拒**：Agent 被唤醒但无工具可用，执行变成空跑。
6. **执行结果不可见**：执行结果落在孤立 session 中，用户聊天界面无任何记录。

### Action（需要做什么）

#### 问题一：将"立即运行"改为走 XXL-Job 触发

用户点击"立即运行"时，不再直接创建 MANUAL Run 提交本地执行，而是通过 XXL-Job Admin API 手动触发一次 Job（`/jobinfo/trigger`），让 `automationDispatchHandler` 统一处理所有触发来源。涉及：
- Controller 层 `runNow()` 逻辑改为调用 XXL-Job Admin 的手动触发接口
- 数据库 `automation_runs` 表可能需要调整（triggerType、source 字段语义变更，与 XXL-Job 的 logId 建立映射关系）
- 幂等校验和并发控制逻辑需要适配新的触发链路
- 当 `automation.xxl-job.enabled=false`（本地开发环境）时降级为当前直连执行路径

#### 问题二：同一自动化任务复用同一 Session

在 `automation_tasks` 表上加一个 `session_id` 字段：
- 首次执行时创建 session 并将 sessionId 回写到 `automation_tasks`
- 后续执行复用该 session，`ChatBackedAutomationRunner.run()` 传入已有 sessionId 而非每次新建
- 用户在聊天界面中可以看到同一任务的所有执行历史

#### 问题三：聊天中通过卡片消息直接确认，无需跳转页面

将自动化提案改为**卡片消息**形式，嵌入聊天对话流中，区别于普通文本消息。交互模式：

- **服务端推送**：Agent 工具 `create_automation_task` 执行后，服务端通过 SSE 推送一个卡片事件（如 `type="proposal_card"`），payload 包含提案信息（任务名、指令、Cron、时区、Agent、投递方式等）
- **前端渲染**：聊天流中渲染提案卡片（非弹窗、非跳转），展示任务参数预览 + "确认"/"取消"按钮
- **确认**：用户点"确认" → 调用 `POST /api/automations/proposals/{id}/confirm` → 任务创建 + `enabled=true` + 同步到 XXL-Job + 默认启动
- **取消**：用户点"取消" → 调用 `POST /api/automations/proposals/{id}/discard` → 提案废弃
- **不操作（不确认不取消）**：卡片一直保留在对话流中，用户可以继续聊天修改提案内容（如调整 Cron、修改指令），Agent 更新提案后卡片刷新

与审批卡片的区别：

| | 审批卡片 | 提案卡片 |
|---|---|---|
| 阻塞 Agent 流 | 是，Agent 挂起等待 | 否，工具已返回，Agent 流正常结束 |
| SSE 事件类型 | `action_required` | `proposal_card`（新增） |
| 卡片位置 | 覆盖在对话流底部 | 作为卡片消息嵌入对话流 |
| 不操作后果 | Agent 一直挂起 | 卡片留在对话流中，可继续聊天修改 |
| 按钮 | 批准/拒绝 | 确认/取消 |

#### 问题四：自动化执行时放开工具权限

`AutomationExecutionSessionRegistry` 当前的设计意图是防止自动化执行时 Agent 动态授权工具（安全隔离），但实际效果是 Agent 所有工具调用都被拒绝，执行变成空跑。需要重新设计：
- 自动化执行时 Agent 应该能使用任务指令中所需的工具（如 read_file、write_file、memory_save、grep 等）
- 保留安全边界：不应允许自动化执行触发审批流程或修改自动化任务本身
- 可能的方案：将"全量拒绝"改为"白名单过滤"——只拒绝需要人工审批的工具和自动化管理工具，其余工具正常提供

#### 问题五：执行结果投递到用户可见位置

自动化执行结果需要投递到用户可见的位置，而非留在孤立 session 中。结合问题二的 Session 复用方案：
- 如果任务配置了 `deliverySink = SESSION`，执行结果应投递到用户的聊天 session 中，用户在聊天界面可以直接看到
- 如果任务配置了 `deliverySink = NONE`（Agent 自行投递），则由 Agent 在执行过程中自行完成投递（如写入 memory、发送消息等）
- 无论哪种投递方式，`automation_runs` 表中的执行记录（状态、output、error_message）应在自动化管理页面的运行历史中可见

### Result（期望目标）

1. **业务执行记录与 XXL-Job 调度记录 1:1 对应**：无论定时触发还是手动触发，每一次执行在 XXL-Job Admin 上都有一条调度日志，在本地 `automation_runs` 表中也有一条业务记录，两者通过 XXL-Job 的 `logId` 建立映射关系。运维人员只需查看 XXL-Job Admin 即可看到任务的完整执行次数和历史。
2. **Session 上下文延续**：同一自动化任务的多次执行共享同一 session，Agent 可以在历史消息基础上继续工作，用户在聊天界面可以查看完整的执行脉络。
3. **聊天中一步完成创建和启动**：用户在聊天中看到提案卡片，点"确认"即完成任务创建 + 启用 + 同步 XXL-Job，无需跳转页面。不操作时卡片留在对话流中，支持继续聊天修改提案内容。
4. **自动化执行不再空跑**：Agent 在自动化执行时可以使用所需工具完成任务，不再被全量拒绝。
5. **执行结果用户可见**：自动化执行结果投递到用户聊天 session 或在管理页面运行历史中可见，用户不再对执行情况一无所知。
