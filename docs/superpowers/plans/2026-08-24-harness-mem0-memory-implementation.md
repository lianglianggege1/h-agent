# Harness Agent Mem0 长期记忆改造实施计划

> **状态：已取消，禁止执行本计划。** 2026-08-27 的架构决策已放弃“替换 Harness 内建
> Markdown 长期记忆”方向。新设计见
> `docs/superpowers/specs/2026-08-27-langchain4j-mem0-long-term-memory-design.md`；应基于新设计另行编写
> LangChain4j 实施计划，不得从本文选取 Harness patch/hooks/compaction/provider 任务继续实施。

> **面向 AI 代理的工作者：** 按任务顺序实施，使用复选框（`- [ ]`）跟踪进度。每个任务先写失败测试，再写最小实现。mem0 服务部署不在本计划内；未经对应质量门槛确认，不得提前扩大灰度。

- 日期：2026-08-24
- 修订：2026-08-26
- 状态：已取消，禁止实施
- 设计依据：`docs/superpowers/specs/2026-08-23-harness-mem0-memory-design.md`
- 产品依据：`docs/prd/2026-08-08-harness-agent-prd.md`
- 改造范围：Harness 父 Agent/Worker 的 USER、AGENT、RUN 三级长期记忆、管理 API、`/me/memory` 页面

**目标：** 把 Harness 的 `MEMORY.md` / `memory/YYYY-MM-DD.md` 长期记忆迁移到自托管 mem0 OSS，使 mem0 成为正文与语义演化的唯一真相源，PostgreSQL 只保存 owner/version/category/saga 控制事实；保留 AgentState、session log、compaction summary、Skills、subagents 与普通 Agent chat memory 的现有存储。

**架构：** 建立 `HarnessMemoryRuntime` 与 `UserMemoryCatalog` 两个调用方导向的深 Module；内部使用固定版本的 `Mem0Gateway`、`HarnessMemoryScopePolicy`、不含正文的 PostgreSQL 控制索引和 capture outbox。执行上下文中的 user/agent/run 全部必填；落到 mem0 时按 `USER=user`、`AGENT=user+agent`、`RUN=user+agent+run` 逐级收窄。父 Agent/Worker 每 turn 对各自可见层级最多 recall 一次；final、run success 和 outbox 在同一事务提交，worker 以 at-least-once + remote reconciliation 调用 mem0；管理页分页、owner、scope 筛选、409 和 mutation saga 由本地控制索引提供。通过部署实例级 `filesystem|shadow|mem0` 条件装配灰度，不在单例 HarnessAgent 内按 user hash 切换。

**技术栈：** Java 26、Spring Boot 4.0.6、WebClient、Reactor、MyBatis-Plus、Flyway、PostgreSQL、Redisson、AgentScope Harness 2.0.1（需最小补丁或升级）、Next.js 16.2.6、React 19.2.4、JUnit 5、Mockito、AssertJ、Node test runner。

---

## 1. 已确认的边界与不变量

1. 只替换 Harness 用户长期事实、偏好、项目上下文、决策、承诺/待办与其他长期信息。
2. 不改 `agent_state_snapshots`、session JSONL、compaction summary、普通/领域 Agent Redis chat memory。
3. 父 Agent 与 Worker 都可 recall/capture；Worker 共享 USER，只能访问自己的 AGENT 和当前 RUN。
4. 每次执行上下文的 userId、stable logical agentId、stable taskSessionId 全部必填；mem0 scope 由服务端构造，前端和工具不得传入原始 mem0 entity id。
5. `USER` 只写 `user_id`；`AGENT` 写 `user_id+agent_id`；`RUN` 写 `user_id+agent_id+run_id`。`run_id` 非空时 `agent_id` 必须非空。
6. `agent_id` 使用稳定逻辑 Agent ID；`run_id` 使用稳定任务/子会话 ID。每轮本地 agentRunId 只作审计和幂等，不映射为 mem0 `run_id`。
7. 自动 capture 只发送本轮输入/assignment 与 final assistant；禁止发送 system、reasoning、工具参数/结果和其他 Agent 内部事件。
8. 自动 capture 每个成功父/Worker turn 都进入 outbox；失败、取消不 capture。
9. 显式“记住”使用 `infer=false` 精确写入，并显式选择 `USER|AGENT|RUN`；只有拿到 memory id 后才能报告成功。
10. 一个独立事实、偏好或决策对应一条 memory；一个自动 capture batch 只投递一个规范 scope，禁止盲目复制三份。
11. Agent 可主动 save；update/delete 只有本轮用户明确要求时才能调用。
12. 当前用户消息优先于旧记忆；冲突只在相同 entity scope 内推动旧事实演化。
13. recall 故障 fail-open；自动 capture 通过 outbox 重试；显式 mutation fail-closed。
14. 更新使用本地控制索引 version；冲突返回 409。项目内所有写入口按规范 entity scope 串行化，禁止旁路应用直接修改 mem0。
15. 删除单条 memory 时先验证 user owner，再从 mem0 硬删除；agent/run 不能替代授权，本地审计不保存正文。
16. 普通长期记忆没有统一 TTL；账号注销时按 user scope 清空三个层级并验证结果。
17. `filesystem` 仅用于迁移前；`shadow` 旧文件服务模型、mem0 只写和比对；`mem0` 停止 Markdown 写入。
18. 正式 mem0 写入开始后，不允许把旧 filesystem 版本当作数据回滚目标。
19. 旧 Markdown 在 100% 切换后只读保留 30 天，再删除或按既有备份策略归档。
20. 第一期只实现固定版本的自托管 mem0 OSS HTTP contract；不实现 Platform adapter，不设计 mem0 部署。
21. 控制索引只保存 memory id、owner、scope kind、agent/run、version、分类、来源、远端 hash/时间和 saga 状态；mutation operation 只保存稳定 key、request hash、状态与远端 ids，均不保存正文。
22. 本地 turn key 只防重复入队；远端投递为 at-least-once，未知结果必须按目标 entity scope reconciliation 后再决定是否重试。

## 2. 目标数据流

```text
父 Agent / Worker turn
    │
    ├─ HarnessMemoryTurnContext（userId/agentId/taskSessionId/agentRunId/actorKind 全部由服务端构造）
    │
    ├─ Mem0RecallMiddleware
    │    ├─ USER / 当前 AGENT / 当前 RUN 各最多 search 一次
    │    ├─ 合并、去重、重排、统一预算
    │    ├─ timeout/5xx -> 空结果继续
    │    └─ transient <recalled_user_memory>，不写 AgentState
    │
    ├─ Mem0MemoryTools -> UserMemoryCatalog
    │    ├─ search/get/save
    │    ├─ update/delete 需要明确用户意图标记
    │    └─ 显式 mutation -> 持久化 MEMORY 消息 + SSE 结果卡片
    │
    └─ 成功 AgentEndEvent
         └─ HarnessTurnCompletion
              ├─ final assistant/Worker result
              ├─ agent run success
              └─ PostgreSQL outbox（同一事务）
                   └─ scope policy -> reconcile/submit mem0 infer=true

/api/me/memories -> UserMemoryCatalog -> control index + Mem0Gateway
```

## 3. Provider 行为矩阵

