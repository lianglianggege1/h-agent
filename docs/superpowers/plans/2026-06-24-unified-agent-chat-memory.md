# Unified Agent Chat and Scoped Memory 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 统一普通聊天和领域 Agent 的会话入口，并为 agentic 子 Agent 引入 scoped memory，避免历史会话错路由和子 Agent 记忆污染。

**架构：** `/chat` 以 `chat_sessions.agent_id` 作为运行时事实来源，前端根据会话 Agent 元数据切换普通/领域模式。后端保留 root execution id 用于运行和事件路由，新增 `mem:v2` scoped memory id 用于子 Agent 记忆隔离，并让共享事实留在 `AgenticScope`/结构化状态中。

**技术栈：** Spring Boot 3、MyBatis Plus、PostgreSQL/Flyway、Redis/Redisson、LangChain4j Agentic、Next.js 16、React、Node test、Maven/JUnit。

---

## 文件结构

后端会话与 Agent 元数据：

- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatSessionMetaDto.java`
- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatSessionSummaryDto.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`

前端统一聊天入口：

- 修改：`frontend/lib/chat-sessions.ts`
- 修改：`frontend/app/chat/page.tsx`
- 修改：`frontend/app/agents/page.tsx`
- 修改：`frontend/app/me/agents/[agentId]/page.tsx`
- 创建：`frontend/lib/chat-agent-mode.ts`
- 创建/修改：`frontend/lib/chat-agent-mode.test.mjs`

Memory identity 与存储：

- 创建：`backend/src/main/java/com/h/backend/chat/memory/ChatMemoryIdFactory.java`
- 修改：`backend/src/main/java/com/h/backend/chat/memory/ChatMemoryContext.java`
- 修改：`backend/src/main/java/com/h/backend/chat/memory/RedisChatMemoryStore.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatMemorySnapshotServiceImpl.java`
- 修改：`backend/src/main/java/com/h/backend/chat/entity/ChatMemorySnapshotEntity.java`
- 修改：`backend/src/main/java/com/h/backend/chat/mapper/ChatMemorySnapshotMapper.java`
- 创建：`backend/src/main/resources/db/migration/V20260624_01__agent_scoped_memory_snapshots.sql`
- 修改：`backend/src/test/java/com/h/backend/chat/RedisChatMemoryStoreTest.java`
- 创建/修改：`backend/src/test/java/com/h/backend/chat/memory/ChatMemoryIdFactoryTest.java`

Agentic 配置：

- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
- 修改：`backend/src/main/java/com/h/backend/chat/agent/ChatAgentExecutionCommand.java`
- 修改：`backend/src/main/java/com/h/backend/chat/agent/AgenticSyncExecutor.java`
- 修改：`backend/src/main/java/com/h/backend/chat/config/AgentConfig.java`
- 修改：`backend/src/test/java/com/h/backend/chat/config/AgentConfigTest.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ai/carrentalassistant/services/CarRentalAssistantMemoryIdTest.java`

## 任务 1：会话 DTO 返回 Agent 元数据

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatSessionMetaDto.java`
- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatSessionSummaryDto.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatSessionServiceImplTest.java`

- [ ] **步骤 1：编写失败测试**

在 `ChatSessionServiceImplTest` 中增加用例，创建普通会话和领域 Agent 会话后断言 DTO 包含 Agent 显示信息。

```java
assertEquals("standard-chat", open.session().agentId());
assertEquals("普通聊天", open.session().agentDisplayName());
assertEquals("STANDARD_STREAMING_CHAT", open.session().runtimeType());
```

领域 Agent 断言：

```java
assertEquals("car-rental-assistant", open.session().agentId());
assertEquals("租车应急协助 Agent", open.session().agentDisplayName());
assertEquals("AGENTIC_SYNC", open.session().runtimeType());
assertNull(open.session().promptId());
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd backend
mvn -Dtest=ChatSessionServiceImplTest test
```

预期：编译失败或断言失败，因为 DTO 还没有 `agentDisplayName` / `runtimeType` 字段。

- [ ] **步骤 3：扩展 DTO 与映射**

更新 `ChatSessionMetaDto` 和 `ChatSessionSummaryDto`：

```java
String agentDisplayName,
String agentDomain,
String runtimeType,
```

