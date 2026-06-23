# 领域 Agent 页面与运行时路由 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 构建领域 Agent 专属问答页、Agent 管理/编排详情页，并让后端通过 `agentId` 路由到普通聊天或 LangChain4j agentic 编排。

**架构：** 后端增加薄 `AgentRegistry` 保存产品层元数据，拓扑由真实 LangChain4j `AgentInstance` 生成 `AgentTopologyDto`。聊天 SSE 保持统一入口，普通聊天继续 token streaming，领域 Agent 使用 `AgentListener` 通过跨线程 `AgentStepEventBridge` 发送结构化步骤事件，最终回复作为 `chunk` 发出。

**技术栈：** Spring Boot、MyBatis Plus、Flyway、LangChain4j Agentic、Reactor SSE、Next.js App Router、React、Tailwind CSS、Node test runner。

---

## 文件结构

后端新增：

- `backend/src/main/resources/db/migration/V10__add_agent_id_to_chat_sessions.sql`：给会话表增加 `agent_id`。
- `backend/src/main/java/com/h/backend/chat/agent/AgentRuntimeType.java`：领域 Agent 运行时类型枚举。
- `backend/src/main/java/com/h/backend/chat/agent/AgentDefinition.java`：Registry 内部定义。
- `backend/src/main/java/com/h/backend/chat/agent/AgentRegistry.java`：Agent 产品目录和运行时 Bean 查找。
- `backend/src/main/java/com/h/backend/chat/agent/AgentTopologyMapper.java`：`AgentInstance` 到 DTO 的映射。
- `backend/src/main/java/com/h/backend/chat/agent/AgentStepEventBridge.java`：按 memoryId 路由跨线程步骤事件。
- `backend/src/main/java/com/h/backend/chat/agent/AgentStepListener.java`：LangChain4j `AgentListener` 适配 SSE 步骤事件。
- `backend/src/main/java/com/h/backend/chat/agent/AgenticSyncExecutor.java`：执行同步 agentic Agent 并输出 SSE。
- `backend/src/main/java/com/h/backend/chat/agent/HAssistantStreamingExecutor.java`：承接现有普通聊天 streaming 逻辑。
- `backend/src/main/java/com/h/backend/chat/agent/ChatAgentExecutor.java`：执行器接口。
- `backend/src/main/java/com/h/backend/chat/agent/ChatAgentExecutionCommand.java`：执行器命令对象。
- `backend/src/main/java/com/h/backend/chat/controller/AgentController.java`：Agent 列表和拓扑接口。
- `backend/src/main/java/com/h/backend/chat/dto/AgentSummaryDto.java`：Agent 卡片 DTO。
- `backend/src/main/java/com/h/backend/chat/dto/AgentTopologyDto.java`：拓扑根 DTO。
- `backend/src/main/java/com/h/backend/chat/dto/AgentTopologyNodeDto.java`：拓扑节点 DTO。
- `backend/src/main/java/com/h/backend/chat/dto/AgentStepPayloadDto.java`：步骤事件 payload。
- `backend/src/main/java/com/h/backend/chat/dto/LoopMetaDto.java`：循环节点元数据。
- `backend/src/main/java/com/h/backend/chat/dto/StateKeyDto.java`：状态 key 图例。

后端修改：

- `backend/src/main/java/com/h/backend/chat/config/AgentConfig.java`：给 `CarRentalAssistant` 及子 Agent 挂接 `AgentStepListener`，并清理重复 chat memory provider。
- `backend/src/main/java/com/h/backend/chat/controller/ChatController.java`：向 `ChatService` 传递 `agentId`。
- `backend/src/main/java/com/h/backend/chat/controller/ChatSessionController.java`：创建会话时接收 `agentId`。
- `backend/src/main/java/com/h/backend/chat/dto/ChatMessageRequest.java`：新增 `agentId`。
- `backend/src/main/java/com/h/backend/chat/dto/ChatStreamEvent.java`：新增通用 `payload` 字段并保留现有构造器。
- `backend/src/main/java/com/h/backend/chat/dto/CreateChatSessionRequest.java`：新增 `agentId`。
- `backend/src/main/java/com/h/backend/chat/dto/ChatSessionMetaDto.java`：新增 `agentId`。
- `backend/src/main/java/com/h/backend/chat/dto/ChatSessionSummaryDto.java`：新增 `agentId`。
- `backend/src/main/java/com/h/backend/chat/entity/ChatSessionEntity.java`：新增 `agentId` 字段。
- `backend/src/main/java/com/h/backend/chat/service/ChatService.java`：`streamChat` 新增 `agentId`。
- `backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java`：创建与校验方法新增 `agentId`。
- `backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`：改为调用执行器。
- `backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`：会话绑定和校验 `agent_id`。
- `backend/src/main/java/com/h/backend/chat/mapper/ChatSessionMapper.java` 及 XML/注解 SQL：确认选择列包含 `agent_id`。

后端测试新增或修改：

- `backend/src/test/java/com/h/backend/chat/agent/AgentRegistryTest.java`
- `backend/src/test/java/com/h/backend/chat/agent/AgentTopologyMapperTest.java`
- `backend/src/test/java/com/h/backend/chat/agent/AgentStepEventBridgeTest.java`
- `backend/src/test/java/com/h/backend/chat/service/impl/ChatSessionServiceImplTest.java`
- `backend/src/test/java/com/h/backend/chat/dto/ChatStreamEventTest.java`

前端新增：

- `frontend/lib/agents.ts`：Agent 列表与拓扑 API。
- `frontend/lib/agents.test.mjs`：Agent API URL 和类型辅助测试。
- `frontend/app/agents/page.tsx`：领域 Agent 专属问答页。
- `frontend/app/me/agents/page.tsx`：Agent 管理列表页。
- `frontend/app/me/agents/[agentId]/page.tsx`：Agent 编排详情页。

前端修改：

- `frontend/lib/http.ts`：`apiStream` 支持 `agent_step`。
- `frontend/lib/chat-message-state.ts`：支持 assistant turn 的 Agent 步骤状态。
- `frontend/lib/chat-sessions.ts`：会话请求/响应类型增加 `agentId`。
- `frontend/app/chat/page.tsx`：普通聊天传 `agentId: "standard-chat"`，保持旧交互。
- `frontend/app/me/page.tsx`：增加 Agent 管理入口。
- `frontend/app/page.tsx`：认证后主入口可继续跳 `/chat`，不在本任务强制改首页。

前端测试修改：

- `frontend/lib/http.test.mjs`
- `frontend/lib/chat-message-state.test.mjs`

## 任务 1：数据库和会话 agent_id 绑定

**文件：**
- 创建：`backend/src/main/resources/db/migration/V10__add_agent_id_to_chat_sessions.sql`
- 修改：`backend/src/main/java/com/h/backend/chat/entity/ChatSessionEntity.java`
- 修改：`backend/src/main/java/com/h/backend/chat/dto/CreateChatSessionRequest.java`
- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatSessionMetaDto.java`
- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatSessionSummaryDto.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java`
- 修改：`backend/src/main/java/com/h/backend/chat/controller/ChatSessionController.java`
- 测试：`backend/src/test/java/com/h/backend/chat/service/impl/ChatSessionServiceImplTest.java`

- [ ] **步骤 1：编写失败的会话绑定测试**

在 `ChatSessionServiceImplTest` 中新增测试，使用现有 mock 风格。如果该文件不存在，创建测试类并 mock mapper/service 依赖。