| 行为 | `filesystem` | `shadow` | `mem0` |
| --- | --- | --- | --- |
| 模型 recall | 旧 Markdown | 旧 Markdown | mem0 transient recall |
| 正常 turn 长期写入 | 旧 hooks/tools | 旧 hooks/tools + mem0 outbox | mem0 outbox/tools |
| compaction memory flush | 开 | 开 | 关 |
| session offload/summary | 保留 | 保留 | 保留 |
| mem0 search 对模型可见 | 否 | 否，只做对比指标 | 是 |
| `/me/memory` 数据源 | 功能未开放 | mem0 shadow 数据，只读核验 | 控制索引 + mem0 正文 |

`shadow` 不是永久双写模式。provider 在 Spring 单例 HarnessAgent 构建时固定，因此第一期只支持部署实例级切换。若生产必须 canary，使用不同 provider 的独立应用池和 sticky routing；不在项目内引入按 user hash 的多 Agent provider 重构。

## 4. 实施前置检查

- [ ] 记录当前 `io.agentscope:agentscope-harness` 的精确版本与来源。
- [ ] 确认 SDK 修复方案：升级到已修复版本，或发布一个最小补丁 artifact。
- [ ] 用反编译/源码测试确认 emergency compaction 继承配置的 `flushBeforeCompact` 和 `offloadBeforeCompact`。
- [ ] 固定 mem0 image tag 或 git commit，保存 `/openapi.json` fixture 和 SHA-256；禁止浮动 `latest`。
- [ ] 确认固定版本提供 `/memories`、`/search`、history 与 `X-API-Key` contract；不在仓库内实现部署。
- [ ] 真实 contract spike 验证：`/search` 顶层 `user_id/agent_id/run_id` 请求形状、同一 payload 保存三个 entity 字段、三个字段 AND 查询、仅 user 查询可覆盖该用户三个层级、metadata `scope_kind` filter、按 id endpoint 无 user filter、get-all 无 page/offset、turn_key 可核验、delete 是否物理清除 history 正文。
- [ ] 在创建 Flyway 文件当天运行 `ls backend/src/main/resources/db/migration | sort -V | tail`，使用当时下一个可用版本。

> 当前另有 MinIO 实施计划预留同日迁移编号。本文不硬编码 Flyway 序号，实施时必须一次性分配高于仓库所有已落地 migration 的编号，避免两个计划并行产生冲突。

---

## 5. Task 1：建立两个深 Module、领域模型与配置

**新建文件：**

- `backend/src/main/java/com/h/backend/chat/domain/memory/HarnessMemoryRuntime.java`
- `backend/src/main/java/com/h/backend/chat/domain/memory/UserMemoryCatalog.java`
- `backend/src/main/java/com/h/backend/chat/domain/memory/MemoryScope.java`
- `backend/src/main/java/com/h/backend/chat/domain/memory/MemoryScopeKind.java`
- `backend/src/main/java/com/h/backend/chat/domain/memory/MemoryExecutionIdentity.java`
- `backend/src/main/java/com/h/backend/chat/domain/memory/MemoryCategory.java`
- `backend/src/main/java/com/h/backend/chat/domain/memory/MemoryRecord.java`
- `backend/src/main/java/com/h/backend/chat/domain/memory/HarnessMemoryTurn.java`
- `backend/src/main/java/com/h/backend/chat/domain/memory/UserMemoryQuery.java`
- `backend/src/main/java/com/h/backend/chat/domain/memory/UserMemoryCommand.java`
- `backend/src/main/java/com/h/backend/chat/domain/memory/UserMemoryView.java`
- `backend/src/main/java/com/h/backend/chat/domain/memory/UserMemoryMutationResult.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/config/HarnessMemoryProperties.java`
- `backend/src/test/java/com/h/backend/chat/domain/memory/MemoryScopeTest.java`
- `backend/src/test/java/com/h/backend/chat/domain/memory/UserMemoryCommandTest.java`

**步骤：**

- [ ] 先写 value object/interface 测试：执行身份的 user/agent/task run 全部必填；scope 只能由认证 user + 已解析 agent + 已验证 task 构造，command/query 不接受 mem0 DTO、URL 或原始 entity id。
- [ ] interface 形状固定为 runtime `recall` 与 catalog `query/execute`，不把远端 CRUD 暴露成八个公共方法。
- [ ] 定义稳定领域异常：not-found、conflict、unavailable、invalid-content；具体冲突行为在 Task 3 的 Module contract 验证。
- [ ] 定义六类稳定枚举：`PERSONAL`、`PREFERENCE`、`PROJECT`、`DECISION`、`COMMITMENT`、`OTHER`。
- [ ] `MemoryExecutionIdentity` 包含 authenticatedUserId、stableAgentId、stableTaskSessionId、agentRunId、actorKind；构造时任何字段缺失均失败。
- [ ] `MemoryScope.user(identity)`、`agent(identity)`、`run(identity)` 是唯一 entity 映射入口；强制 `USER=(u,-,-)`、`AGENT=(u,a,-)`、`RUN=(u,a,r)`。
- [ ] `MemoryRecord` 包含 id、scopeKind、agentId、runId、content、category、source、createdAt、updatedAt、version；不向 domain 暴露带前缀的 mem0 entity id、metadata/DTO。
- [ ] 配置 `chat.harness.memory.provider=filesystem|shadow|mem0`，默认 `filesystem`。
- [ ] 配置 topK=8、threshold=0.25、maxChars=6000、connectTimeout=500ms、responseTimeout=1500ms、outbox maxAttempts=10、contractVersion/openapiSha256。
- [ ] `provider=mem0` 缺 base URL/API key/contractVersion/openapiSha256 时启动失败；`shadow` 缺配置只告警并关闭 shadow 调用。

**验证：**

```bash
cd backend
./mvnw -Dtest='*MemoryScopeTest,*UserMemoryCommandTest,*HarnessMemoryPropertiesTest' test
```

## 6. Task 2：实现固定版本的内部 `Mem0Gateway` seam

**新建文件：**

- `backend/src/main/java/com/h/backend/chat/infrastructure/memory/mem0/Mem0HttpClient.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/memory/mem0/Mem0OssDto.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/port/Mem0Gateway.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/memory/mem0/Mem0HttpGateway.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/memory/mem0/Mem0ExceptionMapper.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/config/HarnessMemoryBeanConfig.java`
- `backend/src/test/java/com/h/backend/chat/application/memory/port/FakeMem0Gateway.java`
- `backend/src/test/java/com/h/backend/chat/infrastructure/memory/mem0/Mem0HttpGatewayContractTest.java`
- `backend/src/test/resources/contracts/mem0/<pinned-version>/openapi.json`

**步骤：**

