# Harness HITL 批准模式实施计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 为 Harness Agent 增加会话创建前的批准模式选择，并在 AgentScope 权限结果为 `ASK` 时，把当前 Agent Run 持久化为可恢复的等待状态，在聊天时间线内完成“允许一次 / 拒绝”后继续同一个 Run。

**架构：** `ApprovalMode`、`ApprovalRequest` 与 `ApprovalDecision` 是项目拥有的领域模型；AgentScope 的 `PermissionMode`、`RequireUserConfirmEvent` 和 `ConfirmResult` 只存在于 `AgentScopeApprovalAdapter` 之后。批准模式保存于实际 `Agent Session`，每个 `Agent Run` 保存不可变快照；`ASK` 通过数据库状态机和终止型 `action_required` SSE 暂停 HTTP 流，用户决策通过新的 SSE 请求恢复执行，不持有长连接等待人类。

**技术栈：** Java 26、Spring Boot 4.0.6、Reactor Flux、AgentScope Java Harness 2.0.1、MyBatis-Plus、PostgreSQL/Flyway、Next.js 16、React 19、TypeScript、JUnit 5、Mockito、Node test。

**官方依据：** [AgentScope Java 2.0 Permission System](https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system.html)

---

## 1. 交付范围与不变量

### 1.1 本期交付

1. 创建 `harness-agent` 顶级聊天会话前选择批准模式。
2. 支持并持久化 AgentScope 五种标准模式：
   - `DEFAULT`：前端显示“默认审批（未命中规则时询问）”，作为新前端创建器的默认选中值。
   - `ACCEPT_EDITS`：前端显示“自动接受编辑”。
   - `EXPLORE`：前端显示“只读探索”。
   - `BYPASS`：前端显示“自动执行”。
   - `DONT_ASK`：后端支持但不在普通聊天创建器展示。
3. `ASK` 时创建一个持久化 `Approval Request`，聊天中展示安全摘要。
4. 用户可以“允许一次”或“拒绝”；两种决策都通过 `ConfirmResult` 恢复同一个 Agent Run，让 Agent 获得真实工具结果或拒绝结果后继续回答。
5. 刷新、重新登录或应用实例切换后，仍可查询并处理当前 Agent Session 的待审批请求。
6. 支持用户当前直接对话的 Harness 根 Session，以及用户进入协作 Agent 抽屉后直接发起的子 Session turn。
7. Agent Run 状态机支持一个 Run 内多次暂停和恢复：

```text
RUNNING -> WAITING_APPROVAL -> RUNNING -> ... -> SUCCEEDED | FAILED
```

### 1.2 明确不在本期交付

- 不开放“本会话始终允许”或规则编辑 UI；本期 `ConfirmResult.rules` 固定为空。
- 不允许浏览器修改工具参数，也不接收浏览器回传的 `ToolUseBlock` 或 `PermissionRule`。
- 不把完整工具输入复制到 `approval_requests`，也不通过 SSE 暴露原始参数。
- 不允许在有待审批请求时修改会话批准模式；本期会话创建后模式不可变。
- 不为普通聊天和非 Harness 领域 Agent 启用批准模式。
- 不把 `HarnessSubagentStatus` 扩展成等待审批状态；协作状态与 HITL 状态继续保持两个事实来源。
- 不在本计划中实现“父 Run 内部同步委托的子 Agent”中途审批。AgentScope 2.0.1 会把子事件转发给父流，但没有把暂停的子工具结果重新接回原父工具调用的产品级恢复接口；本期只对当前 HTTP 请求直接寻址的 Agent Session 建立可恢复审批。转发来源非空的 `REQUIRE_USER_CONFIRM` 继续作为 `harness_event` 观测，不生成可操作审批卡片。

### 1.3 核心不变量

1. `agent_sessions.approval_mode` 是会话配置真相；AgentScope `AgentState.permissionContext.mode` 是执行前同步的框架投影。
2. `agent_runs.approval_mode_snapshot` 在 Run 创建后不可变，即使将来开放会话模式修改也不能改写历史 Run。
3. 同一个实际 Agent Session 同时最多有一个 `RUNNING` 或 `WAITING_APPROVAL` Run；该不变量由 PostgreSQL partial unique index 最终保证，不能只依赖应用查询。
4. 一个 `Approval Request` 对应一次 `RequireUserConfirmEvent`，可以包含多个工具调用；“允许一次 / 拒绝”对该事件内所有工具调用统一生效。
5. `action_required` 是本次 HTTP SSE 的终止事件，但不是 Agent Run 的业务终态。
6. 只有 `PENDING` 请求可以被决策；决策使用 `version` 做 CAS，重复点击不得二次恢复 Agent。
7. 恢复前必须从 AgentState 重新读取 `ASKING` 状态的 `ToolUseBlock`，并与数据库保存的工具调用 ID 集合完全一致。
8. 前端只提交 `approvalId + decision + version`。用户身份、Run、Session、工具调用与恢复地址全部由后端解析。
9. `BYPASS` 仍服从 AgentScope 显式 DENY / ASK 规则与不可绕过的工具安全检查，前端文案不得写成“绝对无拦截”。
10. `DEFAULT` 权限上下文必须是非 trivial；仅调用 `PermissionContextState.builder().mode(DEFAULT).build()` 不满足该要求。
11. 旧客户端省略 `approvalMode` 时按 `BYPASS` 处理以保持升级兼容；新版 UI 必须显式发送模式，并默认选中 `DEFAULT`。
12. 首次暂停前已经流式展示但尚未形成最终消息的 reasoning 不做跨刷新持久化；刷新后以持久化审批卡片为恢复锚点，最终回答仍只在 Run 成功时落库。

## 2. 模块与 seam

```text
Chat Session 创建
    -> 项目 ApprovalMode
    -> agent_sessions.approval_mode

Chat Run 开始
    -> AgentScopeApprovalAdapter.applyMode(...)
    -> AgentScope PermissionEngine
       |- ALLOW -> 工具执行
       |- DENY  -> Agent 收到拒绝结果
       `- ASK   -> RequireUserConfirmEvent
                    -> ApprovalRequestService.suspend(...)
                    -> Agent Run = WAITING_APPROVAL
                    -> SSE action_required

用户决策
    -> ChatApprovalService.streamDecision(...)
    -> ApprovalRequestService.claimDecision(...)
    -> AgentScopeApprovalAdapter.resume(... ConfirmResult ...)
    -> 原 Agent Run 继续
```

### 2.1 `ApprovalRequestService` 深模块

它的 interface 只暴露三种能力：记录暂停、查询待审批、原子认领决策。它隐藏表结构、JSON 序列化、所有权校验、版本 CAS、Run 状态联动和幂等键。

```java
public interface ApprovalRequestService {
    ApprovalRequestDto suspend(SuspendApprovalCommand command);

    ApprovalRequestDto findPending(Long userId, String sessionId);

    ApprovalResolution claimDecision(
            Long userId,
            String approvalId,
            ApprovalDecision decision,
            int expectedVersion
    );