```java
@Test
void createSessionStoresAgentId() {
    ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
    ChatSessionMessageMapper messageMapper = mock(ChatSessionMessageMapper.class);
    ChatMemorySnapshotService snapshotService = mock(ChatMemorySnapshotService.class);
    SystemPromptService promptService = mock(SystemPromptService.class);
    ObjectMapper objectMapper = new ObjectMapper();

    when(promptService.resolvePromptId(1L, null)).thenReturn(10L);

    ChatSessionServiceImpl service = new ChatSessionServiceImpl(
            sessionMapper,
            messageMapper,
            snapshotService,
            promptService,
            objectMapper
    );

    service.createSession(1L, null, "car-rental-assistant", null);

    ArgumentCaptor<ChatSessionEntity> captor = ArgumentCaptor.forClass(ChatSessionEntity.class);
    verify(sessionMapper).insert(captor.capture());
    assertThat(captor.getValue().getAgentId()).isEqualTo("car-rental-assistant");
}

@Test
void assertActiveSessionRejectsMismatchedAgentId() {
    ChatSessionEntity session = new ChatSessionEntity();
    session.setUserId(1L);
    session.setSessionId("s1");
    session.setPromptId(null);
    session.setAgentId("car-rental-assistant");
    session.setStatus("ACTIVE");

    ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
    when(sessionMapper.selectOne(any())).thenReturn(session);

    ChatSessionServiceImpl service = new ChatSessionServiceImpl(
            sessionMapper,
            mock(ChatSessionMessageMapper.class),
            mock(ChatMemorySnapshotService.class),
            mock(SystemPromptService.class),
            new ObjectMapper()
    );

    BusinessException ex = assertThrows(
            BusinessException.class,
            () -> service.assertActiveSession(1L, "s1", null, "standard-chat")
    );
    assertThat(ex.getMessage()).contains("会话不属于当前 Agent");
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd backend
mvn -Dtest=ChatSessionServiceImplTest test
```

预期：FAIL，编译错误包含 `createSession` 或 `assertActiveSession` 参数数量不匹配。

- [ ] **步骤 3：添加迁移**

创建 `V10__add_agent_id_to_chat_sessions.sql`：

```sql
ALTER TABLE chat_sessions
    ADD COLUMN IF NOT EXISTS agent_id VARCHAR(64);

UPDATE chat_sessions
SET agent_id = 'standard-chat'
WHERE agent_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_agent_status_updated_at
    ON chat_sessions(user_id, agent_id, status, updated_at DESC);
```

- [ ] **步骤 4：更新实体和 DTO**

在 `ChatSessionEntity` 增加字段：

```java
@TableField("agent_id")
private String agentId;

public String getAgentId() {
    return agentId;
}

public void setAgentId(String agentId) {
    this.agentId = agentId;
}
```

修改 `CreateChatSessionRequest`：

```java
public record CreateChatSessionRequest(
        String currentSessionId,
        Long promptId,
        String agentId
) {
}
```

在 `ChatSessionMetaDto` 和 `ChatSessionSummaryDto` record 中加入：

```java
String agentId
```

- [ ] **步骤 5：更新 ChatSessionService 签名**

```java
ChatSessionOpenDto createSession(Long userId, Long promptId, String agentId, String currentSessionId);

void assertActiveSession(Long userId, String sessionId, Long promptId, String agentId);
```

- [ ] **步骤 6：更新 ChatSessionServiceImpl 最少实现**

在类中加入常量：

```java
private static final String DEFAULT_AGENT_ID = "standard-chat";
```

创建会话时写入：

```java
String resolvedAgentId = StringUtils.isBlank(agentId) ? DEFAULT_AGENT_ID : agentId;
entity.setAgentId(resolvedAgentId);
```

校验时加入：

```java
String requestedAgentId = StringUtils.isBlank(agentId) ? DEFAULT_AGENT_ID : agentId;
String sessionAgentId = StringUtils.isBlank(session.getAgentId()) ? DEFAULT_AGENT_ID : session.getAgentId();
if (!requestedAgentId.equals(sessionAgentId)) {
    throw new BusinessException(40008, "会话不属于当前 Agent，请重新创建会话");
}
```

在普通聊天时保留 prompt 校验；领域 Agent 的 prompt 校验在任务 6 接入 `AgentRegistry` 后按 runtimeType 收敛。

- [ ] **步骤 7：更新 controller 调用**

`ChatSessionController.create` 默认 payload：

```java
CreateChatSessionRequest payload = request == null
        ? new CreateChatSessionRequest(null, null, "standard-chat")
        : request;
return ApiResponse.ok(chatSessionService.createSession(
        principal.userId(),
        payload.promptId(),
        payload.agentId(),
        payload.currentSessionId()
));
```

- [ ] **步骤 8：运行测试验证通过**

运行：

```bash
cd backend
mvn -Dtest=ChatSessionServiceImplTest test
```

预期：PASS。

- [ ] **步骤 9：Commit**

```bash
git add backend/src/main/resources/db/migration/V10__add_agent_id_to_chat_sessions.sql \
  backend/src/main/java/com/h/backend/chat/entity/ChatSessionEntity.java \
  backend/src/main/java/com/h/backend/chat/dto/CreateChatSessionRequest.java \
  backend/src/main/java/com/h/backend/chat/dto/ChatSessionMetaDto.java \
  backend/src/main/java/com/h/backend/chat/dto/ChatSessionSummaryDto.java \
  backend/src/main/java/com/h/backend/chat/service/ChatSessionService.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatSessionServiceImpl.java \
  backend/src/main/java/com/h/backend/chat/controller/ChatSessionController.java \
  backend/src/test/java/com/h/backend/chat/service/impl/ChatSessionServiceImplTest.java
git commit -m "feat: bind chat sessions to agents"
```

## 任务 2：Agent DTO、Registry 和 Controller

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/agent/AgentRuntimeType.java`
- 创建：`backend/src/main/java/com/h/backend/chat/agent/AgentDefinition.java`
- 创建：`backend/src/main/java/com/h/backend/chat/agent/AgentRegistry.java`
- 创建：`backend/src/main/java/com/h/backend/chat/controller/AgentController.java`
- 创建：`backend/src/main/java/com/h/backend/chat/dto/AgentSummaryDto.java`
- 测试：`backend/src/test/java/com/h/backend/chat/agent/AgentRegistryTest.java`

- [ ] **步骤 1：编写失败的 Registry 测试**

```java
@Test
void listsOnlyEnabledAgents() {
    AgentRegistry registry = new AgentRegistry(List.of(
            new AgentDefinition("a1", "Agent A", "出行", List.of("tag"), "summary", new Object(), AgentRuntimeType.AGENTIC_SYNC, true),
            new AgentDefinition("a2", "Agent B", "企业", List.of(), "summary", new Object(), AgentRuntimeType.AGENTIC_SYNC, false)
    ));

    assertThat(registry.listEnabled()).extracting(AgentDefinition::agentId).containsExactly("a1");
}

@Test
void requireRejectsMissingAgent() {
    AgentRegistry registry = new AgentRegistry(List.of());

    BusinessException ex = assertThrows(BusinessException.class, () -> registry.requireEnabled("missing"));
    assertThat(ex.getMessage()).contains("领域 Agent 不存在或未启用");
}
```

- [ ] **步骤 2：运行测试验证失败**

```bash
cd backend
mvn -Dtest=AgentRegistryTest test
```

预期：FAIL，类 `AgentRegistry` 不存在。

- [ ] **步骤 3：创建运行时类型和定义**

`AgentRuntimeType.java`：

```java
package com.h.backend.chat.agent;