- [ ] 保存固定 image tag/git commit 对应的 OpenAPI fixture 和 SHA-256；启动/contract smoke 检查实际环境一致。
- [ ] 用 JDK `HttpServer` 写 fake Adapter contract test，避免为测试引入新的 mock HTTP 依赖。
- [ ] 覆盖 OSS 路径：add/list/get/update/delete `/memories`，search `/search`，history `/memories/{id}/history`。
- [ ] 验证每次调用带 `X-API-Key`，日志永不输出 key、memory 正文或完整请求体。
- [ ] 把 API key 作为单一后端服务凭证；测试不把 mem0 dashboard/API-key 身份误映射成 h-agent user owner。
- [ ] capture 用 `infer=true` 且只包含 `user` 与 `assistant` 消息；explicit save 用 `infer=false`。
- [ ] list/search 至少强制使用顶层 `user_id`；AGENT/RUN 逐级增加顶层 `agent_id/run_id`，`scope_kind` 使用固定 OSS contract 支持的 metadata filter。记录“按 id get/update/delete/history 无 user filter”这一真实 contract，禁止把远端 API key 误当应用用户授权。
- [ ] `Mem0OssDto` 严格按固定 `/openapi.json` 建模；禁止为了复用 Platform 示例把 entity ID 包进 `filters`。
- [ ] contract test 分别覆盖 `(u)`、`(u,a)`、`(u,a,r)` 写入和 AND 查询；同时证明 `(u)` 宽查询能列出该用户三个层级，行为不符则阻止切换而不是在 Adapter 猜兼容逻辑。
- [ ] `Mem0Gateway` 不提供 page/offset 或 expected-version 幻觉；只返回远端实际支持的 topK/result/hash/updatedAt。
- [ ] response timeout、429、5xx、非法 JSON、空 id 分别映射稳定领域错误。
- [ ] recall 对 timeout/429/5xx 由上层 fail-open；显式 mutation 不吞错误。
- [ ] 验证 add/update affected ids 和 metadata turn_key 可用于未知结果 reconciliation；若固定版本不支持，阻止 mem0 切换并先补项目侧 idempotency proxy/最小服务补丁。
- [ ] 验证 delete 后 get/search/history 不再包含正文；history 无法清除时阻止隐私删除能力上线。
- [ ] 接入连接池上限和超时；不实现 Mem0 Platform event polling 或 `/v1` 分支。

**验证：**

```bash
cd backend
./mvnw -Dtest='*Mem0HttpGatewayContractTest' test
```

## 7. Task 3：控制面 schema、本地索引、mutation saga 与显式 Memory Tools

**新建文件：**