给 `ChatSessionServiceImpl` 注入 `AgentRegistry`，在 `toMeta(...)` 和 `toSummary(...)` 内通过 `agentId` 查找定义。若 Agent 不存在，返回：

```java
displayName = sessionAgentId
domain = "未知"
runtimeType = "UNKNOWN"
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
cd backend
mvn -Dtest=ChatSessionServiceImplTest test
```

预期：`BUILD SUCCESS`，相关测试通过。

## 任务 2：前端类型与 Agent 模式工具

**文件：**
- 修改：`frontend/lib/chat-sessions.ts`
- 创建：`frontend/lib/chat-agent-mode.ts`
- 创建：`frontend/lib/chat-agent-mode.test.mjs`

- [ ] **步骤 1：编写失败测试**

创建 `frontend/lib/chat-agent-mode.test.mjs`：

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import { agentModeFromSession, isStandardAgent } from "./chat-agent-mode.ts";

test("agentModeFromSession identifies standard chat", () => {
  assert.equal(isStandardAgent("standard-chat"), true);
  assert.equal(agentModeFromSession({ agentId: "standard-chat" }), "standard");
});

test("agentModeFromSession identifies domain agent", () => {
  assert.equal(isStandardAgent("car-rental-assistant"), false);
  assert.equal(agentModeFromSession({ agentId: "car-rental-assistant" }), "domain");
});
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd frontend
npm test
```

预期：失败，提示找不到 `chat-agent-mode.ts`。

- [ ] **步骤 3：实现类型和工具**

更新 `ChatSessionMeta` / `ChatSessionSummary`：

```ts
agentDisplayName: string;
agentDomain: string;
runtimeType: string;
```

创建 `frontend/lib/chat-agent-mode.ts`：

```ts
export const STANDARD_AGENT_ID = "standard-chat";

export function isStandardAgent(agentId: string | null | undefined) {
  return !agentId || agentId === STANDARD_AGENT_ID;
}