public enum AgentRuntimeType {
    STANDARD_STREAMING_CHAT,
    AGENTIC_SYNC
}
```

`AgentDefinition.java`：

```java
package com.h.backend.chat.agent;

import java.util.List;

public record AgentDefinition(
        String agentId,
        String displayName,
        String domain,
        List<String> tags,
        String summary,
        Object agentBean,
        AgentRuntimeType runtimeType,
        boolean enabled
) {
}
```

- [ ] **步骤 4：实现 AgentRegistry**

```java
package com.h.backend.chat.agent;

import com.h.backend.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentRegistry {

    public static final String STANDARD_CHAT_AGENT_ID = "standard-chat";

    private final Map<String, AgentDefinition> definitions;

    public AgentRegistry(List<AgentDefinition> definitions) {
        Map<String, AgentDefinition> ordered = new LinkedHashMap<>();
        for (AgentDefinition definition : definitions) {
            ordered.put(definition.agentId(), definition);
        }
        this.definitions = Map.copyOf(ordered);
    }

    public List<AgentDefinition> listEnabled() {
        return definitions.values().stream()
                .filter(AgentDefinition::enabled)
                .toList();
    }

    public AgentDefinition requireEnabled(String agentId) {
        AgentDefinition definition = definitions.get(agentId);
        if (definition == null || !definition.enabled()) {
            throw new BusinessException(41001, "领域 Agent 不存在或未启用");
        }
        return definition;
    }
}
```

- [ ] **步骤 5：创建 AgentSummaryDto 和 Controller**

`AgentSummaryDto.java`：

```java
package com.h.backend.chat.dto;

import java.util.List;

public record AgentSummaryDto(
        String agentId,
        String displayName,
        String domain,
        List<String> tags,
        String summary,
        String runtimeType,
        boolean enabled
) {
}
```

`AgentController.java`：

```java
package com.h.backend.chat.controller;

import com.h.backend.chat.agent.AgentDefinition;
import com.h.backend.chat.agent.AgentRegistry;
import com.h.backend.chat.dto.AgentSummaryDto;
import com.h.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRegistry agentRegistry;

    public AgentController(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    @GetMapping
    public ApiResponse<List<AgentSummaryDto>> list() {
        return ApiResponse.ok(agentRegistry.listEnabled().stream()
                .map(this::toSummary)
                .toList());
    }

    private AgentSummaryDto toSummary(AgentDefinition definition) {
        return new AgentSummaryDto(
                definition.agentId(),
                definition.displayName(),
                definition.domain(),
                definition.tags(),
                definition.summary(),
                definition.runtimeType().name(),
                definition.enabled()
        );
    }
}
```

- [ ] **步骤 6：注册两个 AgentDefinition Bean**

在 `AgentConfig` 或单独配置类中注册：

```java
@Bean
public AgentDefinition standardChatAgent(HAssistant hAssistant) {
    return new AgentDefinition(
            AgentRegistry.STANDARD_CHAT_AGENT_ID,
            "普通聊天",
            "通用",
            List.of("聊天", "知识库"),
            "使用系统提示词和知识库的普通聊天助手",
            hAssistant,
            AgentRuntimeType.STANDARD_STREAMING_CHAT,
            true
    );
}

@Bean
public AgentDefinition carRentalAgent(CarRentalAssistant carRentalAssistant) {
    return new AgentDefinition(
            "car-rental-assistant",
            "租车应急协助 Agent",
            "出行服务",
            List.of("拖车", "应急", "客户协助"),
            "面向租车客户的拖车与紧急事件协助",
            carRentalAssistant,
            AgentRuntimeType.AGENTIC_SYNC,
            true
    );
}
```

- [ ] **步骤 7：运行测试验证通过**

```bash
cd backend
mvn -Dtest=AgentRegistryTest test
```

预期：PASS。

- [ ] **步骤 8：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/agent/AgentRuntimeType.java \
  backend/src/main/java/com/h/backend/chat/agent/AgentDefinition.java \
  backend/src/main/java/com/h/backend/chat/agent/AgentRegistry.java \
  backend/src/main/java/com/h/backend/chat/controller/AgentController.java \
  backend/src/main/java/com/h/backend/chat/dto/AgentSummaryDto.java \
  backend/src/main/java/com/h/backend/chat/config/AgentConfig.java \
  backend/src/test/java/com/h/backend/chat/agent/AgentRegistryTest.java
git commit -m "feat: add domain agent registry"
```

## 任务 3：AgentTopologyDto 和框架拓扑映射

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/dto/AgentTopologyDto.java`
- 创建：`backend/src/main/java/com/h/backend/chat/dto/AgentTopologyNodeDto.java`
- 创建：`backend/src/main/java/com/h/backend/chat/dto/LoopMetaDto.java`
- 创建：`backend/src/main/java/com/h/backend/chat/dto/StateKeyDto.java`
- 创建：`backend/src/main/java/com/h/backend/chat/agent/AgentTopologyMapper.java`
- 修改：`backend/src/main/java/com/h/backend/chat/controller/AgentController.java`
- 测试：`backend/src/test/java/com/h/backend/chat/agent/AgentTopologyMapperTest.java`

- [ ] **步骤 1：编写失败的 topology mapper 测试**

```java
@Test
void mapsSequenceWithChildren() {
    MockAgent child = MockAgent.ai("extract", "Extract", "customerInfo");
    MockAgent root = MockAgent.sequence("root", "Root", "response", List.of(child));
    AgentDefinition definition = new AgentDefinition("car", "Car Agent", "出行", List.of(), "summary", root, AgentRuntimeType.AGENTIC_SYNC, true);

    AgentTopologyDto dto = new AgentTopologyMapper().from(definition, root);

    assertThat(dto.agent().agentId()).isEqualTo("car");
    assertThat(dto.root().topology()).isEqualTo("SEQUENCE");
    assertThat(dto.root().children()).hasSize(1);
    assertThat(dto.root().children().get(0).outputKey()).isEqualTo("customerInfo");
}
```

在测试文件内创建实现 `AgentInstance` 的 `MockAgent`，字段覆盖 `agentId/name/topology/outputKey/children/arguments`。

```java
static class MockAgent implements AgentInstance {
    private final String agentId;
    private final String name;
    private final AgenticSystemTopology topology;
    private final String outputKey;
    private final List<AgentInstance> children;

    static MockAgent sequence(String agentId, String name, String outputKey, List<AgentInstance> children) {
        return new MockAgent(agentId, name, AgenticSystemTopology.SEQUENCE, outputKey, children);
    }

    static MockAgent ai(String agentId, String name, String outputKey) {
        return new MockAgent(agentId, name, AgenticSystemTopology.AI_AGENT, outputKey, List.of());
    }

    MockAgent(String agentId, String name, AgenticSystemTopology topology, String outputKey, List<AgentInstance> children) {
        this.agentId = agentId;
        this.name = name;
        this.topology = topology;
        this.outputKey = outputKey;
        this.children = children;
    }