- `backend/src/main/resources/db/migration/V<next>__create_harness_memory_tables.sql`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/HarnessMemoryRecordEntity.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/HarnessMemoryOperationEntity.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/HarnessMemoryRecordMapper.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/HarnessMemoryOperationMapper.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/DefaultUserMemoryCatalog.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryRecordIndex.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemorySearchSnapshotStore.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryScopePolicy.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryIntentPolicy.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/agentscope/Mem0MemoryTools.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/agentscope/HarnessMemoryTurnContext.java`
- `backend/src/test/java/com/h/backend/chat/application/memory/UserMemoryCatalogMutationTest.java`
- `backend/src/test/java/com/h/backend/chat/domain/memory/UserMemoryCatalogContractTest.java`
- `backend/src/test/java/com/h/backend/chat/application/memory/HarnessMemoryRecordIndexTest.java`
- `backend/src/test/java/com/h/backend/chat/application/memory/HarnessMemoryScopePolicyTest.java`
- `backend/src/test/java/com/h/backend/chat/infrastructure/persistence/HarnessMemoryRecordPersistenceTest.java`
- `backend/src/test/java/com/h/backend/chat/infrastructure/persistence/HarnessMemoryOperationPersistenceTest.java`
- `backend/src/test/java/com/h/backend/chat/infrastructure/agentscope/Mem0MemoryToolsTest.java`

**步骤：**

- [ ] 先分配当时下一个 Flyway 版本，一次创建 `harness_memory_records`、`harness_memory_operations`、`harness_memory_outbox`、`harness_memory_migrations` 及索引；所有控制表禁止正文列。
- [ ] `harness_memory_records` 增加 `scope_kind`、nullable `agent_id/run_id` 与 CHECK：USER 二者空、AGENT 仅 agent 非空、RUN 二者都非空；数据库和领域对象双重守住层级不变量。
- [ ] 先写控制索引 owner 测试：按 id 跨用户 get/history/update/delete 统一 404，调用不会到达 Mem0Gateway；同 user 内 agent/run 只是筛选维度，不替代 owner。
- [ ] `UserMemoryCatalogContractTest` 覆盖 list/detail/history/add/update/delete 的可观察结果，测试不穿透 Module interface。
- [ ] `UserMemoryCommand.Add` 的结构化 entries 由 `UserMemoryCatalog` 逐条调用 gateway add 并返回全部 ids；不能把多事实拼成一条，也不做模糊 NLP 分句。
- [ ] `UserMemoryCatalog` 把 mem0 raw categories 映射到六类稳定分类，未知值落到 `OTHER`；Gateway 不决定产品信息架构。
- [ ] `HarnessMemoryScopePolicy` 只接收完整 `MemoryExecutionIdentity` 和领域层级，返回规范 `MemoryScope`；不允许 controller/tool/middleware 自己拼 filter。
- [ ] 自动 capture 的 scope policy 是确定性规则：父 Agent 成功 turn -> USER，Worker 成功 turn -> 当前 RUN；AGENT 只由显式 `memory_save(AGENT, ...)` 或管理页面创建。未来自动 RUN -> AGENT 提升另设 promotion policy，不在 capture 时双写。
- [ ] 先写并发测试：两个相同 expected version 更新，只有一个在本地 CAS 预留 operation，另一个得到 conflict。
- [ ] 用 Redisson per-memory lock + PostgreSQL version CAS 包住 update/delete；显式 add 和自动 capture 使用规范 entity-scope lock。
- [ ] saga 状态至少包含 `STABLE|UPDATING|DELETING|RECONCILING|DELETED`；远端超时后不回滚版本、不盲重试。
- [ ] mutation 成功登记 remote hash/updatedAt；reconciliation 根据 operation id/turn key 收敛本地状态。
- [ ] 显式 ADD/UPDATE/DELETE 先用 operation key + request hash 在 `harness_memory_operations` 预留；同 key/同 hash 返回既有结果，同 key/异 hash 冲突。
- [ ] `UserMemoryMutationResult` 区分 `CONFIRMED|PENDING_RECONCILIATION|FAILED`；只有 confirmed 可返回成功话术。
- [ ] 普通列表由控制索引按 `(updated_at,memory_id)` opaque cursor 分页；scopeKind/agentId/runId/分类直接查询索引，agent/run 筛选先验证属于当前 user。
- [ ] 语义搜索先取 mem0 有序 ids/scores，再把 user+scopeKind+agentId+runId+queryHash+filter 的结果快照写 Redis，TTL 5 分钟，页面按 opaque cursor 读取；不伪装 mem0 支持 page/offset。
- [ ] 约定 mem0 只允许经本模块写入；如果其他系统绕过应用直写，项目侧 409 语义不再可保证。
- [ ] 定义 `HarnessMemoryTurnContext`：authenticatedUserId、stableAgentId、stableTaskSessionId、agentRunId、actorKind、input/assignment、explicit mutation intent；前三个 entity 来源字段以及 agentRunId 全部必填。
- [ ] `memory_search(query, limit, scopeKind?)` 只搜索当前执行身份可见的 USER/当前 AGENT/当前 RUN，返回 id、scope、内容、分类、更新时间、score。
- [ ] `memory_get(memoryId)` 只按记录 id 读取，不接受文件 path/line。
- [ ] `memory_save(scopeKind, memories[])` 的每个 item 带 content/category；调用方只能选择层级，Module 从执行身份补齐 entity ID，同步逐条 `infer=false`，拿到全部 id 后才返回成功。
- [ ] `memory_update(memoryId, expectedVersion, content, category?)` 和 `memory_delete(memoryId)` 只有 `HarnessMemoryIntentPolicy` 判定本轮用户明确要求时才执行。
- [ ] tool description 和 system guidance 明确：不能因模型自判“过期”而主动 update/delete。
- [ ] mutation 失败返回可理解但不泄露内部地址/响应体的错误；不得伪造“已记住”。

**验证：**

```bash
cd backend
./mvnw -Dtest='*UserMemoryCatalogContractTest,*UserMemoryCatalogMutationTest,*HarnessMemoryRecordIndexTest,*HarnessMemoryScopePolicyTest,*HarnessMemoryRecordPersistenceTest,*HarnessMemoryOperationPersistenceTest,*Mem0MemoryToolsTest' test
```

## 8. Task 4：修复 SDK emergency compaction，并按 provider 条件装配

**修改文件：**

- `backend/pom.xml`
- `backend/src/main/java/com/h/backend/chat/infrastructure/config/HarnessAgentConfig.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/subagent/AgentScopeSubagentRuntimeFactory.java`

**新建文件：**

- `backend/src/main/java/com/h/backend/chat/infrastructure/agentscope/HarnessSessionSearchTool.java`
- `backend/src/test/java/com/h/backend/chat/infrastructure/config/HarnessMemoryProviderCompositionTest.java`
- `backend/src/test/java/com/h/backend/chat/infrastructure/agentscope/HarnessCompactionMemoryIsolationTest.java`
- `backend/src/test/java/com/h/backend/chat/infrastructure/agentscope/HarnessSessionSearchToolTest.java`

**步骤：**

- [ ] 先写回归测试，强制触发正常 compaction 与 emergency compaction。
- [ ] 测试断言 `provider=mem0` 时均不创建/修改 `MEMORY.md` 或 daily ledger，同时 session offload 和 summary 仍发生。
- [ ] 升级 AgentScope，或引用包含最小修复的 artifact：`forceCompactAndRetry()` 必须继承 agent 已配置的 flush/offload flags。
- [ ] 如果 SDK 源码必须 fork，只改配置传递点并固定版本/commit；不要在本项目复制整套 memory pipeline。
- [ ] `filesystem`：保留 `.memory(legacyMemoryConfig)`、旧 tools/hooks 和 flush-before-compact。
- [ ] `shadow`：保留旧服务链路，增加 mem0 outbox/recall compare，但不向模型注入 mem0。
- [ ] `mem0`：调用 `disableMemoryTools()`、`disableMemoryHooks()`，注册 Mem0 tools/middleware，设置 `flushBeforeCompact(false)`、`offloadBeforeCompact(true)`。
- [ ] provider 是部署实例级不可变配置；测试确认没有在同一单例 HarnessAgent 内按 user hash 动态切换 hooks/tools。
- [ ] 因 `disableMemoryTools()` 会移除 SDK `session_search`，用独立 `HarnessSessionSearchTool` 恢复原会话检索能力。
- [ ] 保持 USER subagent 的 `disableMemoryTools()` / `disableMemoryHooks()`，只关闭 AgentScope Markdown memory；确认项目自有 Mem0 recall/capture 通过统一执行上下文装配，不因这两个开关被误关。
- [ ] 暂不删除 legacy memory prompts/bean；把删除放到 30 天退役任务。

**硬门槛：** emergency compaction 回归测试未通过前，禁止启用 `provider=mem0`。

**验证：**

```bash
cd backend
./mvnw -Dtest='*HarnessMemoryProviderCompositionTest,*HarnessCompactionMemoryIsolationTest,*HarnessSessionSearchToolTest' test
```

## 9. Task 5：实现每 scope 每 turn 一次的 transient recall

**修改文件：**

- `backend/src/main/java/com/h/backend/chat/domain/agent/HarnessAgentExecutor.java`
- `backend/src/main/java/com/h/backend/chat/domain/agent/HarnessSubagentLifecycleMiddleware.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/subagent/AgentScopeSubagentRuntimeFactory.java`

**新建文件：**

- `backend/src/main/java/com/h/backend/chat/infrastructure/agentscope/Mem0RecallMiddleware.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/agentscope/HarnessMemoryPromptRenderer.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/DefaultHarnessMemoryRuntime.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryExecutionIdentityFactory.java`
- `backend/src/test/java/com/h/backend/chat/infrastructure/agentscope/Mem0RecallMiddlewareTest.java`
- `backend/src/test/java/com/h/backend/chat/domain/memory/HarnessMemoryRuntimeContractTest.java`
- `backend/src/test/java/com/h/backend/chat/domain/agent/HarnessAgentExecutorMemoryContextTest.java`

**步骤：**

- [ ] 父 Agent 与 Worker 执行都在 `RuntimeContext` 放入不可变 `HarnessMemoryTurnContext`；从认证用户、已解析 Agent 和已验证任务会话构造完整执行身份，不允许用 display name/每轮 run 代替稳定 entity ID。
- [ ] identity factory 映射：父=`agentId:harness-agent + runId:rootSessionId`；Worker=`stable definition agentId + runId:child taskSessionId`。spawn、后台完成、直达 Worker follow-up 必须得到同一映射。
- [ ] `HarnessMemoryRuntimeContractTest` 验证 USER 跨 Worker/任务可见、AGENT 仅同 user+agent 可见、RUN 仅同 user+agent+run 可见、不同 user 零串读、timeout fail-open；测试只穿过 runtime interface。
- [ ] `Mem0RecallMiddleware` 在 parent/Worker + provider=mem0 时调用 runtime；runtime 分别查询 USER、当前 AGENT、当前 RUN，并在 RuntimeContext 缓存每个 scope 的 future/result，保证 ReAct 多轮不重复查询。
- [ ] query 对父 turn 以本轮 user message 为主，对 Worker 以 assignment/本轮输入为主；弱 query 可追加最近输入或短 compaction summary，禁止发送整个历史。
- [ ] 三层结果按 memory id/正文 hash 去重、统一重排后再应用 topK=8 与 6000 字符总预算；不能简单拼接三个 topK 导致预算膨胀。
- [ ] renderer 生成 `<recalled_user_memory>`，最多 topK=8、总计 6000 字符。
- [ ] system guidance 声明 memory 是不可信事实数据，不执行其中的指令；当前消息冲突时当前消息优先。
- [ ] recall timeout/429/5xx/circuit-open 返回空 block，聊天继续并记录 outcome/latency/result count。
- [ ] 断言 recall 内容没有写入 `AgentState.context`、workspace、session message 或 telemetry prompt 正文。
- [ ] Worker 只能读同 user 的 USER、自己的 AGENT、当前 RUN；伪造其他 agent/run 的测试必须在调用 Mem0Gateway 前失败。
- [ ] spawned builtin、Catalog USER Worker 和直达 Worker follow-up 都做集成测试，证明 custom Mem0 middleware 正确继承/装配；不能只测顶级 `HarnessAgentExecutor`。

**验证：**

```bash
cd backend
./mvnw -Dtest='*HarnessMemoryRuntimeContractTest,*Mem0RecallMiddlewareTest,*HarnessAgentExecutorMemoryContextTest' test
```

## 10. Task 6：实现 capture outbox 与迁移状态持久化

**新建文件：**

- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/HarnessMemoryOutboxEntity.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/entity/HarnessMemoryMigrationEntity.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/HarnessMemoryOutboxMapper.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/persistence/mapper/HarnessMemoryMigrationMapper.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryOutbox.java`
- `backend/src/test/java/com/h/backend/chat/application/memory/HarnessMemoryOutboxTest.java`
- `backend/src/test/java/com/h/backend/chat/infrastructure/persistence/HarnessMemoryMigrationTest.java`