    record SuspendApprovalCommand(
            Long runId,
            Long userId,
            String rootSessionId,
            String sessionId,
            String subagentExecutionId,
            ApprovalMode approvalMode,
            ApprovalEpisode episode
    ) {}
}
```

### 2.2 `AgentScopeApprovalAdapter`

这是项目审批领域与 AgentScope SDK 的唯一 seam。它负责：

- 把项目 `ApprovalMode` 映射成 SDK `PermissionMode`。
- 在指定 `(userId, sessionId)` 上安装非 trivial `PermissionContextState`，保留已存在规则。
- 把 `RequireUserConfirmEvent` 转成不含 SDK 类型的 `ApprovalEpisode`。
- 从 AgentState 找回待确认工具并构建 `ConfirmResult` 恢复消息。
- 校验数据库工具调用 ID 与 AgentState 当前 `ASKING` 工具完全一致。

Controller、DTO、数据库 Entity 和前端代码禁止 import 或复制 AgentScope 类型名。

### 2.3 `ChatApprovalService`

它协调一次决策恢复：校验请求、获取 Redis Session permit、通过 `claimDecision` 原子认领请求并恢复 Run、恢复 Trace context、调用 `HarnessApprovalContinuation`，并在启动失败时释放 permit、把 Run 标成 `FAILED`。它不解释 SDK 事件。

```java
public interface ChatApprovalService {
    Flux<ChatStreamEvent> streamDecision(
            Long userId,
            String approvalId,
            ApprovalDecision decision,
            int expectedVersion
    );
}
```

### 2.4 `HarnessApprovalContinuation`

`HarnessAgentExecutor` 实现这个 interface，从而复用现有事件投影、消息持久化、Run 完成和协作子 Session 完成逻辑，不复制第二套 Harness 执行器。

```java
public interface HarnessApprovalContinuation {
    void resume(ApprovalResumeExecutionCommand command);
}
```

## 3. 文件结构

### 3.1 后端新增文件

- `backend/src/main/java/com/h/backend/chat/domain/approval/ApprovalMode.java`
- `backend/src/main/java/com/h/backend/chat/domain/approval/ApprovalDecision.java`
- `backend/src/main/java/com/h/backend/chat/domain/approval/ApprovalRequestStatus.java`
- `backend/src/main/java/com/h/backend/chat/domain/approval/ApprovalEpisode.java`
- `backend/src/main/java/com/h/backend/chat/domain/approval/ApprovalResolution.java`
- `backend/src/main/java/com/h/backend/chat/application/ApprovalRequestService.java`
- `backend/src/main/java/com/h/backend/chat/application/ChatApprovalService.java`
- `backend/src/main/java/com/h/backend/chat/application/HarnessApprovalContinuation.java`
- `backend/src/main/java/com/h/backend/chat/application/impl/ApprovalRequestServiceImpl.java`
- `backend/src/main/java/com/h/backend/chat/application/impl/ChatApprovalServiceImpl.java`
- `backend/src/main/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapter.java`
- `backend/src/main/java/com/h/backend/chat/domain/agent/ApprovalResumeExecutionCommand.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/ApprovalRequestEntity.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/ApprovalRequestMapper.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/ApprovalActionDto.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/ApprovalRequestDto.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/ApprovalDecisionRequest.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/web/ApprovalController.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/web/ChatSseAdapter.java`
- `backend/src/main/resources/db/migration/V20260828_01__create_harness_approval_flow.sql`
- `backend/src/test/java/com/h/backend/chat/domain/approval/ApprovalModeTest.java`
- `backend/src/test/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapterTest.java`
- `backend/src/test/java/com/h/backend/chat/ApprovalRequestServiceTest.java`
- `backend/src/test/java/com/h/backend/chat/ApprovalControllerTest.java`
- `backend/src/test/java/com/h/backend/chat/HarnessApprovalFlowIT.java`

### 3.2 后端修改文件

- `backend/src/main/java/com/h/backend/chat/interfaces/dto/CreateChatSessionRequest.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/ChatSessionMetaDto.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/ChatSessionSummaryDto.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/ChatStreamEvent.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/web/ChatSessionController.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/web/ChatController.java`
- `backend/src/main/java/com/h/backend/chat/application/ChatSessionService.java`
- `backend/src/main/java/com/h/backend/chat/application/AgentRunService.java`
- `backend/src/main/java/com/h/backend/chat/application/AgentRunTelemetryService.java`
- `backend/src/main/java/com/h/backend/chat/application/impl/ChatSessionServiceImpl.java`
- `backend/src/main/java/com/h/backend/chat/application/impl/HarnessCollaborationServiceImpl.java`
- `backend/src/main/java/com/h/backend/chat/application/impl/AgentRunServiceImpl.java`
- `backend/src/main/java/com/h/backend/chat/application/impl/AgentRunTelemetryServiceImpl.java`
- `backend/src/main/java/com/h/backend/chat/application/impl/ChatServiceImpl.java`
- `backend/src/main/java/com/h/backend/chat/domain/model/AgentRunSummary.java`
- `backend/src/main/java/com/h/backend/chat/domain/agent/ChatAgentExecutionCommand.java`
- `backend/src/main/java/com/h/backend/chat/domain/agent/HarnessRuntime.java`
- `backend/src/main/java/com/h/backend/chat/domain/agent/AgentScopeHarnessRuntime.java`
- `backend/src/main/java/com/h/backend/chat/domain/agent/HarnessAgentExecutor.java`
- `backend/src/main/java/com/h/backend/chat/application/HarnessExecutionSession.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/AgentSessionEntity.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/AgentRunEntity.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/AgentSessionMapper.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/AgentRunMapper.java`
- `backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`
- `backend/src/test/java/com/h/backend/chat/HarnessCollaborationSessionOpenIT.java`
- `backend/src/test/java/com/h/backend/chat/AgentRunServicePersistenceTest.java`
- `backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`
- `backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
- `backend/src/test/java/com/h/backend/chat/domain/agent/AgentScopeHarnessRuntimeTest.java`
- `backend/src/test/java/com/h/backend/chat/domain/agent/HarnessAgentExecutorTest.java`
- `backend/src/test/java/com/h/backend/chat/domain/agent/HarnessEventMapperTest.java`

### 3.3 前端新增文件

- `frontend/lib/approval-requests.ts`
- `frontend/lib/approval-ui.ts`
- `frontend/lib/approval-ui.test.mjs`
- `frontend/app/chat/approval-mode-picker.tsx`
- `frontend/app/chat/approval-card.tsx`

### 3.4 前端修改文件

- `frontend/lib/chat-sessions.ts`
- `frontend/lib/http.ts`
- `frontend/lib/http.test.mjs`
- `frontend/lib/chat-agent-mode.ts`
- `frontend/lib/chat-agent-mode.test.mjs`
- `frontend/app/chat/page.tsx`

## 4. 实施任务

### 任务 1：建立批准模式领域模型和数据库结构

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/domain/approval/ApprovalMode.java`
- 创建：`backend/src/main/java/com/h/backend/chat/domain/approval/ApprovalDecision.java`
- 创建：`backend/src/main/java/com/h/backend/chat/domain/approval/ApprovalRequestStatus.java`
- 创建：`backend/src/main/resources/db/migration/V20260828_01__create_harness_approval_flow.sql`
- 修改：`backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/AgentSessionEntity.java`
- 修改：`backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/AgentRunEntity.java`
- 修改：`backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/AgentSessionMapper.java`
- 修改：`backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/AgentRunMapper.java`
- 测试：`backend/src/test/java/com/h/backend/chat/domain/approval/ApprovalModeTest.java`

- [ ] **步骤 1：编写批准模式失败测试**

```java
@Test
void keepsMissingModeBackwardCompatibleForHarnessSessions() {
    assertEquals(ApprovalMode.BYPASS,
            ApprovalMode.resolveForNewSession(ChatAgentIds.HARNESS, null));
}

@Test
void rejectsApprovalModeForNonHarnessAgents() {
    assertThrows(IllegalArgumentException.class, () ->
            ApprovalMode.resolveForNewSession(ChatAgentIds.STANDARD_CHAT, ApprovalMode.BYPASS));
}