    @Override public Class<?> type() { return String.class; }
    @Override public Class<? extends Planner> plannerType() { return null; }
    @Override public String name() { return name; }
    @Override public String agentId() { return agentId; }
    @Override public String description() { return name + " desc"; }
    @Override public Type outputType() { return String.class; }
    @Override public String outputKey() { return outputKey; }
    @Override public boolean async() { return false; }
    @Override public List<AgentArgument> arguments() { return List.of(); }
    @Override public AgentInstance parent() { return null; }
    @Override public List<AgentInstance> subagents() { return children; }
    @Override public AgenticSystemTopology topology() { return topology; }
}
```

- [ ] **步骤 2：运行测试验证失败**

```bash
cd backend
mvn -Dtest=AgentTopologyMapperTest test
```

预期：FAIL，DTO 或 mapper 类不存在。

- [ ] **步骤 3：创建 DTO records**

```java
public record AgentTopologyDto(
        AgentSummaryDto agent,
        AgentTopologyNodeDto root,
        List<StateKeyDto> stateKeys
) {}
```

```java
public record AgentTopologyNodeDto(
        String nodeId,
        String name,
        String topology,
        String type,
        String description,
        String returnType,
        String outputKey,
        List<String> inputKeys,
        String condition,
        Boolean async,
        LoopMetaDto loop,
        List<AgentTopologyNodeDto> children
) {}
```

```java
public record LoopMetaDto(Integer maxIterations, String exitCondition, Boolean testExitAtLoopEnd) {}
public record StateKeyDto(String key, String type, String color) {}
```

- [ ] **步骤 4：实现 mapper 最少功能**

```java
public AgentTopologyDto from(AgentDefinition definition, AgentInstance root) {
    return new AgentTopologyDto(toSummary(definition), toNode(root, null), collectStateKeys(root));
}

private AgentTopologyNodeDto toNode(AgentInstance agent, String condition) {
    List<AgentTopologyNodeDto> children = agent.subagents() == null
            ? List.of()
            : agent.subagents().stream()
                    .map(child -> toNode(child, conditionsOf(agent).get(child.agentId())))
                    .toList();
    return new AgentTopologyNodeDto(
            agent.agentId(),
            agent.name(),
            agent.topology().name(),
            agent.type() == null ? null : agent.type().getSimpleName(),
            agent.description(),
            simpleTypeName(agent.outputType()),
            agent.outputKey(),
            agent.arguments() == null ? List.of() : agent.arguments().stream().map(AgentArgument::name).toList(),
            condition,
            agent.async(),
            loopMeta(agent),
            children
    );
}
```

Add these helper methods with the same casts as `HtmlReportGenerator`:

```java
private Map<String, String> conditionsOf(AgentInstance agent) {
    if (agent.topology() != AgenticSystemTopology.ROUTER) {
        return Map.of();
    }
    Map<String, String> map = new LinkedHashMap<>();
    for (ConditionalAgent ca : agent.as(ConditionalAgentInstance.class).conditionalSubagents()) {
        for (AgentInstance child : ca.agentInstances()) {
            map.put(child.agentId(), ca.condition());
        }
    }
    return map;
}

private LoopMetaDto loopMeta(AgentInstance agent) {
    if (agent.topology() != AgenticSystemTopology.LOOP) {
        return null;
    }
    LoopAgentInstance loop = agent.as(LoopAgentInstance.class);
    return new LoopMetaDto(loop.maxIterations(), loop.exitCondition(), loop.testExitAtLoopEnd());
}

private List<StateKeyDto> collectStateKeys(AgentInstance root) {
    Map<String, StateKeyDto> keys = new LinkedHashMap<>();
    collectStateKeys(root, keys);
    return List.copyOf(keys.values());
}

private void collectStateKeys(AgentInstance agent, Map<String, StateKeyDto> keys) {
    if (agent.arguments() != null) {
        for (AgentArgument argument : agent.arguments()) {
            keys.putIfAbsent(argument.name(), new StateKeyDto(argument.name(), simpleTypeName(argument.type()), colorFor(argument.name())));
        }
    }
    if (agent.outputKey() != null && !agent.outputKey().isBlank()) {
        keys.putIfAbsent(agent.outputKey(), new StateKeyDto(agent.outputKey(), simpleTypeName(agent.outputType()), colorFor(agent.outputKey())));
    }
    if (agent.subagents() != null) {
        for (AgentInstance child : agent.subagents()) {
            collectStateKeys(child, keys);
        }
    }
}

private String simpleTypeName(Type type) {
    if (type == null) {
        return null;
    }
    if (type instanceof Class<?> cls) {
        return cls.getSimpleName();
    }
    return type.getTypeName();
}

private String colorFor(String key) {
    String[] colors = {"#e63946", "#457b9d", "#2a9d8f", "#e9c46a", "#7c3aed", "#0891b2"};
    return colors[Math.abs(key.hashCode()) % colors.length];
}
```

- [ ] **步骤 5：添加 topology endpoint**

In `AgentController`:

```java
private final AgentTopologyMapper topologyMapper;

@GetMapping("/{agentId}/topology")
public ApiResponse<AgentTopologyDto> topology(@PathVariable String agentId) {
    AgentDefinition definition = agentRegistry.requireEnabled(agentId);
    return ApiResponse.ok(topologyMapper.from(definition, (AgentInstance) definition.agentBean()));
}
```

- [ ] **步骤 6：运行测试验证通过**

```bash
cd backend
mvn -Dtest=AgentTopologyMapperTest test
```

预期：PASS。

- [ ] **步骤 7：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/dto/AgentTopologyDto.java \
  backend/src/main/java/com/h/backend/chat/dto/AgentTopologyNodeDto.java \
  backend/src/main/java/com/h/backend/chat/dto/LoopMetaDto.java \
  backend/src/main/java/com/h/backend/chat/dto/StateKeyDto.java \
  backend/src/main/java/com/h/backend/chat/agent/AgentTopologyMapper.java \
  backend/src/main/java/com/h/backend/chat/controller/AgentController.java \
  backend/src/test/java/com/h/backend/chat/agent/AgentTopologyMapperTest.java
git commit -m "feat: expose agent topology dto"
```

## 任务 4：SSE payload 和跨线程 AgentStepEventBridge

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatStreamEvent.java`
- 创建：`backend/src/main/java/com/h/backend/chat/dto/AgentStepPayloadDto.java`
- 创建：`backend/src/main/java/com/h/backend/chat/agent/AgentStepEventBridge.java`
- 创建：`backend/src/main/java/com/h/backend/chat/agent/AgentStepListener.java`
- 测试：`backend/src/test/java/com/h/backend/chat/dto/ChatStreamEventTest.java`
- 测试：`backend/src/test/java/com/h/backend/chat/agent/AgentStepEventBridgeTest.java`

- [ ] **步骤 1：编写失败的 ChatStreamEvent payload 测试**

```java
@Test
void existingConstructorsKeepNullPayload() {
    ChatStreamEvent event = new ChatStreamEvent("chunk", "hello");

    assertThat(event.type()).isEqualTo("chunk");
    assertThat(event.content()).isEqualTo("hello");
    assertThat(event.message()).isNull();
    assertThat(event.payload()).isNull();
}
```

- [ ] **步骤 2：编写失败的 EventBridge 测试**

```java
@Test
void routesEventsByMemoryId() {
    AgentStepEventBridge bridge = new AgentStepEventBridge();
    List<AgentStepPayloadDto> first = new CopyOnWriteArrayList<>();
    List<AgentStepPayloadDto> second = new CopyOnWriteArrayList<>();

    bridge.register("m1", first::add);
    bridge.register("m2", second::add);

    AgentStepPayloadDto payload = new AgentStepPayloadDto("r1", "car", "i1", "n1", "Node", "AI_AGENT", "running", 1, 1);
    bridge.emit("m1", payload);

    assertThat(first).containsExactly(payload);
    assertThat(second).isEmpty();
}