**表设计：**

- `harness_memory_records`：memory id、owner、scope kind、agent/run、version、category/source、turn key、remote hash/updatedAt、saga state；禁止正文列。
- `harness_memory_operations`：稳定 operation key、request hash、owner、scope kind、agent/run、type/target、status、remote ids、时间；禁止正文列，并使用与 records 相同的 scope CHECK。
- `harness_memory_outbox`：唯一 `turn_key`、request hash、user、logical agent、task session、本地 agent run、输入/final message id、status/lease、attempts、remote ids、时间；禁止正文列。
- `harness_memory_migrations`：唯一 `(user_id, legacy_hash)`、source path/heading、mem0 ids、status、error code、created/completed 时间。
- 必要索引：`records(user_id,updated_at,memory_id)`、`records(user_id,scope_kind,agent_id,run_id,updated_at)`、`records(user_id,category,updated_at)`、`records(source_turn_key)`、`operations(user_id,status)`、`operations(completed_at)`、`outbox(status,next_attempt_at)`、`outbox(completed_at)`、`migration(status,user_id)`。

**步骤：**

- [ ] 先写 outbox/migration mapper 集成测试；复用 Task 3 已创建的 schema，不改写已经提交的 migration。
- [ ] outbox 状态：`PENDING`、`PROCESSING`、`RECONCILING`、`COMPLETED`、`DEAD_LETTER`。
- [ ] `turn_key={userId}:{logicalAgentId}:{taskSessionId}:{agentRunId}:long-term-memory-capture:v2` 建唯一约束。
- [ ] 多实例 worker 使用 `FOR UPDATE SKIP LOCKED` claim，并记录 processing lease；过期 lease 可恢复。
- [ ] outbox 只保存 actorKind、完整执行身份和已持久化 input/final message id；worker 读取权威正文，严禁在 outbox 复制 payload 正文。
- [ ] 明确收到并登记 affected memory ids 后标记 completed；仅非正文审计保存 30 天。
- [ ] 最大重试后进入 dead-letter 并告警；不能阻塞或修改已完成聊天结果。
- [ ] migration 表不永久复制 legacy 原文，只存 hash、来源定位、目标 ids 与状态；导入成功同时登记 records owner/version。

**验证：**

```bash
cd backend
./mvnw -Dtest='*HarnessMemoryOutboxTest,*HarnessMemoryMigrationTest' test
```

## 11. Task 7：父/Worker turn 完成事务、scope 路由、远端幂等 worker 与清理任务

**修改文件：**

- `backend/src/main/java/com/h/backend/chat/domain/agent/ChatAgentExecutionCommand.java`
- `backend/src/main/java/com/h/backend/chat/domain/agent/HarnessAgentExecutor.java`
- `backend/src/main/java/com/h/backend/chat/application/ChatSessionService.java`
- `backend/src/main/java/com/h/backend/chat/application/impl/ChatSessionServiceImpl.java`
- `backend/src/main/java/com/h/backend/chat/application/AgentRunService.java`
- `backend/src/main/java/com/h/backend/chat/application/impl/AgentRunServiceImpl.java`
- `backend/src/main/java/com/h/backend/chat/domain/agent/HarnessSubagentLifecycleMiddleware.java`
- `backend/src/main/java/com/h/backend/chat/application/impl/HarnessCollaborationServiceImpl.java`

**新建文件：**

- `backend/src/main/java/com/h/backend/chat/application/HarnessTurnCompletion.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryCaptureWorker.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryCaptureReconciler.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryIndexReconciler.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryOutboxCleanupJob.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryMetrics.java`
- `backend/src/test/java/com/h/backend/chat/application/HarnessTurnCompletionTest.java`
- `backend/src/test/java/com/h/backend/chat/application/memory/HarnessMemoryCaptureWorkerTest.java`

**步骤：**

- [ ] 先写父/Worker 事务测试：final message/result、agent run success、capture outbox 要么一起提交，要么一起回滚。
- [ ] `ChatAgentExecutionCommand` 显式携带本轮已持久化 `userMessageId`，不得让 worker 按文本或时间猜用户消息。
- [ ] `HarnessAgentExecutor` 在 parent/Worker `AgentEndEvent` 后调用 `completeTurn(...)`；error/cancel/无 final result 均不创建 capture outbox。
- [ ] spawned/background Worker 的 final 由 `HarnessSubagentLifecycleMiddleware -> HarnessCollaborationService` 进入同一个 `HarnessTurnCompletion`，把子会话结果持久化、run success 和 outbox 原子提交；不得只覆盖直达 Worker follow-up。
- [ ] completion command 携带完整 `MemoryExecutionIdentity` 和已持久化 input/assignment message id；事务保存 final、完成 run、以 turn key 幂等插入 outbox。
- [ ] 不新增把 Mem0 HTTP 细节泄漏给 Agent 的 capture interface；executor/completion 只提交执行身份和消息引用，scope policy/worker 负责内部路由。
- [ ] worker claim 后由 `HarnessMemoryScopePolicy` 按 actorKind 确定父->USER、Worker->RUN，获取对应 entity-scope lock，读取权威输入/final，再调用 Mem0 `infer=true`，避免与页面 edit/delete 竞争。
- [ ] 指数退避 + jitter；区分 retryable 429/5xx/timeout 与不可重试 4xx/schema error。
- [ ] 请求携带稳定 turn_key/request hash；远端明确返回 affected ids 后，同事务登记 control index 并 completed。
- [ ] timeout、断连或进程在远端成功后崩溃时标记 `RECONCILING`；先按目标 user/agent/run scope + turn_key 查远端并登记结果，禁止盲目再次 add。
- [ ] index reconciler 按本地 cursor 小批读取 records、限流核验 remote hash/existence，修复自动 inference update/delete 造成的状态漂移；不依赖远端全量分页。
- [ ] contract 不能可靠按 turn_key 核验所有 ADD/UPDATE 结果时，阻止切换并先提供 idempotency proxy/最小服务补丁。
- [ ] `shadow` 写 mem0 但不影响旧主链路；`mem0` 是正式 capture。
- [ ] 清理任务删除 completed 超过 30 天的非正文审计，不自动删除 dead-letter/reconciling。
- [ ] 指标至少覆盖 recall、capture、tool mutation、reconciliation、operation pending、index drift、outbox pending/oldest age、latency、circuit state。
- [ ] 日志只记录 user hash、turn key hash、status、latency、result count 和安全错误码。