export function agentModeFromSession(session: { agentId: string | null | undefined }) {
  return isStandardAgent(session.agentId) ? "standard" : "domain";
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
cd frontend
npm test
```

预期：所有前端测试通过。

## 任务 3：统一 `/chat` 的 session hydration 与发送 Agent

**文件：**
- 修改：`frontend/app/chat/page.tsx`
- 修改：`frontend/lib/chat-agent-mode.test.mjs`

- [ ] **步骤 1：补充前端纯函数测试**

在 `chat-agent-mode.test.mjs` 增加发送 payload 构造测试。先实现为纯函数，避免直接测试整页组件。

```js
import { buildChatSendPayload } from "./chat-agent-mode.ts";

test("buildChatSendPayload sends domain agent id and null prompt", () => {
  assert.deepEqual(
    buildChatSendPayload({
      message: "救援",
      sessionId: "s1",
      agentId: "car-rental-assistant",
      promptId: 9,
    }),
    {
      message: "救援",
      sessionId: "s1",
      agentId: "car-rental-assistant",
      promptId: null,
    },
  );
});
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd frontend
npm test
```

预期：失败，`buildChatSendPayload` 未定义。

- [ ] **步骤 3：实现 payload 工具**

在 `chat-agent-mode.ts` 增加：

```ts
export function buildChatSendPayload(input: {
  message: string;
  sessionId: string;
  agentId: string;
  promptId: number | null;
}) {
  const standard = isStandardAgent(input.agentId);
  return {
    message: input.message,
    sessionId: input.sessionId,
    promptId: standard ? input.promptId : null,
    agentId: standard ? STANDARD_AGENT_ID : input.agentId,
  };
}
```

- [ ] **步骤 4：改 `/chat` 使用 currentAgentId**

在 `frontend/app/chat/page.tsx` 增加：

```ts
const [currentAgentId, setCurrentAgentId] = useState("standard-chat");
const [currentAgentName, setCurrentAgentName] = useState("普通聊天");
```

在 `hydrateSession(...)` 中设置：

```ts
setCurrentAgentId(detail.agentId || "standard-chat");
setCurrentAgentName(detail.agentDisplayName || "普通聊天");
setSelectedPromptId(detail.agentId === "standard-chat" ? (detail.promptId ?? fallbackPromptId) : null);
```

在 `handleSubmit(...)` 中替换写死 payload：

```ts
body: JSON.stringify(buildChatSendPayload({
  message: content,
  sessionId,
  promptId: selectedPromptId,
  agentId: currentAgentId,
})),
```

- [ ] **步骤 5：根据模式切换 UI**

普通模式显示 SystemPrompt 面板；领域模式隐藏该面板，并显示当前 Agent 卡片：

```tsx
{currentAgentId === "standard-chat" ? (
  <SystemPromptPanel />
) : (
  <DomainAgentPanel agentId={currentAgentId} agentName={currentAgentName} />
)}
```

实现时可先不抽组件，保持局部 JSX 条件渲染。

- [ ] **步骤 6：运行前端验证**

运行：

```bash
cd frontend
npm test
npm run lint
```

预期：测试通过；lint 无 error。

## 任务 4：`/agents` 改为发现页和 `/chat` 快速启动

**文件：**
- 修改：`frontend/app/agents/page.tsx`
- 修改：`frontend/app/me/agents/[agentId]/page.tsx`

- [ ] **步骤 1：将 quick-start 目标改为 `/chat`**

在 `/me/agents/[agentId]/page.tsx` 中，把“开始问答”链接改为：

```tsx
href={`/chat?agentId=${encodeURIComponent(topology.agent.agentId)}`}
```

- [ ] **步骤 2：改 `/agents` 选择 Agent 行为**

保留搜索、领域筛选、Agent 卡片。点击 Agent 时：

```ts
router.push(`/chat?agentId=${encodeURIComponent(agent.agentId)}`);
```

删除或停用该页面内独立的 `apiStream` 聊天实现，避免两套聊天逻辑。

- [ ] **步骤 3：让 `/chat` 支持 URL agentId 启动**

在 `/chat` 初次 bootstrap 后，如果 URL 包含 `agentId` 且与当前 session 不同，调用：

```ts
createChatSession({
  currentSessionId: sessionId,
  promptId: null,
  agentId: requestedAgentId,
})
```

然后 `hydrateSession(...)`。

- [ ] **步骤 4：运行前端验证**

运行：

```bash
cd frontend
npm test
npm run lint
```

预期：测试通过；lint 无 error。

## 任务 5：Memory ID v2 工厂和解析器

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/memory/ChatMemoryIdFactory.java`
- 修改：`backend/src/main/java/com/h/backend/chat/memory/ChatMemoryContext.java`
- 创建：`backend/src/test/java/com/h/backend/chat/memory/ChatMemoryIdFactoryTest.java`
- 修改：`backend/src/test/java/com/h/backend/chat/RedisChatMemoryStoreTest.java`

- [ ] **步骤 1：编写失败测试**

创建 `ChatMemoryIdFactoryTest`：

```java
@Test
void buildsRootExecutionId() {
    ChatMemoryIdFactory factory = new ChatMemoryIdFactory();
    assertEquals(
            "exec:v2:user:1:session:s1:agent:car-rental-assistant",
            factory.executionId(1L, "s1", "car-rental-assistant")
    );
}

@Test
void buildsScopedMemoryIdFromExecutionId() {
    ChatMemoryIdFactory factory = new ChatMemoryIdFactory();
    assertEquals(
            "mem:v2:user:1:session:s1:agent:car-rental-assistant:scope:customer-info-extractor",
            factory.scopedMemoryId(
                    "exec:v2:user:1:session:s1:agent:car-rental-assistant",
                    "customer-info-extractor"
            )
    );
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd backend
mvn -Dtest=ChatMemoryIdFactoryTest test
```

预期：编译失败，类不存在。

- [ ] **步骤 3：实现 factory 和 context**

`ChatMemoryContext` 改为：

```java
public record ChatMemoryContext(
        Long userId,
        Long promptId,
        String sessionId,
        String agentId,
        String memoryScope
) {}
```

`ChatMemoryIdFactory` 提供：

```java
String executionId(Long userId, String sessionId, String agentId)
String scopedMemoryId(String executionId, String scopeKey)
ChatMemoryContext parse(Object memoryId)
```

解析器必须支持旧格式：

```text
{userId}:{promptId}:{sessionId}
{userId}:agent:{agentId}:{sessionId}
```

以及新格式：

```text
exec:v2:user:{userId}:session:{sessionId}:agent:{agentId}
mem:v2:user:{userId}:session:{sessionId}:agent:{agentId}:scope:{scopeKey}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
cd backend
mvn -Dtest=ChatMemoryIdFactoryTest test
```

预期：`BUILD SUCCESS`。

## 任务 6：Memory snapshot 支持 agentId + memoryScope

**文件：**
- 创建：`backend/src/main/resources/db/migration/V20260624_01__agent_scoped_memory_snapshots.sql`
- 修改：`backend/src/main/java/com/h/backend/chat/entity/ChatMemorySnapshotEntity.java`
- 修改：`backend/src/main/java/com/h/backend/chat/mapper/ChatMemorySnapshotMapper.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatMemorySnapshotServiceImpl.java`
- 修改：`backend/src/main/java/com/h/backend/chat/memory/RedisChatMemoryStore.java`
- 修改：`backend/src/test/java/com/h/backend/chat/RedisChatMemoryStoreTest.java`

- [ ] **步骤 1：编写失败测试**

在 `RedisChatMemoryStoreTest` 中增加测试：

```java
store.getMessages("mem:v2:user:1:session:s1:agent:car-rental-assistant:scope:customer-info-extractor");
verify(snapshotService).loadSnapshot(new ChatMemoryContext(
        1L,
        null,
        "s1",
        "car-rental-assistant",
        "customer-info-extractor"
));
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd backend
mvn -Dtest=RedisChatMemoryStoreTest test
```

预期：失败，因为 store 还没有使用 v2 parser。

- [ ] **步骤 3：创建迁移**

迁移内容：

```sql
ALTER TABLE chat_memory_snapshots
    ADD COLUMN IF NOT EXISTS agent_id VARCHAR(64) NOT NULL DEFAULT 'standard-chat',
    ADD COLUMN IF NOT EXISTS memory_scope VARCHAR(128) NOT NULL DEFAULT 'default';

DROP INDEX IF EXISTS uk_chat_memory_snapshots_session_id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_memory_snapshots_session_agent_scope
ON chat_memory_snapshots(session_id, agent_id, memory_scope);
```

如果现有唯一约束不是索引名，先用 `rg "chat_memory_snapshots"` 查迁移文件并按实际名称处理。

- [ ] **步骤 4：更新实体与 mapper**

实体新增：

```java
@TableField("agent_id")
private String agentId;

@TableField("memory_scope")
private String memoryScope;
```

Mapper 查询改为：

```java
selectBySessionScope(String sessionId, String agentId, String memoryScope)
selectBySessionId(String sessionId)
selectAllBySessionId(String sessionId)
upsertLatestSnapshot(ChatMemorySnapshotEntity entity)
```

`upsertLatestSnapshot` 使用冲突键：

```sql
ON CONFLICT (session_id, agent_id, memory_scope) DO UPDATE
```

- [ ] **步骤 5：更新 service key 和锁**

`memoryKey/versionKey/dirtyKey/lockKey` 加入 `agentId` 和 `memoryScope`。

`loadSnapshot/cacheMemory/deleteHotMemory` 使用 scoped 查询。

`flushNow(sessionId)` 需要能处理当前 session 的所有 dirty scope。实现方式可以先保守：

1. `scheduleFlush(context, ...)` 的 pending key 改为 `sessionId + ":" + agentId + ":" + memoryScope`。
2. 新增 `flushNow(ChatMemoryContext context)` 私有方法。
3. `flushNow(String sessionId)` 查询该 session 已知 snapshots 并逐个 flush；没有快照的热 memory 通过 scheduled context flush。

- [ ] **步骤 6：运行后端测试**

运行：

```bash
cd backend
mvn -Dtest=RedisChatMemoryStoreTest,ChatSessionServiceImplTest test
```

预期：`BUILD SUCCESS`。

## 任务 7：Agentic runtime 使用 root execution id 与 scoped memory

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
- 修改：`backend/src/main/java/com/h/backend/chat/agent/ChatAgentExecutionCommand.java`
- 修改：`backend/src/main/java/com/h/backend/chat/agent/AgenticSyncExecutor.java`
- 修改：`backend/src/main/java/com/h/backend/chat/config/AgentConfig.java`
- 修改：`backend/src/test/java/com/h/backend/chat/ChatServiceImplTest.java`
- 修改：`backend/src/test/java/com/h/backend/chat/config/AgentConfigTest.java`

- [ ] **步骤 1：更新 ChatServiceImpl 测试**

把现有领域 Agent memory id 断言从：

```java
assertEquals("1:agent:car-rental-assistant:session-car", agenticExecutor.command.memoryId());
```

改为：

```java
assertEquals(
        "exec:v2:user:1:session:session-car:agent:car-rental-assistant",
        agenticExecutor.command.memoryId()
);
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd backend
mvn -Dtest=ChatServiceImplTest test
```

预期：断言失败，当前仍是旧 memory id。

- [ ] **步骤 3：ChatServiceImpl 使用 factory**

注入 `ChatMemoryIdFactory`，替换 `buildMemoryId(...)`：

普通聊天可先继续旧格式：

```java
return userId + ":" + resolvedPromptId + ":" + sessionId;
```

领域 Agent 使用：

```java
return chatMemoryIdFactory.executionId(userId, sessionId, agent.agentId());
```

- [ ] **步骤 4：AgentConfig 增加 scoped provider**

注入 `ChatMemoryIdFactory`，添加 helper：

```java
private ChatMemoryProvider scopedMemoryProvider(String scopeKey) {
    return memoryId -> MessageWindowChatMemory.builder()
            .id(chatMemoryIdFactory.scopedMemoryId(String.valueOf(memoryId), scopeKey))
            .maxMessages(10)
            .alwaysKeepSystemMessageFirst(true)
            .chatMemoryStore(redisChatMemoryStore)
            .build();
}
```

只给 `CustomerInfoExtractionService` 保留 `.chatMemoryProvider(scopedMemoryProvider("customer-info-extractor"))`。

其它当前不需要多轮私有记忆的子 Agent 先移除 `.chatMemoryProvider(...)`。

- [ ] **步骤 5：调整 @MemoryId 测试策略**

`CarRentalAssistantMemoryIdTest` 当前要求所有带 memory provider 的服务都声明 `@MemoryId`。改成只检查配置了 memory provider 的服务或保留接口声明但不配置 provider。若接口保留 `@MemoryId`，要确认 LangChain4j 在无 provider 时仍能接受参数。

- [ ] **步骤 6：运行后端聚焦测试**

运行：

```bash
cd backend
mvn -Dtest=ChatServiceImplTest,AgentConfigTest,CarRentalAssistantMemoryIdTest,RedisChatMemoryStoreTest test
```

预期：`BUILD SUCCESS`。

## 任务 8：端到端验证普通/领域历史会话

**文件：**
- 修改测试视需要而定。

- [ ] **步骤 1：后端完整聚焦回归**

运行：

```bash
cd backend
mvn -Dtest=CustomerInfoTest,AgentConfigTest,CarRentalAssistantMemoryIdTest,AgentTopologyMapperTest,AgentControllerTest,AgenticSyncExecutorTest,AgentStepListenerTest,ChatServiceImplTest,ChatSessionServiceImplTest,RedisChatMemoryStoreTest test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 2：前端测试与 lint**

运行：

```bash
cd frontend
npm test
npm run lint
```

预期：测试通过；lint 无 error。现有 `<img>` warning 可保留。

- [ ] **步骤 3：构建验证**

运行：

```bash
cd frontend
npm run build
```

预期：build 成功。若 `frontend/next-env.d.ts` 被构建命令机械改动，确认是否为现有 Next 行为，避免无关 churn。

- [ ] **步骤 4：浏览器手动验证**

启动或确认服务：

```bash
cd frontend
npm run dev
```

后端应在 `8081`。

在浏览器验证：

1. 登录 `test@test` / `12345678`。
2. 打开 `/chat`，普通聊天发送一条消息。
3. 打开 `/me/agents/car-rental-assistant`，点击开始问答，应进入 `/chat?agentId=car-rental-assistant`。
4. 发送领域 Agent 消息，确认显示子 Agent 状态。
5. 从历史会话打开该领域 Agent 会话，再发送一条消息。
6. 确认请求 payload 使用 `agentId=car-rental-assistant`，不是 `standard-chat`。

- [ ] **步骤 5：检查 memory scope**

通过 Redis 或数据库确认同一 session 下出现 scoped key/row：

```text
agent_id = car-rental-assistant
memory_scope = customer-info-extractor
```

且没有所有子 Agent 共用 `default` 或 `legacy-root` 的新写入。