@Test
void unregisterStopsEvents() {
    AgentStepEventBridge bridge = new AgentStepEventBridge();
    List<AgentStepPayloadDto> events = new CopyOnWriteArrayList<>();

    bridge.register("m1", events::add);
    bridge.unregister("m1");
    bridge.emit("m1", new AgentStepPayloadDto("r1", "car", "i1", "n1", "Node", "AI_AGENT", "running", 1, 1));

    assertThat(events).isEmpty();
}
```

- [ ] **步骤 3：运行测试验证失败**

```bash
cd backend
mvn -Dtest=ChatStreamEventTest,AgentStepEventBridgeTest test
```

预期：FAIL，`payload()` 或 bridge 类不存在。

- [ ] **步骤 4：修改 ChatStreamEvent**

```java
public record ChatStreamEvent(String type, String content, ChatSessionMessageDto message, Object payload) {

    public ChatStreamEvent(String type, String content) {
        this(type, content, null, null);
    }

    public ChatStreamEvent(String type, String content, ChatSessionMessageDto message) {
        this(type, content, message, null);
    }
}
```

- [ ] **步骤 5：创建 AgentStepPayloadDto**

```java
public record AgentStepPayloadDto(
        String runId,
        String agentId,
        String invocationId,
        String nodeId,
        String nodeName,
        String topology,
        String status,
        Integer depth,
        Integer sequence
) {
}
```

- [ ] **步骤 6：实现 AgentStepEventBridge**

```java
@Component
public class AgentStepEventBridge {

    private final ConcurrentMap<String, Consumer<AgentStepPayloadDto>> emitters = new ConcurrentHashMap<>();

    public void register(String memoryId, Consumer<AgentStepPayloadDto> emitter) {
        emitters.put(memoryId, emitter);
    }

    public void emit(Object memoryId, AgentStepPayloadDto payload) {
        Consumer<AgentStepPayloadDto> emitter = emitters.get(String.valueOf(memoryId));
        if (emitter != null) {
            emitter.accept(payload);
        }
    }

    public void unregister(String memoryId) {
        emitters.remove(memoryId);
    }
}
```

- [ ] **步骤 7：实现 AgentStepListener**

```java
@Component
public class AgentStepListener implements AgentListener {

    private final AgentStepEventBridge bridge;
    private final AtomicInteger sequence = new AtomicInteger();

    public AgentStepListener(AgentStepEventBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest request) {
        bridge.emit(request.agenticScope().memoryId(), payload(request.agent(), "running"));
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        bridge.emit(response.agenticScope().memoryId(), payload(response.agent(), "completed"));
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        bridge.emit(error.agenticScope().memoryId(), payload(error.agent(), "failed"));
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }

    private AgentStepPayloadDto payload(AgentInstance agent, String status) {
        int next = sequence.incrementAndGet();
        return new AgentStepPayloadDto(
                null,
                null,
                agent.agentId() + ":" + next,
                agent.agentId(),
                agent.name(),
                agent.topology().name(),
                status,
                depth(agent),
                next
        );
    }

    private int depth(AgentInstance agent) {
        int depth = 0;
        AgentInstance cursor = agent.parent();
        while (cursor != null) {
            depth++;
            cursor = cursor.parent();
        }
        return depth;
    }
}
```

任务 6 在执行器边界通过 `withRunAndAgent` 包装 payload，填入 `runId` 和 `agentId` 后再发送 SSE。

- [ ] **步骤 8：运行测试验证通过**

```bash
cd backend
mvn -Dtest=ChatStreamEventTest,AgentStepEventBridgeTest test
```

预期：PASS。

- [ ] **步骤 9：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/dto/ChatStreamEvent.java \
  backend/src/main/java/com/h/backend/chat/dto/AgentStepPayloadDto.java \
  backend/src/main/java/com/h/backend/chat/agent/AgentStepEventBridge.java \
  backend/src/main/java/com/h/backend/chat/agent/AgentStepListener.java \
  backend/src/test/java/com/h/backend/chat/dto/ChatStreamEventTest.java \
  backend/src/test/java/com/h/backend/chat/agent/AgentStepEventBridgeTest.java
git commit -m "feat: add agent step stream events"
```

## 任务 5：把 AgentStepListener 接入 CarRentalAssistant 构建

**文件：**
- 修改：`backend/src/main/java/com/h/backend/chat/config/AgentConfig.java`
- 测试：`backend/src/test/java/com/h/backend/chat/agent/AgentTopologyMapperTest.java`

- [ ] **步骤 1：编写失败的 listener 继承测试**

在 `AgentTopologyMapperTest` 或新建 `AgentConfigTest` 中验证 `AgentStepListener.inheritedBySubagents()`：

```java
@Test
void stepListenerIsInheritedBySubagents() {
    AgentStepListener listener = new AgentStepListener(new AgentStepEventBridge());

    assertThat(listener.inheritedBySubagents()).isTrue();
}
```

- [ ] **步骤 2：运行测试验证失败或通过**

```bash
cd backend
mvn -Dtest=AgentTopologyMapperTest test
```

预期：如果任务 4 已实现 listener，该新增测试 PASS；如果 listener 未实现继承，该测试 FAIL。

- [ ] **步骤 3：注入 listener 并挂到所有 agent builder**

`AgentConfig` 增加：

```java
@Resource
private AgentStepListener agentStepListener;
```

每个 `AgenticServices.agentBuilder(...)` 和 workflow builder 调用增加：

```java
.listener(agentStepListener)
```

包括：

1. `CustomerInfoExtractionService`
2. `TowingAgentService`
3. `ResponseGeneratorService`
4. `EmergencyExtractorService`
5. `EmergencyResponseService`
6. `FireAgentService`
7. `MedicalAgentService`
8. `PoliceAgentService`
9. `conditionalBuilder()`
10. nested `sequenceBuilder()`
11. root `sequenceBuilder(CarRentalAssistant.class)`

同时删除 `FireAgentService` 上重复的第二个 `.chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))`，保留使用 `redisChatMemoryStore` 的 provider。

- [ ] **步骤 4：运行测试验证通过**

```bash
cd backend
mvn -Dtest=AgentTopologyMapperTest test
```

预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/config/AgentConfig.java \
  backend/src/test/java/com/h/backend/chat/agent/AgentTopologyMapperTest.java