**验证：**

```bash
cd backend
./mvnw -Dtest='*HarnessTurnCompletionTest,*HarnessMemoryCaptureWorkerTest' test
```

## 12. Task 8：部署级 provider、shadow 对比与功能开关

**新建文件：**

- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryModeGuard.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryShadowComparator.java`
- `backend/src/test/java/com/h/backend/chat/application/memory/HarnessMemoryModeGuardTest.java`

**修改文件：**

- `backend/src/main/resources/application.yml`
- 对应环境配置模板/说明文件（只加入变量名和安全默认值，不加入真实 secret）

**步骤：**

- [ ] provider 是部署实例级配置，客户端和 user hash 都不能选择后端。
- [ ] `filesystem` 不依赖 mem0 可启动。
- [ ] `shadow` 下 mem0 recall 只记录命中数量、离线样本 id 和耗时，不向模型注入正文。
- [ ] 切到 `mem0` 的应用池完整使用 mem0 recall/capture/management，legacy 文件全局只读。
- [ ] 配置不在 turn 中途热切换；变更通过新实例滚动发布。
- [ ] 若生产要求 canary，由 filesystem/shadow 与 mem0 两个应用池配合 sticky user routing；不改造单例 AgentDefinition 为运行时 provider。
- [ ] 正式 mem0 写入后，回滚版本必须仍理解 mem0 contract；禁止自动反向导出为 Markdown。
- [ ] 配置日志不打印 API key，Actuator/config dump 不暴露 secret 值。

**验证：**

```bash
cd backend
./mvnw -Dtest='*HarnessMemoryModeGuardTest,*HarnessMemoryProviderCompositionTest' test
```

## 13. Task 9：实现 legacy Markdown 在线幂等迁移工具

**新建文件：**

- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessLegacyMemoryMigrator.java`
- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessLegacyMemoryParser.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/cli/HarnessMemoryMigrationRunner.java`
- `backend/src/test/java/com/h/backend/chat/application/memory/HarnessLegacyMemoryParserTest.java`
- `backend/src/test/java/com/h/backend/chat/application/memory/HarnessLegacyMemoryMigratorTest.java`

**步骤：**

- [ ] 先写 heading + bullet/paragraph block parser 测试，空 block、超长 block、重复 block、中文内容都覆盖。
- [ ] 枚举已有 Harness user，读取 `[agents,harness-agent,users,{userId},root]/MEMORY.md`。
- [ ] legacy Markdown 没有可靠 Worker/任务归属，所有导入记录固定为 `USER` scope；禁止根据标题或文本猜 agent/run。
- [ ] 默认只导入整理后的 `MEMORY.md`；只有用户没有该文件时，才候选最近 90 天 daily ledger，并先去重。
- [ ] 每个 block 规范化后计算 `SHA-256(userId + normalizedBlock)`；source path 单独保存，避免 ledger fallback 中相同事实因日期路径不同而重复导入。
- [ ] dry-run 只输出用户数、block 数、空/超长/重复数和 hash，不输出完整敏感正文。
- [ ] import 使用 `infer=false`，只传 `user_id`，metadata 标记 `scope_kind=USER`、`legacy_migration`、heading、legacy hash。
- [ ] `(userId,legacyHash)` 保证重复运行不重复写；部分失败可从 migration 表续跑。
- [ ] 保存 mem0 ids 并登记 control index owner/version 后再标 completed；无法确认外部成功时进入 reconciliation，不能盲重试。
- [ ] 提供按 user 范围/批次运行参数，避免一次迁移锁住全量用户；不要求停机。
- [ ] 抽样工具验证原文映射、中文语义 recall、跨 session recall 和跨用户零命中。

**验证：**

```bash
cd backend
./mvnw -Dtest='*HarnessLegacyMemoryParserTest,*HarnessLegacyMemoryMigratorTest' test
```

## 14. Task 10：实现 `/api/me/memories` 管理 API

**新建文件：**

- `backend/src/main/java/com/h/backend/chat/interfaces/web/MeMemoryController.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/web/HarnessMemoryExceptionAdvisor.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/MemoryRecordDto.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/MemoryCursorPageDto.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/MemoryHistoryEntryDto.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/CreateMemoryRequest.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/UpdateMemoryRequest.java`
- `backend/src/test/java/com/h/backend/chat/interfaces/web/MeMemoryControllerTest.java`

**API 契约：**

```text
GET    /api/me/memories?scopeKind=&agentId=&runId=&category=&query=&cursor=&limit=
POST   /api/me/memories             body: scopeKind, agentId?, runId?, entries
GET    /api/me/memories/{memoryId}
GET    /api/me/memories/{memoryId}/history
PUT    /api/me/memories/{memoryId}   body: content, category, expectedVersion
DELETE /api/me/memories/{memoryId}
```

**步骤：**

- [ ] controller 所有 owner 从 `AuthUserPrincipal.userId()` 取得，不接受 userId query/body。
- [ ] `scopeKind` 缺省表示该用户全部层级；AGENT 必须带 agentId，RUN 必须带 agentId+runId，后端先验证 stable agent/task 归属再映射，非法组合返回 400，跨 owner 统一 404。
- [ ] 无 query 的 list 从 control index 按 `(updatedAt,memoryId)` cursor 分页，可按 scope/agent/run/category 筛选，再按 ids 从 mem0 读取正文。
- [ ] 有 query 的 list 创建/读取包含 scope/agent/run 的 Redis 有序搜索快照，返回 opaque nextCursor；限制最多结果、limit 和 query/content 长度。
- [ ] create 走 explicit `infer=false`；USER 携带 agent/run、AGENT 携带 run 一律返回 400，AGENT 只接受已验证 agent，RUN 只接受已验证 agent+task；如果拆成多条，返回 records 数组，不伪装单 id。
- [ ] get/history/update/delete 都先查 control index ownership；跨 owner 与不存在统一返回 404，再调用无 user filter 的 mem0 id endpoint。
- [ ] expected version 由 control index CAS；冲突返回 HTTP 409 和安全错误码，远端 mutation 走 saga/reconciliation。
- [ ] mem0 unavailable 对查询/显式 mutation 返回 503，不泄露 endpoint、API key 或原始 body。
- [ ] mutation 加用户级速率限制；记录 scope 创建后不可通过普通 update 改变，跨 scope 移动必须显式执行“新 scope add 成功 -> 旧记录 delete”的 saga；删除是 mem0 hard delete，本地审计不含 content。
- [ ] `filesystem` 模式返回功能未启用；`shadow` 默认只读核验；`mem0` 开放完整 CRUD。

**验证：**

```bash
cd backend
./mvnw -Dtest='*MeMemoryControllerTest' test
```

## 15. Task 11：持久化显式 mutation 的聊天结果卡片

**修改文件：**

- `backend/src/main/java/com/h/backend/chat/domain/model/ChatMessagePayload.java`
- `backend/src/main/java/com/h/backend/chat/application/ChatSessionService.java`
- `backend/src/main/java/com/h/backend/chat/application/impl/ChatSessionServiceImpl.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/ChatMessagePayloadDto.java`
- `backend/src/main/java/com/h/backend/chat/domain/agent/HarnessAgentExecutor.java`
- `backend/src/main/java/com/h/backend/chat/interfaces/dto/ChatStreamEvent.java`

**新建文件：**

- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryOperationProjector.java`
- `backend/src/main/java/com/h/backend/chat/infrastructure/agentscope/HarnessMemoryResultSink.java`
- `backend/src/test/java/com/h/backend/chat/application/memory/HarnessMemoryOperationProjectorTest.java`