@Test
void leavesNonHarnessSessionsWithoutApprovalMode() {
    assertNull(ApprovalMode.resolveForNewSession(ChatAgentIds.STANDARD_CHAT, null));
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：

```bash
source ~/.profile
cd backend
mvn -Dtest=ApprovalModeTest test
```

预期：FAIL，`ApprovalMode` 尚不存在。

- [ ] **步骤 3：实现项目领域枚举**

```java
public enum ApprovalMode {
    DEFAULT,
    ACCEPT_EDITS,
    EXPLORE,
    BYPASS,
    DONT_ASK;

    public static ApprovalMode resolveForNewSession(String agentId, ApprovalMode requested) {
        if (!ChatAgentIds.HARNESS.equals(agentId)) {
            if (requested != null) {
                throw new IllegalArgumentException("批准模式只适用于 Harness Agent");
            }
            return null;
        }
        // 兼容尚未带 approvalMode 字段的旧前端；新前端始终显式发送所选模式。
        return requested == null ? BYPASS : requested;
    }
}
```

同时创建：

```java
public enum ApprovalDecision { APPROVE, DENY }

public enum ApprovalRequestStatus { PENDING, APPROVED, DENIED, CANCELLED }
```

- [ ] **步骤 4：新增 Flyway migration**

migration 必须包含以下结构和约束：

```sql
ALTER TABLE agent_sessions
    ADD COLUMN approval_mode VARCHAR(32);

WITH RECURSIVE harness_session_tree AS (
    SELECT session_id
    FROM agent_sessions
    WHERE agent_id = 'harness-agent'
    UNION ALL
    SELECT child.session_id
    FROM agent_sessions child
    JOIN harness_session_tree parent
      ON child.parent_session_id = parent.session_id
)
UPDATE agent_sessions
SET approval_mode = 'BYPASS'
WHERE session_id IN (SELECT session_id FROM harness_session_tree);

ALTER TABLE agent_sessions
    ADD CONSTRAINT ck_agent_sessions_approval_mode CHECK (
        approval_mode IS NULL OR approval_mode IN (
            'DEFAULT', 'ACCEPT_EDITS', 'EXPLORE', 'BYPASS', 'DONT_ASK'
        )
    );

ALTER TABLE agent_runs
    ADD COLUMN approval_mode_snapshot VARCHAR(32),
    ADD COLUMN trace_parent VARCHAR(128);

-- 部署前先用第 5 节的预检 SQL 确认不存在历史重复开放 Run。
CREATE UNIQUE INDEX uk_agent_runs_open_session
    ON agent_runs(session_id)
    WHERE status IN ('RUNNING', 'WAITING_APPROVAL');

CREATE TABLE approval_requests (
    approval_id VARCHAR(36) PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    root_session_id VARCHAR(255) NOT NULL REFERENCES agent_sessions(session_id) ON DELETE CASCADE,
    session_id VARCHAR(255) NOT NULL REFERENCES agent_sessions(session_id) ON DELETE CASCADE,
    request_key VARCHAR(255) NOT NULL,
    reply_id VARCHAR(255),
    subagent_execution_id VARCHAR(255),
    approval_mode VARCHAR(32) NOT NULL,
    tool_call_ids_json JSONB NOT NULL,
    tool_names_json JSONB NOT NULL,
    display_items_json JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    decision VARCHAR(16),
    version INTEGER NOT NULL DEFAULT 0,
    requested_at TIMESTAMP NOT NULL DEFAULT NOW(),
    decided_at TIMESTAMP,
    decided_by BIGINT REFERENCES users(id),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_approval_requests_run_request UNIQUE (run_id, request_key),
    CONSTRAINT ck_approval_requests_status CHECK (
        status IN ('PENDING', 'APPROVED', 'DENIED', 'CANCELLED')
    ),
    CONSTRAINT ck_approval_requests_decision CHECK (
        decision IS NULL OR decision IN ('APPROVE', 'DENY')
    ),
    CONSTRAINT ck_approval_requests_mode CHECK (
        approval_mode IN ('DEFAULT', 'ACCEPT_EDITS', 'EXPLORE', 'BYPASS', 'DONT_ASK')
    ),
    CONSTRAINT ck_approval_requests_version CHECK (
        version >= 0
    )
);

CREATE UNIQUE INDEX uk_approval_requests_pending_session
    ON approval_requests(session_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_approval_requests_user_session
    ON approval_requests(user_id, session_id, requested_at DESC);
```

既有 Harness 根 Session 及其全部历史后代递归回填 `BYPASS`，保持升级前实际自动执行行为。缺少 `approvalMode` 的旧客户端也兼容为 `BYPASS`；新前端创建器默认选中并显式发送 `DEFAULT`，因此用户经新版 UI 创建的 Harness Session 默认进入审批模式。

- [ ] **步骤 5：更新 Entity 和 Mapper 显式 SELECT 列**

`AgentSessionEntity` 新增 `ApprovalMode approvalMode`，`AgentRunEntity` 新增 `ApprovalMode approvalModeSnapshot` 与 `String traceParent`。所有 `AgentSessionMapper` / `AgentRunMapper` 自定义 SELECT 必须显式加入新列，避免 MyBatis 查询后静默得到空值。

- [ ] **步骤 6：运行领域和 Spring 持久化测试**

```bash
source ~/.profile
cd backend
mvn -Dtest=ApprovalModeTest,ChatSessionMapperPersistenceTest,AgentRunServicePersistenceTest test
```

预期：`BUILD SUCCESS`，Flyway 能迁移现有测试数据库。

- [ ] **步骤 7：提交本任务**

```bash
source ~/.profile
git add backend/src/main/java/com/h/backend/chat/domain/approval \
  backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/AgentSessionEntity.java \
  backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/AgentRunEntity.java \
  backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/AgentSessionMapper.java \
  backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/AgentRunMapper.java \
  backend/src/main/resources/db/migration/V20260828_01__create_harness_approval_flow.sql \
  backend/src/test/java/com/h/backend/chat/domain/approval/ApprovalModeTest.java
git commit -m "feat: add harness approval mode persistence"
```

### 任务 2：把批准模式接入 Session 创建、查询和子 Session 继承

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/interfaces/dto/CreateChatSessionRequest.java`
- 修改：`backend/src/main/java/com/h/backend/chat/interfaces/dto/ChatSessionMetaDto.java`
- 修改：`backend/src/main/java/com/h/backend/chat/interfaces/dto/ChatSessionSummaryDto.java`
- 修改：`backend/src/main/java/com/h/backend/chat/interfaces/web/ChatSessionController.java`
- 修改：`backend/src/main/java/com/h/backend/chat/application/ChatSessionService.java`
- 修改：`backend/src/main/java/com/h/backend/chat/application/impl/ChatSessionServiceImpl.java`
- 修改：`backend/src/main/java/com/h/backend/chat/application/impl/HarnessCollaborationServiceImpl.java`
- 修改：`backend/src/main/java/com/h/backend/chat/application/HarnessExecutionSession.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/HarnessCollaborationSessionOpenIT.java`

- [ ] **步骤 1：先写创建和继承测试**

覆盖以下断言：

```java
var opened = service.createSession(
        userId, null, ChatAgentIds.HARNESS, ApprovalMode.ACCEPT_EDITS, null);
assertEquals(ApprovalMode.ACCEPT_EDITS, opened.session().approvalMode());
assertEquals(ApprovalMode.ACCEPT_EDITS,
        agentSessionMapper.selectBySessionId(opened.session().sessionId()).getApprovalMode());
```

以及子 Session 继承：

```java
var child = agentSessionMapper.selectBySessionId("child-runtime-research");
assertEquals(ApprovalMode.ACCEPT_EDITS, child.getApprovalMode());
```

再增加非 Harness 显式传 mode 返回业务错误的 Controller 测试。

- [ ] **步骤 2：运行测试并确认失败**

```bash
source ~/.profile
cd backend
mvn -Dtest=ChatSessionServiceImplTest,HarnessCollaborationSessionOpenIT test
```

预期：FAIL，DTO 和 Session interface 尚无 `approvalMode`。

- [ ] **步骤 3：扩展创建请求和 Session interface**

```java
public record CreateChatSessionRequest(
        String currentSessionId,
        Long promptId,
        String agentId,
        ApprovalMode approvalMode
) {}
```

```java
ChatSessionOpenDto createSession(
        Long userId,
        Long promptId,
        String agentId,
        ApprovalMode approvalMode,
        String currentSessionId
);
```

`ChatSessionMetaDto` 和 `ChatSessionSummaryDto` 增加可空 `ApprovalMode approvalMode`。

- [ ] **步骤 4：在同一事务内保存根 Session 模式**

`ChatSessionServiceImpl.createSession` 先规范化 agentId，再调用：

```java
ApprovalMode resolvedApprovalMode =
        ApprovalMode.resolveForNewSession(resolvedAgentId, approvalMode);
```

`registerRootAgentSession` 必须把 `resolvedApprovalMode` 写入同一条 `agent_sessions` 记录；`toMeta` / `toSummary` 从 `agent_sessions` 回读，而不是从请求临时拼装。

对 Harness 请求，`approvalMode=null` 仅作为旧客户端兼容路径解析为 `BYPASS`；新版前端必须显式发送。对非 Harness 请求，显式 mode 仍转换为业务错误。

- [ ] **步骤 5：子 Session 原子继承直接父 Session 的模式**

在 `HarnessCollaborationServiceImpl.exposeSubagent` 创建 `AgentSessionEntity` 时加入：

```java
session.setApprovalMode(parent.getApprovalMode());
```

已有 Session 被 exposure 提升时不覆盖其已保存模式。`HarnessExecutionSession` 增加 `ApprovalMode approvalMode`，由实际请求 Session 返回，供每个直接 turn 创建 Run 快照。

- [ ] **步骤 6：运行测试**

```bash
source ~/.profile
cd backend
mvn -Dtest=ChatSessionServiceImplTest,HarnessCollaborationSessionOpenIT test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 7：提交本任务**

```bash
source ~/.profile
git add backend/src/main/java/com/h/backend/chat/interfaces/dto \
  backend/src/main/java/com/h/backend/chat/interfaces/web/ChatSessionController.java \
  backend/src/main/java/com/h/backend/chat/application/ChatSessionService.java \
  backend/src/main/java/com/h/backend/chat/application/HarnessExecutionSession.java \
  backend/src/main/java/com/h/backend/chat/application/impl/ChatSessionServiceImpl.java \
  backend/src/main/java/com/h/backend/chat/application/impl/HarnessCollaborationServiceImpl.java \
  backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java \
  backend/src/test/java/com/h/backend/chat/HarnessCollaborationSessionOpenIT.java
git commit -m "feat: bind approval mode to agent sessions"
```

### 任务 3：建立 AgentScope 权限 Adapter，保证 DEFAULT 真正进入 PermissionEngine

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapter.java`
- 创建：`backend/src/main/java/com/h/backend/chat/domain/approval/ApprovalEpisode.java`
- 修改：`backend/src/main/java/com/h/backend/chat/domain/agent/HarnessRuntime.java`
- 修改：`backend/src/main/java/com/h/backend/chat/domain/agent/AgentScopeHarnessRuntime.java`
- 修改：`backend/src/main/java/com/h/backend/chat/domain/agent/ChatAgentExecutionCommand.java`
- 测试：`backend/src/test/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapterTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/domain/agent/AgentScopeHarnessRuntimeTest.java`

- [ ] **步骤 1：编写模式映射和非 trivial 上下文失败测试**

```java
@Test
void installsNonTrivialDefaultContextWithoutDroppingExistingRules() {
    ReActAgent agent = mockAgentWithState(PermissionContextState.builder()
            .mode(PermissionMode.DEFAULT)
            .addDenyRule("dangerous", new PermissionRule(
                    "dangerous", null, PermissionBehavior.DENY, "platform"))
            .build());

    adapter.applyMode(agent, "42", "session-1", ApprovalMode.DEFAULT);

    PermissionContextState installed = capturedPermissionContext();
    assertFalse(installed.isTrivial());
    assertEquals(PermissionMode.DEFAULT, installed.getMode());
    assertEquals(1, installed.getDenyRules().get("dangerous").size());
    assertFalse(installed.getWorkingDirectories().isEmpty());
}
```

五个模式都要断言一一映射；不存在隐式 fallback。

- [ ] **步骤 2：运行测试并确认失败**

```bash
source ~/.profile
cd backend
mvn -Dtest=AgentScopeApprovalAdapterTest test
```

预期：FAIL，Adapter 尚不存在。

- [ ] **步骤 3：实现 Adapter 的 mode 安装**

先定义不泄漏 SDK 类型的审批快照；`requestKey` 使用 `replyId + 排序后的 toolCallId` 生成稳定摘要，同一次框架事件重放时必须得到相同值：

```java
public record ApprovalEpisode(
        String requestKey,
        String replyId,
        List<ToolCall> actions
) {
    public record ToolCall(
            String toolCallId,
            String toolName,
            String title,
            String summary,
            String target,
            String riskLevel
    ) {}
}
```

关键实现要求：

```java
public void applyMode(
        ReActAgent agent,
        String userId,
        String sessionId,
        ApprovalMode mode
) {
    PermissionContextState current =
            agent.getAgentState(userId, sessionId).getPermissionContext();
    PermissionContextState replacement = copyWithModeAndWorkspace(current, toSdk(mode));
    agent.replacePermissionContext(userId, sessionId, replacement);
}
```

`copyWithModeAndWorkspace` 必须复制 allow / deny / ask rules，并增加规范化的 Harness workspace：

```java
builder.addWorkingDirectory(
        workspaceRoot.toString(),
        new AdditionalWorkingDirectory(workspaceRoot.toString(), "projectSettings")
);
```

不能只调用 `setPermissionMode(DEFAULT)`，因为空 DEFAULT 上下文仍是 `isTrivial() == true`。
如果当前上下文已有其他 working directories，也必须一并复制。不要在全局 `HarnessAgentConfig` builder 上安装某个会话的模式；会话级 Adapter 才是唯一写入口。

- [ ] **步骤 4：让每个直接 turn 在调用前同步模式**

`HarnessRuntime` 调整为：

```java
Flux<AgentEvent> streamParent(
        Object agentBean,
        String message,
        RuntimeContext context,
        ApprovalMode approvalMode
);

Flux<AgentEvent> streamSubagent(
        Object agentBean,
        HarnessSubagentContext context,
        String message,
        ApprovalMode approvalMode
);
```

`AgentScopeHarnessRuntime` 必须在 `streamEvents(...)` 之前对确切的 `(userId, sessionId)` 调用 Adapter。`ChatAgentExecutionCommand` 增加非空 `ApprovalMode approvalMode`，仅 Harness executor 使用。

- [ ] **步骤 5：添加运行时调用顺序测试**

用 Mockito `InOrder` 断言：

```java
inOrder.verify(adapter).applyMode(child, "42", "child-session", ApprovalMode.EXPLORE);
inOrder.verify(child).streamEvents(anyList(), any(RuntimeContext.class));
```

- [ ] **步骤 6：运行测试**

```bash
source ~/.profile
cd backend
mvn -Dtest=AgentScopeApprovalAdapterTest,AgentScopeHarnessRuntimeTest test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 7：提交本任务**

```bash
source ~/.profile
git add backend/src/main/java/com/h/backend/chat/domain/approval/ApprovalEpisode.java \
  backend/src/main/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapter.java \
  backend/src/main/java/com/h/backend/chat/domain/agent/HarnessRuntime.java \
  backend/src/main/java/com/h/backend/chat/domain/agent/AgentScopeHarnessRuntime.java \
  backend/src/main/java/com/h/backend/chat/domain/agent/ChatAgentExecutionCommand.java \
  backend/src/test/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapterTest.java \
  backend/src/test/java/com/h/backend/chat/domain/agent/AgentScopeHarnessRuntimeTest.java
git commit -m "feat: adapt session approval modes to agentscope"
```

### 任务 4：建立安全审批展示和持久化状态机

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/interfaces/dto/ApprovalActionDto.java`
- 创建：`backend/src/main/java/com/h/backend/chat/interfaces/dto/ApprovalRequestDto.java`
- 创建：`backend/src/main/java/com/h/backend/chat/domain/approval/ApprovalResolution.java`
- 创建：`backend/src/main/java/com/h/backend/chat/application/ApprovalRequestService.java`
- 创建：`backend/src/main/java/com/h/backend/chat/application/impl/ApprovalRequestServiceImpl.java`
- 创建：`backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/ApprovalRequestEntity.java`
- 创建：`backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/ApprovalRequestMapper.java`
- 修改：`backend/src/main/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapter.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ApprovalRequestServiceTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapterTest.java`

- [ ] **步骤 1：编写安全摘要测试**

构造包含密钥和安全字段的 ToolUseBlock：

```java
ToolUseBlock call = new ToolUseBlock("tool-1", "write_file", Map.of(
        "path", "backend/src/App.java",
        "content", "API_KEY=secret-value"
));

ApprovalEpisode episode = adapter.capture(
        new RequireUserConfirmEvent("reply-1", List.of(call)));

assertEquals("backend/src/App.java", episode.actions().getFirst().target());
assertFalse(episode.actions().getFirst().summary().contains("secret-value"));
```

通用未知工具只显示工具名称和“详细参数已隐藏”，不能回退为 `input.toString()`。

- [ ] **步骤 2：编写持久化 CAS 测试**

测试必须覆盖：

- 相同 `(runId, requestKey)` 重复 suspend 返回同一审批请求，且不会重复转换 Run。
- 同一 Session 第二个 PENDING 请求被数据库唯一索引拒绝并转成明确业务错误。
- 正确 version 第一次决策成功。
- 重复决策或错误 version 返回“审批已处理或页面状态已过期”。
- 用户不能查询或决策其他用户的审批。

- [ ] **步骤 3：运行测试并确认失败**

```bash
source ~/.profile
cd backend
mvn -Dtest=AgentScopeApprovalAdapterTest,ApprovalRequestServiceTest test
```

预期：FAIL，审批 DTO、Entity、Mapper 和状态机尚不存在。

- [ ] **步骤 4：定义浏览器安全 DTO**

```java
public record ApprovalActionDto(
        String toolCallId,
        String toolName,
        String title,
        String summary,
        String target,
        String riskLevel
) {}

public record ApprovalRequestDto(
        String approvalId,
        String runId,
        String sessionId,
        ApprovalMode approvalMode,
        List<ApprovalActionDto> actions,
        ApprovalRequestStatus status,
        int version,
        LocalDateTime requestedAt
) {}
```

`runId` 对外使用字符串，避免 JavaScript `number` 精度风险。

- [ ] **步骤 5：实现安全展示策略**

Adapter 内部按工具名使用有限字段白名单：

```text
write_file/edit_file/read_file -> path
send_file_to_chat             -> path/file_name
agent_spawn                   -> agent_id/label
未知工具                       -> 不展示 input
```

禁止展示 `content`、token、authorization、cookie、password、secret、完整环境变量和任意 provider metadata。

- [ ] **步骤 6：实现 ApprovalRequestService 状态机**

Mapper 的决策必须是单条 CAS 更新：

```sql
UPDATE approval_requests
SET status = #{status},
    decision = #{decision},
    decided_at = NOW(),
    decided_by = #{userId},
    version = version + 1,
    updated_at = NOW()
WHERE approval_id = #{approvalId}
  AND user_id = #{userId}
  AND status = 'PENDING'
  AND version = #{expectedVersion}
```

数据库只保存 `toolCallId`、工具名和安全展示 JSON。完整 ToolUseBlock 继续由 AgentScope `AgentState` 持有。

`suspend(...)` 必须在同一数据库事务内完成“插入/回读幂等 Approval Request”和 `agent_runs: RUNNING -> WAITING_APPROVAL`；任一步失败都回滚，禁止出现 PENDING request 配 RUNNING run。

`claimDecision(...)` 必须在同一数据库事务内完成 approval CAS 和 `agent_runs: WAITING_APPROVAL -> RUNNING`；任一步失败都回滚，禁止出现已决策 request 配 WAITING run。

`ApprovalResolution` 是事务成功后交给恢复层的不可变快照，字段固定为：

```java
public record ApprovalResolution(
        String approvalId,
        Long runId,
        Long userId,
        String rootSessionId,
        String sessionId,
        String subagentExecutionId,
        ApprovalMode approvalMode,
        ApprovalDecision decision,
        List<String> toolCallIds,
        int version,
        String traceParent
) {}
```

`suspend` 的幂等重放只有在现存请求的 Run、Session、mode 和工具 ID 全部一致时才能返回原请求；任何不一致都作为状态损坏失败，不能覆盖原记录。

- [ ] **步骤 7：运行测试**

```bash
source ~/.profile
cd backend
mvn -Dtest=AgentScopeApprovalAdapterTest,ApprovalRequestServiceTest test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 8：提交本任务**

```bash
source ~/.profile
git add backend/src/main/java/com/h/backend/chat/domain/approval \
  backend/src/main/java/com/h/backend/chat/application/ApprovalRequestService.java \
  backend/src/main/java/com/h/backend/chat/application/impl/ApprovalRequestServiceImpl.java \
  backend/src/main/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapter.java \
  backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/ApprovalRequestEntity.java \
  backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/ApprovalRequestMapper.java \
  backend/src/main/java/com/h/backend/chat/interfaces/dto/ApprovalActionDto.java \
  backend/src/main/java/com/h/backend/chat/interfaces/dto/ApprovalRequestDto.java \
  backend/src/test/java/com/h/backend/chat/ApprovalRequestServiceTest.java \
  backend/src/test/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapterTest.java
git commit -m "feat: persist secure harness approval requests"
```

### 任务 5：扩展 Agent Run 为可暂停、可恢复状态机

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/application/AgentRunService.java`
- 修改：`backend/src/main/java/com/h/backend/chat/application/impl/AgentRunServiceImpl.java`
- 修改：`backend/src/main/java/com/h/backend/chat/domain/model/AgentRunSummary.java`
- 修改：`backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/AgentRunMapper.java`
- 修改：`backend/src/main/java/com/h/backend/chat/application/impl/ChatServiceImpl.java`
- 测试：`backend/src/test/java/com/h/backend/chat/AgentRunServicePersistenceTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ApprovalRequestServiceTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`

- [ ] **步骤 1：编写状态转换失败测试**

```java
ApprovalRequestDto pending = approvalRequestService.suspend(suspendCommandForRun(88L));
assertEquals("WAITING_APPROVAL", agentRunService.getById(88L).status());

approvalRequestService.claimDecision(
        userId, pending.approvalId(), ApprovalDecision.APPROVE, pending.version());
assertEquals("RUNNING", agentRunService.getById(88L).status());
```

增加非法转换：`SUCCEEDED -> WAITING_APPROVAL`、重复 `WAITING_APPROVAL -> WAITING_APPROVAL`、错误用户恢复都必须失败。

- [ ] **步骤 2：运行测试并确认失败**

```bash
source ~/.profile
cd backend
mvn -Dtest=AgentRunServicePersistenceTest,ApprovalRequestServiceTest,ChatServiceImplTest test
```

预期：FAIL，Run 尚不认识等待状态，开放 Run 约束也尚未实现。

- [ ] **步骤 3：使用 Mapper CAS 实现状态转换**

```java
boolean hasOpenRun(String sessionId);
```

`AgentRunMapper` 提供给 `ApprovalRequestServiceImpl` 使用的内部 CAS；不要把暂停/恢复暴露成可被 Controller 分步调用的公共 application API：

```sql
UPDATE agent_runs SET status = 'WAITING_APPROVAL', updated_at = NOW()
WHERE id = #{runId} AND status = 'RUNNING'
```

```sql
UPDATE agent_runs SET status = 'RUNNING', updated_at = NOW()
WHERE id = #{runId} AND status = 'WAITING_APPROVAL'
```

`completeRun` / `failRun` 也改成只允许从 `RUNNING` 转换。`completed_at` 只在 `SUCCEEDED` / `FAILED` 时写入。

上述两条 CAS 必须分别与 `ApprovalRequestService.suspend` / `claimDecision` 的审批记录写入处在同一个 Spring transaction 中；更新行数不是 1 就抛业务冲突并回滚。

`createRun` 必须依赖 `uk_agent_runs_open_session` 处理跨实例竞争；遇到唯一索引冲突时转换成“当前会话仍有运行或待审批操作”，不得把数据库异常直接返回浏览器。

- [ ] **步骤 4：创建 Run 时保存模式快照**

`AgentRunService.createRun(...)` 增加可空 `ApprovalMode approvalModeSnapshot`；Harness Run 必须非空，普通聊天 Run 保持空值。

`ChatServiceImpl` 必须从解析完成的 `HarnessExecutionSession.approvalMode()` 同时填入 `createRun` 快照和 `ChatAgentExecutionCommand.approvalMode`，不能再次读取请求 DTO，也不能在 executor 内回退默认值。

- [ ] **步骤 5：阻止待审批 Session 开启新 turn**

`ChatServiceImpl` 在 append 用户消息之前检查实际 `address.sessionId()` 是否存在 `RUNNING` 或 `WAITING_APPROVAL` Run。Redis permit 仍负责短期并发，数据库状态负责跨实例、跨刷新等待期。

返回明确业务错误：

```text
当前会话有待审批操作，请先允许或拒绝后再继续聊天
```

- [ ] **步骤 6：运行测试**

```bash
source ~/.profile
cd backend
mvn -Dtest=AgentRunServicePersistenceTest,ApprovalRequestServiceTest,ChatServiceImplTest test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 7：提交本任务**

```bash
source ~/.profile
git add backend/src/main/java/com/h/backend/chat/application/AgentRunService.java \
  backend/src/main/java/com/h/backend/chat/application/impl/AgentRunServiceImpl.java \
  backend/src/main/java/com/h/backend/chat/application/impl/ChatServiceImpl.java \
  backend/src/main/java/com/h/backend/chat/domain/model/AgentRunSummary.java \
  backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/AgentRunMapper.java \
  backend/src/test/java/com/h/backend/chat/AgentRunServicePersistenceTest.java \
  backend/src/test/java/com/h/backend/chat/ApprovalRequestServiceTest.java \
  backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java
git commit -m "feat: suspend agent runs for human approval"
```

### 任务 6：让 Harness executor 在 AgentState 保存后发出可恢复暂停

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/domain/agent/HarnessAgentExecutor.java`
- 修改：`backend/src/main/java/com/h/backend/chat/interfaces/dto/ChatStreamEvent.java`
- 测试：`backend/src/test/java/com/h/backend/chat/domain/agent/HarnessAgentExecutorTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/domain/agent/HarnessEventMapperTest.java`

- [ ] **步骤 1：编写 ASK 不得失败的测试**

模拟事件顺序：

```text
REQUIRE_USER_CONFIRM
AGENT_RESULT(generateReason = PERMISSION_ASKING)
AGENT_END
```

断言：

```java
verify(agentRunService, never()).completeRun(anyLong(), anyLong());
verify(agentRunService, never()).failRun(anyLong(), anyString());
verify(approvalRequestService).suspend(any(SuspendApprovalCommand.class));
assertEquals("action_required", terminalEvent.type());
assertEquals(approvalRequest, terminalEvent.payload());
verify(command.onTerminal()).run();
```

同时断言没有插入空 assistant message，不发送 `done` / `error`。

- [ ] **步骤 2：运行测试并确认失败**

```bash
source ~/.profile
cd backend
mvn -Dtest=HarnessAgentExecutorTest,HarnessEventMapperTest test
```

预期：FAIL，现有 `completeSuccessfulResponse()` 会把空结果当成错误。

- [ ] **步骤 3：缓存审批 episode，不在 Require 事件处立即断流**

`Execution` 增加：

```java
private final AtomicReference<ApprovalEpisode> pendingApproval = new AtomicReference<>();
```

只有 `isParent(event)` 的 `RequireUserConfirmEvent` 可以写入该引用；`source` 非空的内部委托事件仍只经过 `HarnessEventMapper`。普通 `harness_event` 继续先发，保持观测完整性。

- [ ] **步骤 4：在父 AgentEnd/onComplete 选择暂停或成功**

```java
private void finishResponse() {
    ApprovalEpisode episode = pendingApproval.get();
    if (episode != null) {
        completeApprovalSuspension(episode);
        return;
    }
    completeSuccessfulResponse();
}
```

`completeApprovalSuspension` 的严格顺序：

1. 用 `responseTerminal.compareAndSet(false, true)` 独占本段终止权，避免 cancel / AgentEnd / onComplete 重入。
2. `ApprovalRequestService.suspend(...)` 在同一事务内落库并把 Run 改为 `WAITING_APPROVAL`。
3. 结束本段 Trace，标记 `WAITING_APPROVAL`。
4. `releaseExecution()` 释放 Session permit。
5. 发送 `ChatStreamEvent("action_required", "", null, approvalDto)`。
6. `completeSink()`。

步骤 6 发生在 AgentEnd 之后，确保 AgentScope 的持久化 call 生命周期已经完成；测试用 `InOrder` 固定此顺序。若步骤 2 失败，则收口 Run 为 `FAILED` 并发送 `error`，不能发送没有持久化身份的审批卡片。

- [ ] **步骤 5：保持通用 Harness 事件脱敏**

`HarnessEventMapper` 对 `REQUIRE_USER_CONFIRM` 仍不得输出 `data.toolCalls[*].input`。审批卡片的数据只能来自 `ApprovalRequestDto` 安全摘要，不能复用通用事件原始序列化结果。

- [ ] **步骤 6：运行测试**

```bash
source ~/.profile
cd backend
mvn -Dtest=HarnessAgentExecutorTest,HarnessEventMapperTest test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 7：提交本任务**

```bash
source ~/.profile
git add backend/src/main/java/com/h/backend/chat/domain/agent/HarnessAgentExecutor.java \
  backend/src/main/java/com/h/backend/chat/interfaces/dto/ChatStreamEvent.java \
  backend/src/test/java/com/h/backend/chat/domain/agent/HarnessAgentExecutorTest.java \
  backend/src/test/java/com/h/backend/chat/domain/agent/HarnessEventMapperTest.java
git commit -m "feat: emit durable harness approval pauses"
```

### 任务 7：实现 ConfirmResult 恢复和原 Run 续执行

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/domain/agent/ApprovalResumeExecutionCommand.java`
- 创建：`backend/src/main/java/com/h/backend/chat/application/HarnessApprovalContinuation.java`
- 修改：`backend/src/main/java/com/h/backend/chat/domain/agent/HarnessRuntime.java`
- 修改：`backend/src/main/java/com/h/backend/chat/domain/agent/AgentScopeHarnessRuntime.java`
- 修改：`backend/src/main/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapter.java`
- 修改：`backend/src/main/java/com/h/backend/chat/domain/agent/HarnessAgentExecutor.java`
- 测试：`backend/src/test/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapterTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/domain/agent/AgentScopeHarnessRuntimeTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/domain/agent/HarnessAgentExecutorTest.java`

- [ ] **步骤 1：编写恢复消息测试**

AgentState 中放入两个 `ToolUseBlock(state=ASKING)`，数据库 Resolution 保存相同 ID。批准时断言：

```java
assertTrue(results.stream().allMatch(ConfirmResult::isConfirmed));
assertTrue(results.stream().allMatch(result -> result.getRules() == null));
assertEquals(Set.of("tool-1", "tool-2"), resultToolCallIds(results));
```

拒绝时全部 `confirmed=false`。数据库 ID 与 AgentState ASKING ID 不一致时抛出稳定业务错误“审批状态与 AgentState 不一致”，不得部分恢复；该错误会让 Run 收口为 `FAILED` 并要求用户重试新 turn。

- [ ] **步骤 2：运行测试并确认失败**

```bash
source ~/.profile
cd backend
mvn -Dtest=AgentScopeApprovalAdapterTest,AgentScopeHarnessRuntimeTest,HarnessAgentExecutorTest test
```

预期：FAIL，Runtime 尚无 resume interface。

- [ ] **步骤 3：实现恢复消息**

```java
UserMessage.builder()
        .name("user")
        .metadata(Map.of(
                Msg.METADATA_CONFIRM_RESULTS,
                confirmResults
        ))
        .build();
```

恢复消息不带用户文本，不写入产品聊天记录。Adapter 从确切 AgentState 找 ASKING blocks，禁止从数据库 JSON 反序列化完整工具调用。

- [ ] **步骤 4：扩展 HarnessRuntime**

```java
Flux<AgentEvent> resumeParent(
        Object agentBean,
        ApprovalResolution resolution,
        RuntimeContext context
);

Flux<AgentEvent> resumeSubagent(
        Object agentBean,
        HarnessSubagentContext context,
        ApprovalResolution resolution
);
```

父、子恢复都必须先重新应用 `resolution.approvalMode()`，再读取 pending state 和调用 `streamEvents`。

- [ ] **步骤 5：让 HarnessAgentExecutor 复用同一 Execution**

`HarnessAgentExecutor implements ChatAgentExecutor, HarnessApprovalContinuation`。`execute(...)` 和 `resume(...)` 都进入一个私有 `startExecution(...)`；差异只在 Runtime 初始输入，后续事件处理、审批再次暂停、最终消息落库和 Run 完成共用同一个 `Execution` 实现。

恢复 command 不携带浏览器数据，只包含服务端重建的执行地址和原 Run 身份：

```java
public record ApprovalResumeExecutionCommand(
        FluxSink<ChatStreamEvent> sink,
        ApprovalResolution resolution,
        HarnessExecutionSession executionSession,
        Long resolvedPromptId,
        String memoryId,
        AgentDefinition agent,
        AgentRunService.AgentRunHandle runHandle,
        AgentRunTelemetryService.TelemetryRun telemetryRun,
        Runnable onTerminal
) {}
```

`ChatApprovalService` 从持久化 Run、Session 与 agent definition 重新构造这些字段；禁止缓存初次 HTTP 请求对象作为恢复依据。

- [ ] **步骤 6：覆盖一个 Run 二次 ASK**

测试恢复后再次收到 ASK：Run 再次进入 `WAITING_APPROVAL`，创建新的 requestKey，不插入 assistant message；第二次恢复成功后才 `SUCCEEDED`。

- [ ] **步骤 7：运行测试**

```bash
source ~/.profile
cd backend
mvn -Dtest=AgentScopeApprovalAdapterTest,AgentScopeHarnessRuntimeTest,HarnessAgentExecutorTest test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 8：提交本任务**

```bash
source ~/.profile
git add backend/src/main/java/com/h/backend/chat/application/HarnessApprovalContinuation.java \
  backend/src/main/java/com/h/backend/chat/domain/agent/ApprovalResumeExecutionCommand.java \
  backend/src/main/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapter.java \
  backend/src/main/java/com/h/backend/chat/domain/agent/HarnessRuntime.java \
  backend/src/main/java/com/h/backend/chat/domain/agent/AgentScopeHarnessRuntime.java \
  backend/src/main/java/com/h/backend/chat/domain/agent/HarnessAgentExecutor.java \
  backend/src/test/java/com/h/backend/chat/domain/agent/AgentScopeApprovalAdapterTest.java \
  backend/src/test/java/com/h/backend/chat/domain/agent/AgentScopeHarnessRuntimeTest.java \
  backend/src/test/java/com/h/backend/chat/domain/agent/HarnessAgentExecutorTest.java
git commit -m "feat: resume harness runs from approval decisions"
```

### 任务 8：增加决策 Controller、SSE 终止语义和刷新查询

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/interfaces/dto/ApprovalDecisionRequest.java`
- 创建：`backend/src/main/java/com/h/backend/chat/application/ChatApprovalService.java`
- 创建：`backend/src/main/java/com/h/backend/chat/application/impl/ChatApprovalServiceImpl.java`
- 创建：`backend/src/main/java/com/h/backend/chat/interfaces/web/ApprovalController.java`
- 创建：`backend/src/main/java/com/h/backend/chat/interfaces/web/ChatSseAdapter.java`
- 修改：`backend/src/main/java/com/h/backend/chat/interfaces/web/ChatController.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ApprovalControllerTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/ChatControllerTest.java`

- [ ] **步骤 1：编写 Controller 失败测试**

覆盖：

```http
GET /api/chat/approvals/pending?sessionId={actualSessionId}
POST /api/chat/approvals/{approvalId}/decision/stream
```

请求体：

```json
{
  "decision": "APPROVE",
  "version": 0
}
```

断言其他用户访问返回 404；错误 version 返回 409 语义业务错误；`action_required`、`done`、`blocked`、`error` 都结束 SSE。

- [ ] **步骤 2：运行测试并确认失败**

```bash
source ~/.profile
cd backend
mvn -Dtest=ApprovalControllerTest,ChatControllerTest test
```

预期：FAIL，Controller 和共享 SSE Adapter 尚不存在。

- [ ] **步骤 3：实现决策 DTO 与 ChatApprovalService**

```java
public record ApprovalDecisionRequest(
        @NotNull ApprovalDecision decision,
        @NotNull @PositiveOrZero Integer version
) {}
```

`ChatApprovalService.streamDecision(...)` 顺序：

1. 只读校验请求归属与 PENDING 状态。
2. 获取实际 Session 的 Redis permit。
3. 调用 `claimDecision(...)`，在同一事务内 CAS 认领审批决策并把 Run 从 `WAITING_APPROVAL` 改为 `RUNNING`。
4. 构造 `ApprovalResumeExecutionCommand` 并交给 continuation。
5. executor 负责正常释放 permit；步骤 3 后启动失败则补偿为 `FAILED` 并释放。

- [ ] **步骤 4：提取共享 SSE Adapter**

`ChatSseAdapter` 统一封装初始 heartbeat、周期 heartbeat 和终止事件集合：

```java
private static final Set<String> TERMINAL_EVENTS = Set.of(
        "done", "error", "blocked", "action_required"
);
```

`ChatController` 和 `ApprovalController` 都调用该 Adapter，避免两个 endpoint 演化出不同的流终止规则。

- [ ] **步骤 5：运行测试**

```bash
source ~/.profile
cd backend
mvn -Dtest=ApprovalControllerTest,ChatControllerTest test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 6：提交本任务**

```bash
source ~/.profile
git add backend/src/main/java/com/h/backend/chat/interfaces/dto/ApprovalDecisionRequest.java \
  backend/src/main/java/com/h/backend/chat/application/ChatApprovalService.java \
  backend/src/main/java/com/h/backend/chat/application/impl/ChatApprovalServiceImpl.java \
  backend/src/main/java/com/h/backend/chat/interfaces/web/ApprovalController.java \
  backend/src/main/java/com/h/backend/chat/interfaces/web/ChatController.java \
  backend/src/main/java/com/h/backend/chat/interfaces/web/ChatSseAdapter.java \
  backend/src/test/java/com/h/backend/chat/ApprovalControllerTest.java \
  backend/src/test/java/com/h/backend/chat/ChatControllerTest.java
git commit -m "feat: expose harness approval decision streams"
```

### 任务 9：让 Trace 跨人工等待恢复但不保持长时间开放 Span

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/application/AgentRunTelemetryService.java`
- 修改：`backend/src/main/java/com/h/backend/chat/application/impl/AgentRunTelemetryServiceImpl.java`
- 修改：`backend/src/main/java/com/h/backend/chat/application/impl/AgentRunServiceImpl.java`
- 测试：`backend/src/test/java/com/h/backend/chat/AgentRunTelemetryServiceImplTest.java`

- [ ] **步骤 1：编写 pause/resume Trace 测试**

断言 `pauseForApproval` 在结束当前 Span 前生成并返回 W3C `traceparent`；`resumeFromApproval` 使用该远程 parent 创建新 Span，并保留同一 trace ID。

- [ ] **步骤 2：运行测试并确认失败**

```bash
source ~/.profile
cd backend
mvn -Dtest=AgentRunTelemetryServiceImplTest test
```

预期：FAIL，Telemetry interface 尚无暂停和恢复。

- [ ] **步骤 3：扩展 Telemetry interface**

```java
String pauseForApproval(TelemetryRun telemetryRun, String approvalId);

TelemetryRun resumeFromApproval(
        String traceParent,
        Long runId,
        String approvalId,
        String sessionId,
        Long userId
);
```

暂停 Span 写入：

```text
agent.run.state = WAITING_APPROVAL
approval.request.id = <id>
```

恢复 Span 名称使用 `chat.agent.run.resume`，parent 从 `trace_parent` 恢复。等待期间不保留开放 Span；Agent Run 仍是唯一业务身份。

- [ ] **步骤 4：把 traceparent 写入 agent_runs**

暂停时更新 `agent_runs.trace_parent`；每次再次暂停用最新 SpanContext 覆盖。最终成功或失败后保留该字段用于审计，不用于再次恢复。

- [ ] **步骤 5：运行测试**

```bash
source ~/.profile
cd backend
mvn -Dtest=AgentRunTelemetryServiceImplTest,AgentRunServicePersistenceTest test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 6：提交本任务**

```bash
source ~/.profile
git add backend/src/main/java/com/h/backend/chat/application/AgentRunTelemetryService.java \
  backend/src/main/java/com/h/backend/chat/application/impl/AgentRunTelemetryServiceImpl.java \
  backend/src/main/java/com/h/backend/chat/application/impl/AgentRunServiceImpl.java \
  backend/src/test/java/com/h/backend/chat/AgentRunTelemetryServiceImplTest.java
git commit -m "feat: continue agent traces across hitl waits"
```

### 任务 10：增加前端批准模式类型、创建器和会话标识

**文件：**
- 创建：`frontend/lib/approval-requests.ts`
- 创建：`frontend/lib/approval-ui.ts`
- 创建：`frontend/lib/approval-ui.test.mjs`
- 创建：`frontend/app/chat/approval-mode-picker.tsx`
- 修改：`frontend/lib/chat-sessions.ts`
- 修改：`frontend/lib/chat-agent-mode.ts`
- 修改：`frontend/lib/chat-agent-mode.test.mjs`
- 修改：`frontend/app/chat/page.tsx`

- [ ] **步骤 1：阅读当前 Next.js 16 项目指南**

实现前按 `frontend/AGENTS.md` 读取仓库安装版本的相关文档：

```bash
source ~/.profile
cd frontend
rg -n "use client|Client Components|Forms" node_modules/next/dist/docs
```

只采用当前 `node_modules/next/dist/docs` 中存在的约定。

- [ ] **步骤 2：编写模式 UI 失败测试**

```js
test("normal chat exposes four interactive approval modes", () => {
  assert.deepEqual(interactiveApprovalModes().map((item) => item.value), [
    "DEFAULT", "ACCEPT_EDITS", "EXPLORE", "BYPASS",
  ]);
});

test("dont ask remains backend-only", () => {
  assert.equal(interactiveApprovalModes().some((item) => item.value === "DONT_ASK"), false);
});

test("harness creation requires an explicit selected mode", () => {
  assert.equal(buildHarnessSessionPayload("harness-agent", null), null);
});
```

- [ ] **步骤 3：运行测试并确认失败**

```bash
source ~/.profile
cd frontend
npm test -- --test-name-pattern="approval mode|dont ask|harness creation"
```

预期：FAIL，approval helper 尚不存在。

- [ ] **步骤 4：定义前端稳定类型**

```ts
export type ApprovalMode =
  | "DEFAULT"
  | "ACCEPT_EDITS"
  | "EXPLORE"
  | "BYPASS"
  | "DONT_ASK";
```

`ChatSessionMeta` / `ChatSessionSummary` 增加 `approvalMode: ApprovalMode | null`；`createChatSession` payload 增加 `approvalMode`。

- [ ] **步骤 5：实现独立 ApprovalModePicker**

该模块只接收：

```ts
type Props = {
  value: ApprovalMode;
  disabled: boolean;
  onChange: (mode: ApprovalMode) => void;
  onConfirm: () => void;
  onBack: () => void;
};
```

四张模式卡必须展示行为和风险；`BYPASS` 文案为“自动执行（仍受平台安全规则约束）”。默认选中 `DEFAULT`，用户点击“创建会话”后才调用后端。

- [ ] **步骤 6：修改所有 Harness 新建入口**

`page.tsx` 的新会话步骤扩展为：

```ts
"types" | "domain" | "approval"
```

点击“协作 Agent”进入 `approval`，不立即创建。`/chat?agentId=harness-agent` 且没有 `sessionId` 时也必须打开该步骤，不能静默用默认 mode 自动创建。标准和领域 Agent 保持现有一步创建。

- [ ] **步骤 7：在 Harness 会话头部显示只读 mode chip**

例如：

```text
批准模式：默认审批
```

本期不提供下拉修改，避免用户误以为模式会改写正在等待的 Run。

- [ ] **步骤 8：运行前端测试和 lint**

```bash
source ~/.profile
cd frontend
npm test
npm run lint
```

预期：全部通过且 ESLint 无错误。

- [ ] **步骤 9：提交本任务**

```bash
source ~/.profile
git add frontend/lib/approval-requests.ts \
  frontend/lib/approval-ui.ts \
  frontend/lib/approval-ui.test.mjs \
  frontend/app/chat/approval-mode-picker.tsx \
  frontend/lib/chat-sessions.ts \
  frontend/lib/chat-agent-mode.ts \
  frontend/lib/chat-agent-mode.test.mjs \
  frontend/app/chat/page.tsx
git commit -m "feat: select approval mode before harness chat"
```

### 任务 11：增加聊天内审批卡片、刷新恢复和决策续流

**文件：**
- 创建：`frontend/app/chat/approval-card.tsx`
- 修改：`frontend/lib/approval-requests.ts`
- 修改：`frontend/lib/http.ts`
- 修改：`frontend/lib/http.test.mjs`
- 修改：`frontend/app/chat/page.tsx`
- 测试：`frontend/lib/approval-ui.test.mjs`

- [ ] **步骤 1：编写 action_required SSE 失败测试**

```js
test("apiStream dispatches action_required as a terminal event", async () => {
  // 输入一个 event: action_required SSE block。
  // 断言 onActionRequired 收到 payload，reader 结束后不抛“缺少终止事件”。
});
```

再测试 APPROVE / DENY 请求只包含：

```json
{"decision":"APPROVE","version":0}
```

- [ ] **步骤 2：运行测试并确认失败**

```bash
source ~/.profile
cd frontend
npm test -- --test-name-pattern="action_required|approval decision"
```

预期：FAIL，`apiStream` 还不认识该事件。

- [ ] **步骤 3：扩展 apiStream handler**

```ts
onActionRequired?: (approval: ApprovalRequest) => void;
```

收到 `action_required` 时先把 `terminalEventReceived = true`，再调用 handler。不要把它当 `onHarnessEvent` 的特殊 kind 猜测。

- [ ] **步骤 4：实现 ApprovalCard**

Props：

```ts
type Props = {
  approval: ApprovalRequest;
  deciding: boolean;
  onDecision: (decision: "APPROVE" | "DENY") => void;
};
```

卡片展示工具名称、目标、安全摘要、风险级别和请求时间；按钮固定为“拒绝”“允许一次”。不渲染任意 HTML，不展示不存在于 DTO 的字段。

- [ ] **步骤 5：接入根 Session 和子 Session 状态**

`page.tsx` 使用：

```ts
const [pendingApprovalBySession, setPendingApprovalBySession] =
  useState<Record<string, ApprovalRequest | null>>({});
```

根流或子流收到 `action_required` 后写入对应实际 Session。当前 Session 存在待审批时禁用文本发送和附件上传，但不把全局 `streaming` 永久保持为 true。

- [ ] **步骤 6：处理决策续流**

点击按钮调用：

```http
POST /api/chat/approvals/{approvalId}/decision/stream
```

使用与普通发送相同的 reasoning/chunk/harness_event/done/error/action_required handlers，但不添加新的用户消息。成功 `done` 后清除审批卡片并刷新消息；再次 `action_required` 时用新请求替换旧请求。

- [ ] **步骤 7：实现刷新恢复**

每次 `hydrateSession` 后请求当前根 Session 的 pending approval；打开协作 Agent 抽屉时请求该实际子 Session 的 pending approval。404 表示会话不可见，`data=null` 表示没有待审批，两者不能混淆。

- [ ] **步骤 8：处理双击和过期页面**

决策开始后立即设置 `deciding=true` 并禁用两个按钮。若后端返回 version 冲突，重新查询 pending：

- 查询为空：移除卡片并刷新消息。
- 查询仍有请求：用最新 version 替换卡片。

- [ ] **步骤 9：运行前端验证**

```bash
source ~/.profile
cd frontend
npm test
npm run lint
npm run build
```

预期：测试、lint、Next.js production build 全部成功。

- [ ] **步骤 10：提交本任务**

```bash
source ~/.profile
git add frontend/app/chat/approval-card.tsx \
  frontend/app/chat/page.tsx \
  frontend/lib/approval-requests.ts \
  frontend/lib/approval-ui.test.mjs \
  frontend/lib/http.ts \
  frontend/lib/http.test.mjs
git commit -m "feat: review harness actions in chat"
```

### 任务 12：端到端恢复、安全和兼容性验证

**文件：**
- 创建：`backend/src/test/java/com/h/backend/chat/HarnessApprovalFlowIT.java`
- 修改：`backend/src/test/java/com/h/backend/chat/domain/agent/HarnessAgentExecutorTest.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
- 修改：`CONTEXT.md`

- [ ] **步骤 1：编写后端集成测试**

使用 fake Harness Runtime 驱动完整流程：

1. 创建 `DEFAULT` Harness Session。
2. 开始 Run 并发出 ASK。
3. 断言 SSE 终止于 `action_required`。
4. 断言数据库 Run 为 `WAITING_APPROVAL`、审批为 `PENDING`、无 assistant message。
5. 模拟应用侧重新加载后查询 pending。
6. APPROVE 恢复同一 runId。
7. 最终断言 Run `SUCCEEDED` 且只有一条最终 assistant message。

再以 DENY 执行一遍，断言 Agent 收到 `confirmed=false` 后仍能形成最终回答。

- [ ] **步骤 2：增加模式行为 contract tests**

用确定性测试 Tool 覆盖：

| 模式 | 预期 |
| --- | --- |
| `DEFAULT` | 未命中规则的写操作产生 ASK |
| `ACCEPT_EDITS` | 工作目录内安全编辑 ALLOW |
| `EXPLORE` | 写操作 DENY，不生成 Approval Request |
| `BYPASS` | 普通操作 ALLOW；显式 ASK 规则仍 ASK |
| `DONT_ASK` | 普通 ASK fallback 转成 DENY |

测试必须使用 `AgentScopeApprovalAdapter` 安装后的真实 `PermissionContextState`，防止只测 enum 映射而漏掉 trivial context 回归。

同时增加升级兼容测试：旧创建请求缺少 `approvalMode` 时落库为 `BYPASS`；migration fixture 中既有 Harness 根 Session 和两层子 Session 都递归回填 `BYPASS`，普通 Agent Session 仍为 `NULL`。

- [ ] **步骤 3：增加安全回归测试**

断言以下值不出现在 `ApprovalRequestDto`、SSE JSON 和普通日志 capture 中：

```text
Authorization: Bearer secret
API_KEY=secret
password=secret
完整 write_file content
```

同时断言浏览器提交额外 `toolCall` / `rules` 字段不会被 Controller 采纳。

- [ ] **步骤 4：增加并发和幂等测试**

并发提交两个相同 version 的决策，只有一个请求能完成 CAS 并调用 continuation 一次。等待审批期间的新聊天消息被拒绝，不新增用户消息和 Agent Run。

- [ ] **步骤 5：更新项目词汇文档**

在 `CONTEXT.md` 增加：

```text
批准模式：绑定 Agent Session、决定工具调用默认 ALLOW / DENY / ASK 行为的用户选择。

审批请求：某个 Agent Run 因 ASK 暂停后形成的可持久化业务身份；不属于协作 Agent 状态。

审批决策：用户对一次审批请求给出的允许一次或拒绝结果；它恢复原 Agent Run，不创建新 Run。
```

- [ ] **步骤 6：运行后端完整测试**

```bash
source ~/.profile
cd backend
mvn test
```

预期：`BUILD SUCCESS`。需要 PostgreSQL 的 Spring 集成测试使用仓库现有 `backend/src/test/resources/application.yml` 环境。

- [ ] **步骤 7：运行前端完整验证**

```bash
source ~/.profile
cd frontend
npm test
npm run lint
npm run build
```

预期：全部成功。

- [ ] **步骤 8：执行手工验收矩阵**

1. `DEFAULT`：请求修改工作区文件，出现卡片；允许后执行并回答。
2. `DEFAULT`：相同操作拒绝，工具不执行，Agent 给出拒绝后的说明。
3. 刷新等待页面：卡片恢复，原 runId 不变。
4. 双击允许：只恢复一次。
5. `ACCEPT_EDITS`：工作目录安全编辑不弹卡；其他 ASK 仍弹卡。
6. `EXPLORE`：写操作被拒绝且无卡片。
7. `BYPASS`：普通操作自动执行；显式 ASK / 危险路径仍可弹卡。
8. 子 Agent 抽屉直接 turn：ASK 卡片只出现在该子 Session，父 Session 输入不被锁死。
9. 等待审批时尝试继续给同一 Session 发消息：收到明确提示，不新增消息。
10. 浏览器网络面板：`action_required` 不含完整工具输入或 secret。

- [ ] **步骤 9：提交最终验证与词汇更新**

```bash
source ~/.profile
git add CONTEXT.md \
  backend/src/test/java/com/h/backend/chat/HarnessApprovalFlowIT.java \
  backend/src/test/java/com/h/backend/chat/domain/agent/HarnessAgentExecutorTest.java \
  backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java
git commit -m "test: verify durable harness hitl flow"
```

## 5. 发布与回滚顺序

### 5.1 发布顺序

部署前先执行只读预检；结果必须为 0 行，否则先调查并收口历史开放 Run，不能删除记录规避约束：

```sql
SELECT session_id, COUNT(*)
FROM agent_runs
WHERE status IN ('RUNNING', 'WAITING_APPROVAL')
GROUP BY session_id
HAVING COUNT(*) > 1;
```

1. 先部署包含 Flyway migration 和兼容旧前端的后端。
2. 验证既有 Harness 根 Session 及后代均已回填 `BYPASS`，旧客户端不传 mode 时也保持 `BYPASS`。
3. 再部署前端模式选择器与审批卡片。
4. 验证新前端始终显式发送 mode，并默认发送 `DEFAULT`。
5. 观察 `WAITING_APPROVAL` 数量、决策延迟、恢复失败率和 version 冲突率。

### 5.2 回滚约束

- 前端可独立回滚；后端仍可处理已创建审批请求。
- 后端业务代码不能在存在 `WAITING_APPROVAL` Run 时直接回滚到不认识该状态的版本。
- 必须先把所有 PENDING 请求决策或取消，并把对应 Run 收口到 `FAILED`，确认查询结果为 0 后才能回滚后端。
- 数据库新增列和表保持向后兼容，不在紧急回滚中删除；结构清理由独立 migration 完成。

## 6. 完成定义

只有同时满足以下条件，本需求才算完成：

1. 五种后端模式与 AgentScope 行为 contract tests 全部通过。
2. 用户创建 Harness 会话前可以明确选择四种交互模式。
3. ASK 后刷新页面仍能看到同一个 PENDING 请求。
4. 允许与拒绝都恢复原 Run，且不创建第二个 Agent Run。
5. 同一请求最多恢复一次，同一 Session 等待期间不能开始新 turn。
6. SSE、审批 DTO 和日志中不存在原始敏感工具输入。
7. 根 Harness 和用户直接寻址的子 Agent Session 都通过集成测试。
8. 后端 `mvn test`、前端 `npm test`、`npm run lint`、`npm run build` 全部通过。
9. `CONTEXT.md` 明确区分批准模式、审批请求、审批决策、Agent Run 与协作 Agent 状态。

## 7. 独立后续计划入口

父 Run 内部同步子 Agent 的交互式审批需要独立计划，前置条件是为 `agent_spawn` 建立可持久化 continuation：子 Session 获批后恢复子工具、取得子最终结果，再把该结果写回父 Session 尚未完成的 `agent_spawn` ToolResult，最后恢复父 ReAct loop。该能力不能通过简单转发 `RequireUserConfirmEvent` 或直接恢复子 Session 冒充完成；在独立设计完成前，本计划禁止把内部子事件渲染为可点击审批卡片。