git commit -m "feat: attach step listener to agentic assistant"
```

## 任务 6：拆分聊天执行器并接入 agentId 路由

**文件：**
- 创建：`backend/src/main/java/com/h/backend/chat/agent/ChatAgentExecutor.java`
- 创建：`backend/src/main/java/com/h/backend/chat/agent/ChatAgentExecutionCommand.java`
- 创建：`backend/src/main/java/com/h/backend/chat/agent/HAssistantStreamingExecutor.java`
- 创建：`backend/src/main/java/com/h/backend/chat/agent/AgenticSyncExecutor.java`
- 修改：`backend/src/main/java/com/h/backend/chat/dto/ChatMessageRequest.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/ChatService.java`
- 修改：`backend/src/main/java/com/h/backend/chat/controller/ChatController.java`
- 修改：`backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java`
- 测试：`backend/src/test/java/com/h/backend/chat/agent/AgentStepEventBridgeTest.java`

- [ ] **步骤 1：编写失败的 ChatMessageRequest 编译测试**

创建或修改 DTO 测试：

```java
@Test
void chatMessageRequestCarriesAgentId() {
    ChatMessageRequest request = new ChatMessageRequest("hello", "s1", 1L, "standard-chat");

    assertThat(request.agentId()).isEqualTo("standard-chat");
}
```

- [ ] **步骤 2：运行测试验证失败**

```bash
cd backend
mvn -Dtest=ChatStreamEventTest test
```

预期：FAIL，`ChatMessageRequest` 构造器参数不匹配。

- [ ] **步骤 3：更新 DTO、service 和 controller 签名**

`ChatMessageRequest`：

```java
public record ChatMessageRequest(
        @NotBlank(message = "消息不能为空")
        @Size(max = 4000, message = "消息长度不能超过 4000")
        String message,
        @NotBlank(message = "sessionId 不能为空")
        String sessionId,
        Long promptId,
        String agentId
) {}
```

`ChatController` 调用：

```java
chatService.streamChat(
        principal.userId(),
        request.promptId(),
        request.agentId(),
        request.sessionId(),
        request.message().trim()
)
```

- [ ] **步骤 4：创建执行器接口和命令对象**

```java
public interface ChatAgentExecutor {
    boolean supports(AgentRuntimeType runtimeType);
    void execute(ChatAgentExecutionCommand command);
}
```

```java
public record ChatAgentExecutionCommand(
        Long userId,
        Long resolvedPromptId,
        String sessionId,
        String memoryId,
        String userMessage,
        AgentDefinition agent,
        FluxSink<ChatStreamEvent> sink,
        AgentRunService.AgentRunHandle runHandle,
        AgentRunTelemetryService.TelemetryRun telemetryRun
) {}
```

- [ ] **步骤 5：移动现有 HAssistant streaming 逻辑**

创建 `HAssistantStreamingExecutor`，把 `hAssistant.streamChat(...)` 的 callbacks 从 `ChatServiceImpl.runChatStream` 迁移进 `execute(...)`。保留这些行为：

```java
.onPartialThinking(thinking -> emit reasoning)
.onPartialResponse(chunk -> emit chunk)
.onToolExecuted(toolExecution -> recordToolUsage(runId, toolExecution))
.onCompleteResponse(ignored -> append assistant message, complete run, mark success, done)
.onError(error -> failure handling)
```

把 `emitIfActive`、`emitAndCompleteIfActive`、`recordToolUsage` 和失败处理提取为包内 helper 或执行器私有方法，保持现有错误文案。

- [ ] **步骤 6：实现 AgenticSyncExecutor**

```java
@Service
public class AgenticSyncExecutor implements ChatAgentExecutor {

    private final AgentStepEventBridge stepEventBridge;
    private final ChatSessionService chatSessionService;
    private final AgentRunService agentRunService;
    private final AgentRunTelemetryService telemetryService;

    @Override
    public boolean supports(AgentRuntimeType runtimeType) {
        return runtimeType == AgentRuntimeType.AGENTIC_SYNC;
    }

    @Override
    public void execute(ChatAgentExecutionCommand command) {
        stepEventBridge.register(command.memoryId(), payload ->
                emitIfActive(command.sink(), new ChatStreamEvent(
                        "agent_step",
                        "正在执行：" + payload.nodeName(),
                        null,
                        withRunAndAgent(payload, command.runHandle().id(), command.agent().agentId())
                )));
        try {
            CarRentalAssistant assistant = (CarRentalAssistant) command.agent().agentBean();
            ResultWithAgenticScope<String> result = assistant.chat(command.memoryId(), command.userMessage());
            String reply = result.result();
            emitIfActive(command.sink(), new ChatStreamEvent("chunk", reply));
            Long assistantMessageId = chatSessionService.appendAssistantMessage(command.userId(), command.sessionId(), reply);
            agentRunService.completeRun(command.runHandle().id(), assistantMessageId);
            telemetryService.markSuccess(command.telemetryRun());
            emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("done", ""));
        } catch (Exception ex) {
            agentRunService.failRun(command.runHandle().id(), ex.getMessage() == null ? "AI 服务调用失败" : ex.getMessage());
            telemetryService.markFailure(command.telemetryRun(), ex);
            emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("error", "AI 服务调用失败"));
        } finally {
            stepEventBridge.unregister(command.memoryId());
        }
    }

    private AgentStepPayloadDto withRunAndAgent(AgentStepPayloadDto payload, Long runId, String agentId) {
        return new AgentStepPayloadDto(
                String.valueOf(runId),
                agentId,
                payload.invocationId(),
                payload.nodeId(),
                payload.nodeName(),
                payload.topology(),
                payload.status(),
                payload.depth(),
                payload.sequence()
        );
    }

    private void emitIfActive(FluxSink<ChatStreamEvent> sink, ChatStreamEvent event) {
        if (!sink.isCancelled()) {
            sink.next(event);
        }
    }

    private void emitAndCompleteIfActive(FluxSink<ChatStreamEvent> sink, ChatStreamEvent event) {
        if (!sink.isCancelled()) {
            sink.next(event);
            sink.complete();
        }
    }
}
```

Use the same safe emit pattern in `HAssistantStreamingExecutor` and `AgenticSyncExecutor`.

- [ ] **步骤 7：ChatServiceImpl 路由到执行器**

Inject:

```java
private final AgentRegistry agentRegistry;
private final List<ChatAgentExecutor> executors;
```

In `runChatStream`:

```java
AgentDefinition agent = agentRegistry.requireEnabled(
        agentId == null || agentId.isBlank() ? AgentRegistry.STANDARD_CHAT_AGENT_ID : agentId
);
chatSessionService.assertActiveSession(userId, sessionId, promptId, agent.agentId());
Long resolvedPromptId = agent.runtimeType() == AgentRuntimeType.STANDARD_STREAMING_CHAT
        ? systemPromptService.resolvePromptId(userId, promptId)
        : null;