**步骤：**

- [ ] 新增持久化消息类型 `MEMORY`；payload 包含 operation=`ADD|UPDATE|DELETE`、scopeKind、agent/task 展示引用、category、summary、memoryId。
- [ ] memoryId 仅用于“查看详情”寻址，不在卡片正文显示；后端详情接口仍必须 owner 校验。
- [ ] `ChatSessionService.appendMemoryOperationMessage(...)` 保存消息，保证刷新会话后卡片仍存在。
- [ ] `HarnessAgentExecutor` 在 RuntimeContext 注入 call-scoped `HarnessMemoryResultSink`；tool 成功后由 projector 先持久化，再向当前 SSE 发 `memory` event。
- [ ] 显式 mutation 使用每轮本地 `agentRunId + toolCallId + itemIndex` 稳定 operation key；重试先从 control index/operation state 取既有结果，不能重复 add/update/delete；不得误用稳定任务 `run_id` 导致同一任务后续 turn 冲突。
- [ ] 只有 operation `CONFIRMED` 才持久化成功卡片；pending reconciliation 明确告诉模型“尚未确认”，后续确认成功时幂等补写卡片。
- [ ] SSE 断开不回滚已经成功的 mem0 mutation 或持久化卡片。
- [ ] 只有用户明确触发的 add/update/delete 产生卡片；自动 capture 和 migration 永远静默。
- [ ] 卡片显示产品化的用户/Worker/任务层级、操作、分类、短摘要和查看详情入口，不展示带前缀的 mem0 entity ID、API key、原始外部响应。

**验证：**

```bash
cd backend
./mvnw -Dtest='*HarnessMemoryOperationProjectorTest,*ChatSessionServiceImplTest' test
```

## 16. Task 12：实现 `/me/memory` 页面

**新建文件：**

- `frontend/app/me/memory/page.tsx`
- `frontend/lib/harness-memory.ts`
- `frontend/lib/harness-memory.test.mjs`

**修改文件：**

- `frontend/app/me/page.tsx`
- `frontend/app/chat/page.tsx`
- `frontend/lib/chat-message-state.ts`
- `frontend/lib/chat-message-state.test.mjs`

**步骤：**

- [ ] 先写 API client/状态测试：opaque cursor 分页、搜索快照 cursor、分类映射、search debounce、409、404、503。
- [ ] `/me` 增加“记忆”入口；沿用现有窄版容器、颜色、按钮与移动端布局。
- [ ] 页面提供逐条列表、USER/AGENT/RUN 层级、Worker/任务、六类 filter、语义 search、分页、手动新增、单条编辑、删除确认。
- [ ] detail drawer/modal 显示产品化 scope、Worker/任务、内容、分类、来源、创建/更新时间与 history。
- [ ] 编辑提交 expectedVersion；409 时保留用户草稿，展示“记录已变化”，并提供加载最新内容。
- [ ] 删除必须二次确认；成功后移出列表，失败恢复 UI。
- [ ] 不实现 Markdown 整篇编辑器，不显示带前缀的 mem0 entity ID、API key 或内部错误体。
- [ ] chat state 支持 `MEMORY` 持久消息和实时 `memory` event，显式操作渲染轻量结果卡片。
- [ ] 自动 capture 不产生任何 toast/card；只在管理页后续查询时体现。
- [ ] 页面 loading、empty、error、disabled-provider 与移动端状态完整。

**验证：**

```bash
cd frontend
npm test
npm run lint
npm run build
```

## 17. Task 13：账号注销清理 seam

**新建文件：**

- `backend/src/main/java/com/h/backend/chat/application/memory/HarnessMemoryErasure.java`
- `backend/src/test/java/com/h/backend/chat/application/memory/HarnessMemoryErasureTest.java`

**步骤：**

- [ ] 按 control index + user scope 分页硬删除全部 mem0 records，直到 get/search/history 和索引复查均为空。
- [ ] 同时删除该 user 的 pending outbox message 引用、搜索快照、records/operations control data 与 migration 定位数据；审计不保留正文。
- [ ] 固定 mem0 contract 无法物理清除 history 正文时，本任务判定失败，不把向量删除等同于隐私删除。
- [ ] 过程可重跑，部分失败返回明确失败清单，不把“请求已发出”当作删除完成。
- [ ] 当前仓库没有账号注销 use case，因此本任务只提供应用 Module 与 contract test，不擅自增加账号删除 API。
- [ ] 将该 Module 标记为未来账号注销事务/编排的强制 hook；账号注销功能落地时，未成功清空 mem0 不得报告注销完成。

**验证：**

```bash
cd backend
./mvnw -Dtest='*HarnessMemoryErasureTest' test
```

## 18. Task 14：全链路回归、真实 contract smoke 与安全检查

**新建/修改测试：**

- `backend/src/test/java/com/h/backend/chat/integration/HarnessMem0MemoryIntegrationTest.java`
- `backend/src/test/java/com/h/backend/chat/integration/HarnessMem0IsolationTest.java`
- `backend/src/test/java/com/h/backend/chat/integration/HarnessMem0FailureRecoveryTest.java`
- 现有 Harness、subagent、chat session、普通 Agent memory 回归测试

**步骤：**

- [ ] 固定 OpenAPI + fake server 全链路：显式 save -> get -> search -> update -> history -> delete；不得伪造分页/CAS/owner filter。
- [ ] 真实 mem0 contract test 用环境变量显式启用；shadow/mem0 切换前必须通过，不在测试代码放凭证。
- [ ] 两 user、多 Worker、多任务并发 add/search/get/update/delete：USER 可跨 Worker/任务召回，AGENT 不能跨 Worker，RUN 不能跨任务，不同 user 零串读且跨 owner 404。
- [ ] mem0 timeout/429/5xx：recall 空结果继续；outbox 重试追平；显式 mutation 明确失败。
- [ ] 父 completion 事务故障注入：assistant/run/outbox 原子提交，无“聊天成功但未入队”窗口。
- [ ] worker 多实例 claim、stale lease、重复 turn key、远端成功后本地崩溃 reconciliation、30 天 cleanup。
- [ ] 表结构和日志扫描确认 control index/outbox/operation audit 不保存 memory/user/assistant 正文。
- [ ] 普通 cursor pagination、scope/agent/run/category 索引、语义搜索 Redis 快照和 409 mutation saga 回归通过。
- [ ] parent/Worker 并发回归；两者按可见层级 recall/capture，Worker 不能读取其他 Worker/任务记忆。
- [ ] 正常/emergency compaction 均不写 Markdown，session offload 保持。
- [ ] AgentState 恢复、Skills、plans/tasks/artifacts、subagents、普通 Agent Redis chat memory 回归通过。
- [ ] prompt-injection 样本：memory 中的“忽略指令”等文本只能作为事实数据，不能覆盖 system/current user。
- [ ] 日志扫描不出现 API key、memory 正文或完整 user prompt。