String memoryId = userId + ":" + (resolvedPromptId == null ? "agent" : resolvedPromptId) + ":" + agent.agentId() + ":" + sessionId;
```

Choose executor:

```java
ChatAgentExecutor executor = executors.stream()
        .filter(candidate -> candidate.supports(agent.runtimeType()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No executor for " + agent.runtimeType()));
executor.execute(command);
```

- [ ] **步骤 8：运行后端相关测试**

```bash
cd backend
mvn -Dtest=ChatStreamEventTest,AgentStepEventBridgeTest,ChatSessionServiceImplTest test
```

预期：PASS。

- [ ] **步骤 9：Commit**

```bash
git add backend/src/main/java/com/h/backend/chat/agent/ChatAgentExecutor.java \
  backend/src/main/java/com/h/backend/chat/agent/ChatAgentExecutionCommand.java \
  backend/src/main/java/com/h/backend/chat/agent/HAssistantStreamingExecutor.java \
  backend/src/main/java/com/h/backend/chat/agent/AgenticSyncExecutor.java \
  backend/src/main/java/com/h/backend/chat/dto/ChatMessageRequest.java \
  backend/src/main/java/com/h/backend/chat/service/ChatService.java \
  backend/src/main/java/com/h/backend/chat/controller/ChatController.java \
  backend/src/main/java/com/h/backend/chat/service/impl/ChatServiceImpl.java \
  backend/src/test/java/com/h/backend/chat/dto/ChatStreamEventTest.java
git commit -m "feat: route chat streams by agent"
```

## 任务 7：前端 Agent API 和 stream parser 支持 agent_step

**文件：**
- 创建：`frontend/lib/agents.ts`
- 创建：`frontend/lib/agents.test.mjs`
- 修改：`frontend/lib/http.ts`
- 修改：`frontend/lib/http.test.mjs`

- [ ] **步骤 1：编写失败的 stream parser 测试**

在 `frontend/lib/http.test.mjs` 中新增对 `agent_step` handler 的测试。使用现有测试文件的 mock fetch 风格，断言 handler 收到 payload。

```js
test("apiStream dispatches agent_step events", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              'event: agent_step\n' +
                'data: {"type":"agent_step","content":"正在执行：客户信息提取","payload":{"invocationId":"i1","nodeName":"客户信息提取","status":"running"}}\n\n' +
                'event: done\n' +
                'data: {"type":"done","content":""}\n\n',
            ),
          );
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );

  const steps = [];
  try {
    await apiStream("/api/chat/messages/stream", { method: "POST" }, {
      onChunk() {},
      onAgentStep(step) {
        steps.push(step);
      },
    });
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(steps[0].nodeName, "客户信息提取");
  assert.equal(steps[0].status, "running");
});
```

- [ ] **步骤 2：运行前端测试验证失败**

```bash
cd frontend
npm test -- lib/http.test.mjs
```

预期：FAIL，`onAgentStep` handler 类型或分发不存在。

- [ ] **步骤 3：更新 apiStream 类型和分发**

在 `apiStream` handlers 增加：

```ts
onAgentStep?: (payload: AgentStepPayload) => void;
```

定义类型：

```ts
export type AgentStepPayload = {
  runId: string | null;
  agentId: string | null;
  invocationId: string;
  nodeId: string;
  nodeName: string;
  topology: string;
  status: "running" | "completed" | "failed";
  depth: number | null;
  sequence: number;
};
```

解析 payload：

```ts
const payload = JSON.parse(dataLine.slice("data:".length).trim()) as {
  type: string;
  content: string;
  message?: ChatSessionMessage;
  payload?: unknown;
};
```

分发：

```ts
} else if (eventType === "agent_step" && payload.payload) {
  handlers.onAgentStep?.(payload.payload as AgentStepPayload);
}
```

- [ ] **步骤 4：创建 agents API client**

`frontend/lib/agents.ts`：

```ts
import { apiFetch } from "./http";

export type AgentSummary = {
  agentId: string;
  displayName: string;
  domain: string;
  tags: string[];
  summary: string;
  runtimeType: string;
  enabled: boolean;
};

export type AgentTopologyNode = {
  nodeId: string;
  name: string;
  topology: string;
  type: string | null;
  description: string | null;
  returnType: string | null;
  outputKey: string | null;
  inputKeys: string[];
  condition: string | null;
  async: boolean | null;
  loop: { maxIterations: number | null; exitCondition: string | null; testExitAtLoopEnd: boolean | null } | null;
  children: AgentTopologyNode[];
};

export type AgentTopology = {
  agent: AgentSummary;
  root: AgentTopologyNode;
  stateKeys: Array<{ key: string; type: string; color: string }>;
};

export function listAgents() {
  return apiFetch<AgentSummary[]>("/api/agents");
}

export function getAgentTopology(agentId: string) {
  return apiFetch<AgentTopology>(`/api/agents/${encodeURIComponent(agentId)}/topology`);
}
```

- [ ] **步骤 5：添加 agents API 测试**

```js
test("getAgentTopology encodes agent id", async () => {
  const calls = [];
  global.fetch = async (path) => {
    calls.push(path);
    return Response.json({ code: 0, message: "ok", data: { agent: {}, root: {}, stateKeys: [] } });
  };

  await getAgentTopology("car/rental");

  assert.equal(calls[0], "/api/agents/car%2Frental/topology");
});
```

- [ ] **步骤 6：运行前端测试验证通过**

```bash
cd frontend
npm test
```

预期：PASS。

- [ ] **步骤 7：Commit**

```bash
git add frontend/lib/agents.ts frontend/lib/agents.test.mjs frontend/lib/http.ts frontend/lib/http.test.mjs
git commit -m "feat: add frontend agent stream support"
```

## 任务 8：前端消息状态支持并行 Agent 步骤

**文件：**
- 修改：`frontend/lib/chat-message-state.ts`
- 修改：`frontend/lib/chat-message-state.test.mjs`
- 修改：`frontend/app/chat/page.tsx`

- [ ] **步骤 1：编写失败的步骤状态测试**

```js
test("applyAgentStep upserts parallel steps on assistant message", () => {
  const messages = [{ id: "assistant-1", role: "assistant", messageType: "AI", content: "", agentSteps: [] }];

  const next = applyAgentStep(messages, "assistant-1", {
    invocationId: "i1",
    nodeId: "n1",
    nodeName: "客户信息提取",
    topology: "AI_AGENT",
    status: "running",
    depth: 1,
    sequence: 1,
  });

  assert.equal(next[0].agentSteps.length, 1);
  assert.equal(next[0].agentSteps[0].status, "running");
});
```

- [ ] **步骤 2：运行测试验证失败**

```bash
cd frontend
npm test -- lib/chat-message-state.test.mjs
```

预期：FAIL，`applyAgentStep` 不存在。

- [ ] **步骤 3：扩展类型和 applyAgentStep**

```ts
export type UiAgentStep = {
  invocationId: string;
  nodeId: string;
  nodeName: string;
  topology: string;
  status: "running" | "completed" | "failed";
  depth: number | null;
  sequence: number;
};

export type UiChatMessage = {
  id: string;
  role: "assistant" | "blocked" | "user";
  messageType: ChatSessionMessageType;
  content: string;
  agentSteps?: UiAgentStep[];
  payload?: ChatMessagePayload;
  resources?: ChatMessageResource[];
  createdAt?: string;
};
```

```ts
export function applyAgentStep(messages: UiChatMessage[], assistantId: string, step: UiAgentStep): UiChatMessage[] {
  return messages.map((message) => {
    if (message.id !== assistantId) return message;
    const existing = message.agentSteps ?? [];
    const index = existing.findIndex((item) => item.invocationId === step.invocationId);
    const nextSteps =
      index >= 0
        ? existing.map((item, itemIndex) => (itemIndex === index ? { ...item, ...step } : item))
        : [...existing, step].sort((a, b) => a.sequence - b.sequence);
    return { ...message, agentSteps: nextSteps };
  });
}
```

Update `RenderableTurn` assistant/blocked variants to include:

```ts
agentSteps: UiAgentStep[];
```

- [ ] **步骤 4：接入 chat page stream handler**

In `frontend/app/chat/page.tsx` import `applyAgentStep` and add handler:

```ts
onAgentStep(step) {
  setMessages((current) => applyAgentStep(current, assistantMessage.id, step));
}
```

普通 `/chat` request body adds:

```ts
agentId: "standard-chat"
```

- [ ] **步骤 5：渲染执行过程**

Add component:

```tsx
function AgentStepDetails({ steps, pending }: { steps: UiAgentStep[]; pending?: boolean }) {
  if (steps.length === 0) return null;
  return (
    <details className="rounded-2xl border border-stone-200 bg-stone-50/90 px-3 py-2 text-stone-600" open={pending}>
      <summary className="cursor-pointer list-none text-xs font-medium tracking-[0.18em] text-stone-500">
        执行过程
      </summary>
      <div className="mt-2 space-y-2">
        {steps.map((step) => (
          <div key={step.invocationId} className="flex items-center justify-between gap-3 text-xs">
            <span className="truncate">{step.nodeName}</span>
            <span>{step.status === "running" ? "执行中" : step.status === "completed" ? "已完成" : "失败"}</span>
          </div>
        ))}
      </div>
    </details>
  );
}
```

Render it above reasoning/answer for assistant turns.

- [ ] **步骤 6：运行测试验证通过**

```bash
cd frontend
npm test -- lib/chat-message-state.test.mjs
```

预期：PASS。

- [ ] **步骤 7：Commit**

```bash
git add frontend/lib/chat-message-state.ts frontend/lib/chat-message-state.test.mjs frontend/app/chat/page.tsx
git commit -m "feat: render agent step events"
```

## 任务 9：构建 `/agents` 领域 Agent 问答页

**文件：**
- 创建：`frontend/app/agents/page.tsx`
- 修改：`frontend/lib/chat-sessions.ts`

- [ ] **步骤 1：更新 chat session client**

`createChatSession` payload 增加 `agentId`：

```ts
export function createChatSession(payload?: {
  currentSessionId?: string | null;
  promptId?: number | null;
  agentId?: string | null;
}) {
  return apiFetch<ChatSessionOpen>("/api/chat/sessions/create", {
    method: "POST",
    body: JSON.stringify({
      currentSessionId: payload?.currentSessionId ?? null,
      promptId: payload?.promptId ?? null,
      agentId: payload?.agentId ?? "standard-chat",
    }),
  });
}
```

Add `agentId` to `ChatSessionSummary` and `ChatSessionMeta`.

- [ ] **步骤 2：创建 `/agents` 页面骨架**

Use existing auth/bootstrap pattern from `frontend/app/chat/page.tsx`:

```tsx
"use client";