**完整验证：**

```bash
cd backend
./mvnw test

cd ../frontend
npm test
npm run lint
npm run build
```

## 19. Task 15：灰度、迁移与退役执行

本任务只描述项目内开关和验证动作，不包含 mem0 Server 部署步骤。

### 19.1 Baseline

- [ ] 建立旧系统 recall/capture 质量样本与业务高峰基线。
- [ ] 固定一组中文事实、偏好、决策、冲突更新、弱 query 和 prompt-injection 样本。

### 19.2 Shadow

- [ ] 开启 `shadow`：旧 Markdown 服务父模型，成功父/Worker turn 进入 mem0 outbox；Worker 的 shadow recall/capture 只做离线对比，不影响旧链路。
- [ ] mem0 search 只做离线对比，不注入模型、不影响聊天。
- [ ] 验证 oldest outbox、capture success、p95 latency、结果数量和错误码。

### 19.3 Migrate

- [ ] 先 dry-run，再按 user 批次 import `MEMORY.md`。
- [ ] 对无 `MEMORY.md` 的用户单独审批 daily ledger 候选导入。
- [ ] 重跑同一批次，确认新增记录数为零。
- [ ] 抽样核对 legacy block -> mem0 ids、中文 recall 和跨 session recall。

### 19.4 Contract Gate 与部署级 Switch

- [ ] 固定版本真实 contract 通过 search 顶层 entity 字段、`(u)/(u,a)/(u,a,r)` 写入与 AND 查询、user 宽查询、scope_kind filter、owner 控制、cursor/搜索快照分页、CAS saga、turn_key reconciliation 和 history purge。
- [ ] 新应用池以 `provider=mem0` 启动，旧应用池不接收同一用户的新 turn；通过 sticky routing 防止同一 user 混用真相源。
- [ ] 若不需要基础设施 canary，直接在维护窗口全局切换；切换后 legacy 立即只读。
- [ ] 观察至少一个业务高峰，记录版本/OpenAPI hash、阈值、样本结论、告警和是否继续。

### 19.5 进入 100% 的硬门槛

- [ ] 用户隔离测试零串读；AGENT 跨 Worker、RUN 跨任务均零串读。
- [ ] 按 id endpoint 的 owner 由 control index 强制验证，跨 owner 零远端调用。
- [ ] 迁移可重跑且抽样一致。
- [ ] 离线召回质量不弱于旧实现。
- [ ] recall p95 < 1.5 秒。
- [ ] capture 成功率 >= 99.9%。
- [ ] outbox 最老积压 < 5 分钟。
- [ ] 正常与 emergency compaction 零 Markdown 写入。
- [ ] 父/Worker turn completion 原子提交；远端未知结果可按目标 entity scope reconciliation 收敛且无重复事实样本。
- [ ] delete/账号擦除验证 history 正文不可再读取。

阈值可根据真实环境调整，但必须记录数据和理由，不能取消量化门槛。

### 19.6 Retire

- [ ] 100% 后旧 `MEMORY.md` / daily ledger 只读保留 30 天。
- [ ] 30 天内没有数据回滚到 filesystem；故障由 recall fail-open 和 outbox 恢复处理。
- [ ] 观察期结束后删除或按既有备份策略归档旧数据。
- [ ] 删除 legacy `harnessMemoryConfig`、flush/consolidation prompt、旧 memory tools/hooks 断言和 `filesystem/shadow` 分支。
- [ ] 保留独立 `session_search`、session offload、compaction summary 与 AgentState。

## 20. 验收清单

- [ ] 父 Agent 与 Worker 在新 session/task 能按 USER/AGENT/RUN 可见性语义召回已保存事实。
- [ ] 同一 turn 每个可见 scope 最多一次 recall，三层合并结果只存在 transient context。
- [ ] 成功父/Worker turn 的 final/run/outbox 原子提交；失败、取消不产生 outbox。
- [ ] 显式“记住”选择 USER/AGENT/RUN 后同步精确写入，失败不报告成功。
- [ ] 用户明确 update/delete 才允许 tool mutation，并产生持久化结果卡片。
- [ ] 不同用户、不同 Worker、不同任务并发执行零串读、零串写。
- [ ] 管理页通过 control index + Redis 搜索快照支持 scope/Worker/任务/分类、语义搜索、cursor 分页、CRUD、详情/history 和 409。
- [ ] recall 故障不阻断聊天；capture 可重试追平；显式失败可见。
- [ ] control index/outbox 不保存正文；远端未知结果进入 reconciliation，完成审计 30 天清理。
- [ ] 迁移 dry-run/import 可重跑、可核验，只在无 MEMORY.md 时考虑 ledger。
- [ ] 正常与 emergency compaction 都不再写 Markdown，session offload 仍有效。
- [ ] AgentState、Skills、subagents、普通 Agent chat memory 无行为变化。
- [ ] mem0 是 100% 切换后的正文/语义真相源，PostgreSQL 仅保存无正文控制事实；旧文件观察 30 天后退役。
- [ ] mem0 版本/OpenAPI 固定，按 id owner、分页、CAS、远端幂等和 history purge 的真实 contract 已验证。

## 21. 不可接受的实现捷径

- 不直接从 controller、middleware 或 tool 拼 mem0 URL/JSON；统一经过 Adapter。
- 不把整段 Markdown 作为一条 memory，也不做整篇 Markdown 编辑保存。
- 不把 agent/run 仅当普通 metadata；AGENT/RUN 必须分别写入 mem0 `agent_id`/`run_id` 并参与 AND filter。
- 不要求每条 memory 都写满三个 entity 字段；执行上下文三者必填，但 USER/AGENT/RUN 分别落一、二、三个字段。
- 不使用 display name、每轮 agentRunId、toolCallId 作为稳定 mem0 `agent_id`/`run_id`。
- 不在 controller、tool、middleware 手写 USER/AGENT/RUN filter；统一由 `MemoryScope` / `HarnessMemoryScopePolicy` 生成。
- 不把 recall 结果写回 AgentState 或会话历史。
- 不把 reasoning、工具日志、system prompt 或完整历史送入自动 capture。
- 不因 mem0 暂时不可用而静默回写 Markdown。
- 不靠 memory id 单独授权；所有读取/更新/删除都做 user scope ownership check。
- 不从 mem0 按 id endpoint 推断 owner；必须先查不含正文的 control index。
- 不声称 mem0 OSS 原生支持 page/offset 或 expected-version；分页和 CAS 由本地控制能力实现。
- 不把本地唯一 turn key 描述成远端 exactly-once；未知结果必须先 reconciliation。
- 不让 assistant message、run success 与 capture outbox 分属三个非原子提交点。
- 不把 200 HTTP 之外的模糊响应当作显式写入成功；必须得到有效 memory id/record。
- 不在 SDK emergency compaction 硬编码尚未修复时开启 mem0 模式。
- 不允许外部业务绕过本模块写 mem0，否则 expected-version 并发语义失效。
- 不使用浮动 mem0 `latest`，也不在 OpenAPI/真实 contract 不匹配时启动 shadow/mem0。