export default function AgentsPage() {
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [selectedAgent, setSelectedAgent] = useState<AgentSummary | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<UiChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);

  useEffect(() => {
    getCurrentUser()
      .then(() => listAgents())
      .then(setAgents)
      .catch(() => router.replace("/auth/login"));
  }, [router]);
}
```

- [ ] **步骤 3：添加 Agent 筛选和选择**

Render search, domain chips, and cards. Selection creates session:

```ts
async function handleSelectAgent(agent: AgentSummary) {
  const detail = await createChatSession({ agentId: agent.agentId, promptId: null, currentSessionId: sessionId });
  setSelectedAgent(agent);
  setSessionId(detail.session.sessionId);
  setMessages(detail.messagePage.messages.map(toUiChatMessage));
}
```

- [ ] **步骤 4：复用 stream submit 逻辑**

Request body:

```ts
body: JSON.stringify({
  message: content,
  sessionId,
  promptId: null,
  agentId: selectedAgent.agentId,
})
```

Handlers include `onAgentStep`, `onChunk`, `onDone`, `onError`, `onBlocked`, `onImage`.

- [ ] **步骤 5：移动端布局**

Use a max-width mobile column like existing chat:

```tsx
<main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] text-stone-900">
  <section className="mx-auto flex min-h-screen w-full max-w-md flex-col">
    ...
  </section>
</main>
```

Cards use 8px radius or existing style if the app style already uses larger chat bubbles. Keep text compact and avoid marketing hero layout.

- [ ] **步骤 6：运行 frontend checks**

```bash
cd frontend
npm run lint
npm test
```

预期：PASS。

- [ ] **步骤 7：Commit**

```bash
git add frontend/app/agents/page.tsx frontend/lib/chat-sessions.ts
git commit -m "feat: add domain agent chat page"
```

## 任务 10：构建 Agent 管理和拓扑详情页面

**文件：**
- 创建：`frontend/app/me/agents/page.tsx`
- 创建：`frontend/app/me/agents/[agentId]/page.tsx`
- 修改：`frontend/app/me/page.tsx`

- [ ] **步骤 1：创建 `/me/agents` 管理列表页**

Fetch `listAgents()` after auth. Render:

```tsx
{agents.map((agent) => (
  <article key={agent.agentId} className="rounded-lg border border-stone-200 bg-white/90 p-4">
    <p className="text-xs text-amber-700">{agent.domain}</p>
    <h2 className="mt-1 text-base font-semibold">{agent.displayName}</h2>
    <p className="mt-2 text-sm text-stone-500">{agent.summary}</p>
    <div className="mt-3 flex gap-2">
      <Link href={`/me/agents/${agent.agentId}`}>查看编排</Link>
      <Link href={`/agents?agentId=${agent.agentId}`}>开始问答</Link>
    </div>
  </article>
))}
```

- [ ] **步骤 2：创建 topology node component**

Inside `[agentId]/page.tsx`, create local recursive component:

```tsx
function TopologyNode({ node }: { node: AgentTopologyNode }) {
  return (
    <li>
      {node.condition ? <div className="condition-label">when: {node.condition}</div> : null}
      <button type="button" className="node-card">
        <span>{node.topology}</span>
        <strong>{node.name}</strong>
      </button>
      {node.children.length > 0 ? (
        <ul>{node.children.map((child) => <TopologyNode key={child.nodeId} node={child} />)}</ul>
      ) : null}
    </li>
  );
}
```

Add CSS classes through Tailwind and local class strings. Use horizontal scroll around the tree.

- [ ] **步骤 3：创建 `/me/agents/[agentId]` 页面**

Fetch:

```ts
const topology = await getAgentTopology(agentId);
```

In client page, get `agentId` from params and render title, tags, state keys, and tree. Clicking a node opens a bottom drawer with node details.

- [ ] **步骤 4：Add menu link**

In `frontend/app/me/page.tsx`, add a link card:

```tsx
<Link href="/me/agents">领域 Agent 管理</Link>
```

- [ ] **步骤 5：运行 frontend checks**

```bash
cd frontend
npm run lint
npm test
```

预期：PASS。

- [ ] **步骤 6：Commit**

```bash
git add frontend/app/me/agents/page.tsx frontend/app/me/agents/[agentId]/page.tsx frontend/app/me/page.tsx
git commit -m "feat: add agent management pages"
```

## 任务 11：全量验证和本地浏览器检查

**文件：**
- 修改：按验证发现的问题精确修改。

- [ ] **步骤 1：运行后端测试**

```bash
cd backend
mvn test
```

预期：BUILD SUCCESS。

- [ ] **步骤 2：运行前端测试和 lint**

```bash
cd frontend
npm test
npm run lint
```

预期：PASS，lint 无错误。

- [ ] **步骤 3：启动服务**

后端：

```bash
cd backend
mvn spring-boot:run
```

前端：

```bash
cd frontend
npm run dev
```

预期：前端输出可访问 URL，通常是 `http://localhost:3000`。

- [ ] **步骤 4：浏览器验证**

使用浏览器检查：

1. `/chat` 能加载，普通聊天请求 body 带 `agentId: "standard-chat"`。
2. `/agents` 能加载 Agent 列表。
3. 选择 `租车应急协助 Agent` 后能创建会话。
4. 发送消息后能看到 `执行过程`，其中多个步骤可以显示。
5. 最终回复显示在 assistant 气泡。
6. `/me/agents` 能显示列表。
7. `/me/agents/car-rental-assistant` 能显示拓扑树。

- [ ] **步骤 5：最终 Commit**

如果步骤 1-4 产生修复：

```bash
git add <changed-files>
git commit -m "fix: polish domain agent integration"
```

如果没有修复，跳过此 commit。
