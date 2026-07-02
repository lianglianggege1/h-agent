# A2A RPC 出口层实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 `backend` 和 `other-agents` 的 A2A 集成重构为“LangChain4j agent 能力 + A2A RPC 出口层”，让远端 agent 在调用侧像本地 LangChain4j agent 一样使用，在服务侧仍然由标准 LangChain4j agent bean 提供业务能力。

**架构：** `backend` 直接采用 LangChain4j A2A client：`AgenticServices.a2aBuilder(url, Interface.class)`，删除自研远端 registry/invoker/client。`other-agents` 保持 LangChain4j agent interface/bean 作为主流程，新增一层参考 AgentScope 的 A2A server facade、agent-card factory、request handler、executor、task store、JSON-RPC transport wrapper，把已有 agent bean 暴露成 A2A RPC endpoint。A2A 在本项目中只是出口协议，不成为新的业务 agent 编程模型。

**技术栈：** Spring Boot 3.4、Java 23、LangChain4j `1.17.0`、`langchain4j-agentic-a2a:1.17.0-beta27`、A2A Java SDK、JUnit 5、Spring `WebTestClient`、Jackson。

---

## 参考资料

- AgentScope A2A 文档：[A2A (Agent2Agent)](https://java.agentscope.io/v1/zh/docs/task/a2a.html)
- AgentScope A2A 本地源码：`/Users/huajiang/Desktop/ai_learn/agentscope-java/agentscope-extensions/agentscope-extensions-a2a`
- LangChain4j A2A 本地源码：`/Users/huajiang/Desktop/ai_learn/langchain4j/langchain4j-agentic-a2a`
- 本项目设计文档：`/Users/huajiang/Desktop/h-agent/docs/superpowers/specs/2026-07-02-a2a-adapter-redesign.md`
- LangChain4j client 重点类：`DefaultA2AClientBuilder`、`A2AContextId`、`A2ATaskId`、`A2AClientAgent`
- AgentScope server 重点类：`AgentScopeA2aServer`、`AgentScopeAgentExecutor`、`AgentRequestOptions`、`JsonRpcTransportWrapper`、`AgentScopeAgentCardConverter`

---

## 文件结构

Backend A2A client：

- 修改：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/config/OtherAgentsA2AProperties.java`
  - 只保留轻量环境配置：`baseUrl`、`enabled`，提供统一 endpoint 生成方法。
- 修改：`/Users/huajiang/Desktop/h-agent/backend/src/main/resources/application.yml`
  - 删除 `remote-agents` 重配置，只保留 `base-url` 和 `enabled`。
- 创建：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2ARemoteAgents.java`
  - 定义 `A2ACreativeWriter`、`A2AAudienceEditor`、`A2AStyleEditor` 三个 LangChain4j 风格远端 agent interface。
- 修改：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2AAgentConfig.java`
  - 使用 `AgenticServices.a2aBuilder(...)` 创建远端 agent proxy，并继续组合 `A2AStoryAssistant` workflow。
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/OtherAgentsA2AClient.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2ARemoteAgentInvoker.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2ARemoteAgentRegistry.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2AAgents.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/RemoteCreativeWriterAgent.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/RemoteAudienceEditorAgent.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/RemoteStyleEditorAgent.java`
- 测试：`/Users/huajiang/Desktop/h-agent/backend/src/test/java/com/h/backend/chat/config/OtherAgentsA2APropertiesTest.java`
- 测试：`/Users/huajiang/Desktop/h-agent/backend/src/test/java/com/h/backend/chat/ai/a2a/A2AAgentConfigTest.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/test/java/com/h/backend/chat/ai/a2a/A2ARemoteAgentRegistryTest.java`

Other-agents A2A export model：

- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/export/A2AAgentExport.java`
  - 描述一个已存在 LangChain4j agent bean 的 A2A 暴露定义。
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/export/A2AAgentExports.java`
  - Java 配置友好的 builder，用于集中声明暴露哪些 agent。
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/export/A2AAgentExportRegistry.java`
  - 按 `agentId` 查询 export。
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/export/A2AExportMethod.java`
  - 保存目标 method、`@V` 参数、`@MemoryId` 参数位置、output key、公开名称和描述。
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/export/A2AAgentExportsTest.java`
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/export/A2AAgentExportRegistryTest.java`

Other-agents A2A protocol/server：

- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2AAgentServer.java`
  - server facade，组合 registry、card factory、request handler、transport wrapper。
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2AAgentCardFactory.java`
  - 由 `A2AAgentExport` 生成 agent-card。
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2ARequestHandler.java`
  - 处理 `message/send`，拒绝不支持的方法。
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2AAgentExecutor.java`
  - 管理 task/context 生命周期，调用 method invoker。
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2AInvocationContext.java`
  - 保存本次调用的 `agentId/contextId/taskId/userId/sessionId/memoryId`。
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2ATaskRecord.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2ATaskStore.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/InMemoryA2ATaskStore.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/LangChain4jAgentMethodInvoker.java`
  - 把 A2A text parts 和 metadata 映射成 LangChain4j agent method 参数。
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2AMessageMapper.java`
  - 负责 A2A `Message`、text part、artifact、task status message 转换。
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/JsonRpcA2ATransportWrapper.java`
  - 解析 JSON-RPC envelope，保留 `id`，封装 parse/method/invalid request 错误。
- 修改：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/interfaces/web/A2AController.java`
  - 替换为统一 endpoint：`GET /a2a/agents/{agentId}/.well-known/agent-card.json`、`POST /a2a/agents/{agentId}`。
- 删除：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/application/A2AAgentCardApplicationService.java`
- 修改：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/config/A2AAgentConfig.java`
  - 保持原有 LangChain4j agent bean，新增 `A2AAgentExports`、`A2AAgentServer` 等 A2A 出口层 bean。
- 修改：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/config/OtherAgentsA2AProperties.java`
  - 保留 `publicUrl`，可新增 `agentUrl(agentId)` helper。
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/server/A2AAgentCardFactoryTest.java`
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/server/LangChain4jAgentMethodInvokerTest.java`
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/server/A2AAgentExecutorTest.java`
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/server/JsonRpcA2ATransportWrapperTest.java`
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/interfaces/web/A2AControllerTest.java`

协议契约验证：

- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/contract/LangChain4jA2AClientContractTest.java`
  - 证明 other-agents 生成的 card 和 `message/send` 响应能被 LangChain4j A2A client 消费。

---

## 任务 1：确认 A2A SDK wire contract，锁住兼容目标

**文件：**
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/contract/LangChain4jA2AClientContractTest.java`
- 参考：`/Users/huajiang/Desktop/ai_learn/langchain4j/langchain4j-agentic-a2a/src/main/java/dev/langchain4j/agentic/a2a/DefaultA2AClientBuilder.java`
- 参考：`/Users/huajiang/Desktop/ai_learn/langchain4j/langchain4j-agentic-a2a/src/test/java/dev/langchain4j/agentic/a2a/A2AAgentIT.java`

- [ ] **步骤 1：编写失败的 contract 测试**

创建 `other-agents/src/test/java/com/h/otheragents/a2a/contract/LangChain4jA2AClientContractTest.java`：

```java
package com.h.otheragents.a2a.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jA2AClientContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void agentCardUsesLangChain4jClientReadableShape() throws Exception {
        JsonNode card = objectMapper.readTree("""
                {
                  "name": "creative-writer",
                  "description": "根据主题生成故事初稿",
                  "url": "http://localhost:8082/a2a/agents/creative-writer",
                  "provider": {
                    "organization": "h-agent other-agents",
                    "url": "http://localhost:8082"
                  },
                  "version": "0.1.0",
                  "capabilities": {
                    "streaming": false,
                    "pushNotifications": false,
                    "stateTransitionHistory": false
                  },
                  "defaultInputModes": ["text/plain"],
                  "defaultOutputModes": ["text/plain"],
                  "skills": [{
                    "id": "creative-writer",
                    "name": "创意写作者",
                    "description": "根据主题生成故事初稿",
                    "tags": ["story"],
                    "examples": ["月球救援"],
                    "inputModes": ["text/plain"],
                    "outputModes": ["text/plain"]
                  }]
                }
                """);

        assertEquals("creative-writer", card.path("name").asText());
        assertEquals("http://localhost:8082/a2a/agents/creative-writer", card.path("url").asText());
        assertTrue(card.path("defaultInputModes").isArray());
        assertTrue(card.path("skills").isArray());
        assertEquals("creative-writer", card.path("skills").get(0).path("id").asText());
    }

    @Test
    void messageSendResponseReturnsTaskWithContextAndTextArtifact() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": "rpc-1",
                  "result": {
                    "kind": "task",
                    "id": "task-1",
                    "contextId": "context-1",
                    "status": {
                      "state": "completed"
                    },
                    "artifacts": [{
                      "artifactId": "artifact-1",
                      "parts": [{
                        "kind": "text",
                        "text": "故事内容"
                      }]
                    }]
                  }
                }
                """);

        assertEquals("rpc-1", response.path("id").asText());
        assertEquals("task", response.path("result").path("kind").asText());
        assertEquals("task-1", response.path("result").path("id").asText());
        assertEquals("context-1", response.path("result").path("contextId").asText());
        assertEquals("故事内容", response.path("result").path("artifacts").get(0).path("parts").get(0).path("text").asText());
    }
}
```

- [ ] **步骤 2：运行测试确认 contract 测试可执行**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=LangChain4jA2AClientContractTest test
```

预期：PASS。这个测试暂时只锁住 JSON wire shape；真正的 LangChain4j client 端到端会在任务 9 做。

- [ ] **步骤 3：检查依赖树里的 A2A SDK 包名**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -q dependency:tree -Dincludes=org.a2aproject,io.github.a2asdk,io.a2a
```

预期：输出能看到当前项目实际使用的 A2A SDK 依赖。记录事实到本计划执行记录中：

```text
other-agents A2A SDK dependency tree:
[在执行时粘贴 dependency:tree 中 org.a2aproject/io.github.a2asdk/io.a2a 相关行]
```

- [ ] **步骤 4：根据依赖树确定实现导入策略**

如果 `other-agents` 当前可直接导入 `io.a2a.spec.*` 且测试编译通过，本轮服务端实现继续使用现有 `io.a2a.spec.*` 类型，并用 JSON contract 保证 LangChain4j client 可消费。  
如果 `langchain4j-agentic-a2a` 引入的 `org.a2aproject.sdk.*` 与 `io.a2a.spec.*` 同时存在且类型冲突，服务端内部仍使用 `io.a2a.spec.*`，controller 返回 JSON，由 wire contract 和端到端测试兜底，不在业务代码里混用两套 SDK model。

- [ ] **步骤 5：Commit**

```bash
cd /Users/huajiang/Desktop/h-agent
git add other-agents/src/test/java/com/h/otheragents/a2a/contract/LangChain4jA2AClientContractTest.java
git commit -m "test: characterize a2a wire contract"
```

---

## 任务 2：清理 backend A2A 配置，移除远端 agent 重配置

**文件：**
- 修改：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/config/OtherAgentsA2AProperties.java`
- 修改：`/Users/huajiang/Desktop/h-agent/backend/src/main/resources/application.yml`
- 测试：`/Users/huajiang/Desktop/h-agent/backend/src/test/java/com/h/backend/chat/config/OtherAgentsA2APropertiesTest.java`

- [ ] **步骤 1：编写失败测试，要求只用 base-url 推导 agent endpoint**

将 `backend/src/test/java/com/h/backend/chat/config/OtherAgentsA2APropertiesTest.java` 调整为：

```java
package com.h.backend.chat.config;

import com.h.backend.chat.infrastructure.config.OtherAgentsA2AProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtherAgentsA2APropertiesTest {

    @Test
    void agentUrlAppendsUnifiedA2AAgentPath() {
        OtherAgentsA2AProperties properties = new OtherAgentsA2AProperties();
        properties.setBaseUrl("http://localhost:8082/");

        assertEquals(
                "http://localhost:8082/a2a/agents/creative-writer",
                properties.agentUrl("creative-writer")
        );
    }

    @Test
    void defaultsKeepOtherAgentsA2AEnabled() {
        OtherAgentsA2AProperties properties = new OtherAgentsA2AProperties();

        assertTrue(properties.isEnabled());
        assertEquals("http://localhost:8082", properties.getBaseUrl());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/backend
mvn -Dtest=OtherAgentsA2APropertiesTest test
```

预期：编译失败，提示 `agentUrl(String)` 不存在，或旧 `remoteAgents` 相关测试仍在失败。

- [ ] **步骤 3：实现轻量配置类**

把 `backend/src/main/java/com/h/backend/chat/infrastructure/config/OtherAgentsA2AProperties.java` 改为：

```java
package com.h.backend.chat.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agents.a2a.other-agents")
public class OtherAgentsA2AProperties {

    private String baseUrl = "http://localhost:8082";
    private boolean enabled = true;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String agentUrl(String agentId) {
        return normalizedBaseUrl() + "/a2a/agents/" + agentId;
    }

    private String normalizedBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8082";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
```

- [ ] **步骤 4：清理 backend 配置文件**

在 `backend/src/main/resources/application.yml` 中保留：

```yaml
agents:
  a2a:
    other-agents:
      enabled: true
      base-url: http://localhost:8082
```

删除 `remote-agents:` 列表以及其中的 `id/card-url/input-keys/output-key/async`。

- [ ] **步骤 5：运行测试验证通过**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/backend
mvn -Dtest=OtherAgentsA2APropertiesTest test
```

预期：PASS。

- [ ] **步骤 6：Commit**

```bash
cd /Users/huajiang/Desktop/h-agent
git add backend/src/main/java/com/h/backend/chat/infrastructure/config/OtherAgentsA2AProperties.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/h/backend/chat/config/OtherAgentsA2APropertiesTest.java
git commit -m "refactor: simplify other agents a2a config"
```

---

## 任务 3：backend 改用 LangChain4j A2A client interface

**文件：**
- 创建：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2ARemoteAgents.java`
- 修改：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2AAgentConfig.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/OtherAgentsA2AClient.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2ARemoteAgentInvoker.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2ARemoteAgentRegistry.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2AAgents.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/RemoteCreativeWriterAgent.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/RemoteAudienceEditorAgent.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/RemoteStyleEditorAgent.java`
- 测试：`/Users/huajiang/Desktop/h-agent/backend/src/test/java/com/h/backend/chat/ai/a2a/A2AAgentConfigTest.java`
- 删除：`/Users/huajiang/Desktop/h-agent/backend/src/test/java/com/h/backend/chat/ai/a2a/A2ARemoteAgentRegistryTest.java`

- [ ] **步骤 1：编写失败测试，确认 A2AAgentConfig 不依赖自研 registry**

创建 `backend/src/test/java/com/h/backend/chat/ai/a2a/A2AAgentConfigTest.java`：

```java
package com.h.backend.chat.ai.a2a;

import com.h.backend.chat.infrastructure.ai.a2a.A2AAgentConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class A2AAgentConfigTest {

    @Test
    void configDoesNotInjectCustomRemoteAgentRegistry() {
        boolean hasRegistryField = Arrays.stream(A2AAgentConfig.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(type -> type.getSimpleName().equals("A2ARemoteAgentRegistry"));

        assertFalse(hasRegistryField);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/backend
mvn -Dtest=A2AAgentConfigTest test
```

预期：FAIL，因为当前 `A2AAgentConfig` 仍注入 `A2ARemoteAgentRegistry`。

- [ ] **步骤 3：新增远端 agent interface**

创建 `backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2ARemoteAgents.java`：

```java
package com.h.backend.chat.infrastructure.ai.a2a;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.a2a.A2AContextId;
import dev.langchain4j.agentic.a2a.A2ATaskId;
import dev.langchain4j.service.V;

public final class A2ARemoteAgents {

    private A2ARemoteAgents() {
    }

    public interface CreativeWriter {

        @Agent(outputKey = "story")
        String generateStory(
                @V("topic") String topic,
                @A2AContextId @V("a2aContextId") String contextId,
                @A2ATaskId @V("creativeWriterTaskId") String taskId);
    }

    public interface AudienceEditor {

        @Agent(outputKey = "story")
        String editStory(
                @V("story") String story,
                @V("audience") String audience,
                @A2AContextId @V("a2aContextId") String contextId,
                @A2ATaskId @V("audienceEditorTaskId") String taskId);
    }

    public interface StyleEditor {

        @Agent(outputKey = "story")
        String editStory(
                @V("story") String story,
                @V("style") String style,
                @A2AContextId @V("a2aContextId") String contextId,
                @A2ATaskId @V("styleEditorTaskId") String taskId);
    }
}
```

说明：`contextId/taskId` 参数用于和 LangChain4j A2A client 的 `AgenticScope` 写回机制对齐；第一轮调用可以传 `null`，服务端会生成并带回。

- [ ] **步骤 4：重写 A2AAgentConfig 的远端 agent 创建逻辑**

在 `backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2AAgentConfig.java` 中：

1. 删除字段：

```java
@Resource
private A2ARemoteAgentRegistry remoteAgentRegistry;
```

2. 注入属性：

```java
@Resource
private com.h.backend.chat.infrastructure.config.OtherAgentsA2AProperties otherAgentsA2AProperties;
```

3. 在 `a2aStoryAssistant()` 开头替换远端 agent 创建：

```java
A2ARemoteAgents.CreativeWriter creativeWriter = AgenticServices
        .a2aBuilder(otherAgentsA2AProperties.agentUrl("creative-writer"), A2ARemoteAgents.CreativeWriter.class)
        .listener(agentStepListener)
        .outputKey("story")
        .build();

A2ARemoteAgents.AudienceEditor audienceEditor = AgenticServices
        .a2aBuilder(otherAgentsA2AProperties.agentUrl("audience-editor"), A2ARemoteAgents.AudienceEditor.class)
        .listener(agentStepListener)
        .outputKey("story")
        .build();

A2ARemoteAgents.StyleEditor styleEditor = AgenticServices
        .a2aBuilder(otherAgentsA2AProperties.agentUrl("style-editor"), A2ARemoteAgents.StyleEditor.class)
        .listener(agentStepListener)
        .outputKey("story")
        .build();
```

4. 保持现有 workflow 的 `.subAgents(creativeWriter, audienceEditor)` 和 `.subAgents(styleEditor, styleScorer)` 结构。

- [ ] **步骤 5：删除自研 backend A2A client/registry 代码**

删除以下文件：

```bash
rm backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/OtherAgentsA2AClient.java
rm backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2ARemoteAgentInvoker.java
rm backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2ARemoteAgentRegistry.java
rm -f backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/A2AAgents.java
rm -f backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/RemoteCreativeWriterAgent.java
rm -f backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/RemoteAudienceEditorAgent.java
rm -f backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a/RemoteStyleEditorAgent.java
rm -f backend/src/test/java/com/h/backend/chat/ai/a2a/A2ARemoteAgentRegistryTest.java
```

- [ ] **步骤 6：运行 backend A2A 相关测试**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/backend
mvn -Dtest=A2AAgentConfigTest,OtherAgentsA2APropertiesTest test
```

预期：PASS。

- [ ] **步骤 7：编译 backend**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/backend
mvn -DskipTests compile
```

预期：BUILD SUCCESS。若 `AgenticServices.a2aBuilder` 返回类型无法直接参与 `.subAgents(...)`，以 LangChain4j `DefaultA2AClientBuilder` 的实际返回类型为准调整 interface 或 builder 调用，但不要恢复自研 registry。

- [ ] **步骤 8：Commit**

```bash
cd /Users/huajiang/Desktop/h-agent
git add backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a \
        backend/src/test/java/com/h/backend/chat/ai/a2a
git add -u backend/src/main/java/com/h/backend/chat/infrastructure/ai/a2a \
           backend/src/test/java/com/h/backend/chat/ai/a2a
git commit -m "refactor: use langchain4j a2a client for remote agents"
```

---

## 任务 4：实现 other-agents 的 A2A export model

**文件：**
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/export/A2AExportMethod.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/export/A2AAgentExport.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/export/A2AAgentExports.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/export/A2AAgentExportRegistry.java`
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/export/A2AAgentExportsTest.java`
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/export/A2AAgentExportRegistryTest.java`

- [ ] **步骤 1：编写失败测试，要求从 LangChain4j agent method 读取 `@V` 和 `@Agent`**

创建 `other-agents/src/test/java/com/h/otheragents/a2a/export/A2AAgentExportsTest.java`：

```java
package com.h.otheragents.a2a.export;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class A2AAgentExportsTest {

    interface DraftAgent {

        @Agent(name = "创意写作者", description = "根据主题生成故事初稿", outputKey = "story")
        String generate(@V("topic") String topic);
    }

    @Test
    void exportReadsMethodMetadataFromLangChain4jAnnotations() {
        DraftAgent bean = topic -> "draft:" + topic;

        A2AAgentExports exports = A2AAgentExports.builder()
                .export("creative-writer", bean, DraftAgent.class, "generate")
                .build();

        A2AAgentExport export = exports.list().getFirst();

        assertEquals("creative-writer", export.id());
        assertSame(bean, export.agentBean());
        assertEquals(DraftAgent.class, export.agentInterface());
        assertEquals("generate", export.method().method().getName());
        assertEquals(List.of("topic"), export.method().inputKeys());
        assertEquals("story", export.method().outputKey());
        assertEquals("创意写作者", export.method().publicName());
        assertEquals("根据主题生成故事初稿", export.method().publicDescription());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=A2AAgentExportsTest test
```

预期：编译失败，提示 `A2AAgentExports` 等类型不存在。

- [ ] **步骤 3：实现 A2AExportMethod**

创建 `other-agents/src/main/java/com/h/otheragents/a2a/export/A2AExportMethod.java`：

```java
package com.h.otheragents.a2a.export;

import java.lang.reflect.Method;
import java.util.List;

public record A2AExportMethod(
        Method method,
        List<String> inputKeys,
        Integer memoryIdParameterIndex,
        String outputKey,
        String publicName,
        String publicDescription
) {
}
```

- [ ] **步骤 4：实现 A2AAgentExport**

创建 `other-agents/src/main/java/com/h/otheragents/a2a/export/A2AAgentExport.java`：

```java
package com.h.otheragents.a2a.export;

public record A2AAgentExport(
        String id,
        Object agentBean,
        Class<?> agentInterface,
        A2AExportMethod method
) {
}
```

- [ ] **步骤 5：实现 A2AAgentExports builder**

创建 `other-agents/src/main/java/com/h/otheragents/a2a/export/A2AAgentExports.java`：

```java
package com.h.otheragents.a2a.export;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import dev.langchain4j.service.MemoryId;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

public class A2AAgentExports {

    private final List<A2AAgentExport> exports;

    private A2AAgentExports(List<A2AAgentExport> exports) {
        this.exports = List.copyOf(exports);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<A2AAgentExport> list() {
        return exports;
    }

    public static class Builder {

        private final List<A2AAgentExport> exports = new ArrayList<>();

        public Builder export(String id, Object agentBean, Class<?> agentInterface, String methodName) {
            Method method = findMethod(agentInterface, methodName);
            exports.add(new A2AAgentExport(id, agentBean, agentInterface, exportMethod(method, id)));
            return this;
        }

        public A2AAgentExports build() {
            return new A2AAgentExports(exports);
        }

        private static Method findMethod(Class<?> agentInterface, String methodName) {
            Method[] methods = agentInterface.getMethods();
            Method found = null;
            for (Method method : methods) {
                if (method.getName().equals(methodName)) {
                    if (found != null) {
                        throw new IllegalArgumentException("ambiguous A2A export method: " + methodName);
                    }
                    found = method;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException("A2A export method not found: " + methodName);
            }
            return found;
        }

        private static A2AExportMethod exportMethod(Method method, String fallbackName) {
            List<String> inputKeys = new ArrayList<>();
            Integer memoryIdParameterIndex = null;
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                V variable = parameters[i].getAnnotation(V.class);
                if (variable != null) {
                    inputKeys.add(variable.value());
                }
                if (parameters[i].getAnnotation(MemoryId.class) != null) {
                    memoryIdParameterIndex = i;
                }
            }

            Agent agent = method.getAnnotation(Agent.class);
            String outputKey = agent != null && !agent.outputKey().isBlank() ? agent.outputKey() : "response";
            String publicName = agent != null && !agent.name().isBlank() ? agent.name() : fallbackName;
            String publicDescription = agent != null && !agent.description().isBlank() ? agent.description() : fallbackName;
            return new A2AExportMethod(method, List.copyOf(inputKeys), memoryIdParameterIndex, outputKey, publicName, publicDescription);
        }
    }
}
```

当前 LangChain4j `@MemoryId` 包名是 `dev.langchain4j.service.MemoryId`。不要自己定义 `MemoryId` 注解。

- [ ] **步骤 6：实现 registry 并测试未知 agent 行为**

创建 `other-agents/src/test/java/com/h/otheragents/a2a/export/A2AAgentExportRegistryTest.java`：

```java
package com.h.otheragents.a2a.export;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class A2AAgentExportRegistryTest {

    interface EchoAgent {

        @Agent(outputKey = "response")
        String echo(@V("question") String question);
    }

    @Test
    void requireReturnsExportById() {
        EchoAgent bean = question -> "echo:" + question;
        A2AAgentExportRegistry registry = new A2AAgentExportRegistry(A2AAgentExports.builder()
                .export("echo", bean, EchoAgent.class, "echo")
                .build());

        assertEquals("echo", registry.require("echo").id());
    }

    @Test
    void requireRejectsUnknownAgentId() {
        A2AAgentExportRegistry registry = new A2AAgentExportRegistry(A2AAgentExports.builder().build());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> registry.require("missing"));

        assertEquals("A2A agent not found: missing", error.getMessage());
    }
}
```

创建 `other-agents/src/main/java/com/h/otheragents/a2a/export/A2AAgentExportRegistry.java`：

```java
package com.h.otheragents.a2a.export;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class A2AAgentExportRegistry {

    private final Map<String, A2AAgentExport> exportsById;

    public A2AAgentExportRegistry(A2AAgentExports exports) {
        Map<String, A2AAgentExport> map = new LinkedHashMap<>();
        for (A2AAgentExport export : exports.list()) {
            A2AAgentExport previous = map.putIfAbsent(export.id(), export);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate A2A agent id: " + export.id());
            }
        }
        this.exportsById = Map.copyOf(map);
    }

    public A2AAgentExport require(String agentId) {
        A2AAgentExport export = exportsById.get(agentId);
        if (export == null) {
            throw new IllegalArgumentException("A2A agent not found: " + agentId);
        }
        return export;
    }

    public Collection<A2AAgentExport> list() {
        return exportsById.values();
    }
}
```

- [ ] **步骤 7：运行 export 测试**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=A2AAgentExportsTest,A2AAgentExportRegistryTest test
```

预期：PASS。

- [ ] **步骤 8：Commit**

```bash
cd /Users/huajiang/Desktop/h-agent
git add other-agents/src/main/java/com/h/otheragents/a2a/export \
        other-agents/src/test/java/com/h/otheragents/a2a/export
git commit -m "feat: add a2a agent export registry"
```

---

## 任务 5：由 export model 生成 agent-card

**文件：**
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2AAgentCardFactory.java`
- 修改：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/config/OtherAgentsA2AProperties.java`
- 删除：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/application/A2AAgentCardApplicationService.java`
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/server/A2AAgentCardFactoryTest.java`

- [ ] **步骤 1：编写失败测试，要求统一 URL 生成 card**

创建 `other-agents/src/test/java/com/h/otheragents/a2a/server/A2AAgentCardFactoryTest.java`：

```java
package com.h.otheragents.a2a.server;

import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.export.A2AAgentExport;
import com.h.otheragents.a2a.export.A2AAgentExports;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class A2AAgentCardFactoryTest {

    interface DraftAgent {

        @Agent(name = "创意写作者", description = "根据主题生成故事初稿", outputKey = "story")
        String generate(@V("topic") String topic);
    }

    @Test
    void cardUsesExportMetadataAndUnifiedEndpoint() {
        OtherAgentsA2AProperties properties = new OtherAgentsA2AProperties();
        properties.setPublicUrl("http://localhost:8082/");
        DraftAgent bean = topic -> "draft:" + topic;
        A2AAgentExport export = A2AAgentExports.builder()
                .export("creative-writer", bean, DraftAgent.class, "generate")
                .build()
                .list()
                .getFirst();

        AgentCard card = new A2AAgentCardFactory(properties).card(export);

        assertEquals("creative-writer", card.name());
        assertEquals("根据主题生成故事初稿", card.description());
        assertEquals("http://localhost:8082/a2a/agents/creative-writer", card.url());
        assertFalse(card.capabilities().streaming());
        assertEquals("creative-writer", card.skills().getFirst().id());
        assertEquals("创意写作者", card.skills().getFirst().name());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=A2AAgentCardFactoryTest test
```

预期：编译失败，提示 `A2AAgentCardFactory` 不存在，或 `OtherAgentsA2AProperties.agentUrl` 不存在。

- [ ] **步骤 3：补充 other-agents properties helper**

在 `other-agents/src/main/java/com/h/otheragents/a2a/config/OtherAgentsA2AProperties.java` 保留现有字段，并确保有以下方法：

```java
public String agentUrl(String agentId) {
    return normalizedPublicUrl() + "/a2a/agents/" + agentId;
}
```

`normalizedPublicUrl()` 应继续处理尾部 `/`。

- [ ] **步骤 4：实现 A2AAgentCardFactory**

创建 `other-agents/src/main/java/com/h/otheragents/a2a/server/A2AAgentCardFactory.java`：

```java
package com.h.otheragents.a2a.server;

import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.export.A2AAgentExport;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentProvider;
import io.a2a.spec.AgentSkill;

import java.util.List;

public class A2AAgentCardFactory {

    private final OtherAgentsA2AProperties properties;

    public A2AAgentCardFactory(OtherAgentsA2AProperties properties) {
        this.properties = properties;
    }

    public AgentCard card(A2AAgentExport export) {
        String baseUrl = properties.normalizedPublicUrl();
        return new AgentCard.Builder()
                .name(export.id())
                .description(export.method().publicDescription())
                .url(properties.agentUrl(export.id()))
                .provider(new AgentProvider("h-agent other-agents", baseUrl))
                .version("0.1.0")
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .stateTransitionHistory(false)
                        .build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of(new AgentSkill.Builder()
                        .id(export.id())
                        .name(export.method().publicName())
                        .description(export.method().publicDescription())
                        .tags(List.of("langchain4j", "a2a"))
                        .examples(List.of(String.join(", ", export.method().inputKeys())))
                        .inputModes(List.of("text/plain"))
                        .outputModes(List.of("text/plain"))
                        .build()))
                .build();
    }
}
```

- [ ] **步骤 5：删除旧 card service**

删除：

```bash
rm other-agents/src/main/java/com/h/otheragents/a2a/application/A2AAgentCardApplicationService.java
```

旧 controller 会在任务 8 替换；如果此时编译失败，先只运行 `A2AAgentCardFactoryTest`，不要提前修 controller。

- [ ] **步骤 6：运行测试验证通过**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=A2AAgentCardFactoryTest test
```

预期：PASS。

- [ ] **步骤 7：Commit**

```bash
cd /Users/huajiang/Desktop/h-agent
git add other-agents/src/main/java/com/h/otheragents/a2a/server/A2AAgentCardFactory.java \
        other-agents/src/main/java/com/h/otheragents/a2a/config/OtherAgentsA2AProperties.java \
        other-agents/src/test/java/com/h/otheragents/a2a/server/A2AAgentCardFactoryTest.java
git add -u other-agents/src/main/java/com/h/otheragents/a2a/application
git commit -m "feat: generate a2a agent cards from exports"
```

---

## 任务 6：实现 invocation context、message mapper、task store 和 method invoker

**文件：**
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2AInvocationContext.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2ATaskRecord.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2ATaskStore.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/InMemoryA2ATaskStore.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2AMessageMapper.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/LangChain4jAgentMethodInvoker.java`
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/server/LangChain4jAgentMethodInvokerTest.java`

- [ ] **步骤 1：编写失败测试，要求按 `@V` 映射 text parts 调用 bean**

创建 `other-agents/src/test/java/com/h/otheragents/a2a/server/LangChain4jAgentMethodInvokerTest.java`：

```java
package com.h.otheragents.a2a.server;

import com.h.otheragents.a2a.export.A2AAgentExport;
import com.h.otheragents.a2a.export.A2AAgentExports;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LangChain4jAgentMethodInvokerTest {

    interface AudienceAgent {

        @Agent(outputKey = "story")
        String edit(@V("story") String story, @V("audience") String audience);
    }

    @Test
    void invokesAgentBeanWithTextPartsInVParameterOrder() {
        AudienceAgent bean = (story, audience) -> "edited:" + story + ":" + audience;
        A2AAgentExport export = A2AAgentExports.builder()
                .export("audience-editor", bean, AudienceAgent.class, "edit")
                .build()
                .list()
                .getFirst();

        A2AInvocationContext context = new A2AInvocationContext(
                "audience-editor",
                "context-1",
                "task-1",
                "user-1",
                "session-1",
                "memory-1"
        );

        String result = new LangChain4jAgentMethodInvoker().invoke(export, context, List.of("原故事", "儿童"));

        assertEquals("edited:原故事:儿童", result);
    }

    @Test
    void invocationContextReadsOptionalMetadata() {
        A2AInvocationContext context = A2AInvocationContext.fromMetadata(
                "creative-writer",
                "context-1",
                "task-1",
                Map.of("userId", "u1", "sessionId", "s1", "memoryId", "m1")
        );

        assertEquals("u1", context.userId());
        assertEquals("s1", context.sessionId());
        assertEquals("m1", context.memoryId());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=LangChain4jAgentMethodInvokerTest test
```

预期：编译失败，提示 context/invoker 类型不存在。

- [ ] **步骤 3：实现 A2AInvocationContext**

创建 `other-agents/src/main/java/com/h/otheragents/a2a/server/A2AInvocationContext.java`：

```java
package com.h.otheragents.a2a.server;

import java.util.Map;

public record A2AInvocationContext(
        String agentId,
        String contextId,
        String taskId,
        String userId,
        String sessionId,
        String memoryId
) {

    public static A2AInvocationContext fromMetadata(
            String agentId,
            String contextId,
            String taskId,
            Map<String, Object> metadata
    ) {
        return new A2AInvocationContext(
                agentId,
                contextId,
                taskId,
                stringValue(metadata, "userId"),
                stringValue(metadata, "sessionId"),
                stringValue(metadata, "memoryId")
        );
    }

    public String memoryKey() {
        if (memoryId != null && !memoryId.isBlank()) {
            return memoryId;
        }
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        return contextId;
    }

    private static String stringValue(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }
}
```

- [ ] **步骤 4：实现 task store**

创建 `A2ATaskRecord.java`：

```java
package com.h.otheragents.a2a.server;

import java.time.Instant;

public record A2ATaskRecord(
        String taskId,
        String contextId,
        String agentId,
        String state,
        String lastText,
        Instant updatedAt
) {
}
```

创建 `A2ATaskStore.java`：

```java
package com.h.otheragents.a2a.server;

import java.util.Optional;

public interface A2ATaskStore {

    A2ATaskRecord save(A2ATaskRecord record);

    Optional<A2ATaskRecord> find(String taskId);
}
```

创建 `InMemoryA2ATaskStore.java`：

```java
package com.h.otheragents.a2a.server;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryA2ATaskStore implements A2ATaskStore {

    private final ConcurrentMap<String, A2ATaskRecord> tasks = new ConcurrentHashMap<>();

    @Override
    public A2ATaskRecord save(A2ATaskRecord record) {
        tasks.put(record.taskId(), record);
        return record;
    }

    @Override
    public Optional<A2ATaskRecord> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }
}
```

- [ ] **步骤 5：实现 method invoker**

创建 `LangChain4jAgentMethodInvoker.java`：

```java
package com.h.otheragents.a2a.server;

import com.h.otheragents.a2a.export.A2AAgentExport;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.util.List;

public class LangChain4jAgentMethodInvoker {

    public String invoke(A2AAgentExport export, A2AInvocationContext context, List<String> textParts) {
        Object[] args = new Object[export.method().method().getParameterCount()];
        Parameter[] parameters = export.method().method().getParameters();
        int textIndex = 0;
        for (int i = 0; i < parameters.length; i++) {
            if (export.method().memoryIdParameterIndex() != null && export.method().memoryIdParameterIndex() == i) {
                args[i] = context.memoryKey();
            } else {
                if (textIndex >= textParts.size()) {
                    throw new IllegalArgumentException("message parts must contain required text");
                }
                args[i] = textParts.get(textIndex++);
            }
        }
        try {
            Object result = export.method().method().invoke(export.agentBean(), args);
            return result == null ? "" : result.toString();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("A2A agent method is not accessible: " + export.id(), e);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("A2A agent method failed: " + export.id(), target);
        }
    }
}
```

- [ ] **步骤 6：实现 A2AMessageMapper 的 text 提取骨架**

创建 `A2AMessageMapper.java`：

```java
package com.h.otheragents.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public class A2AMessageMapper {

    public List<String> textParts(JsonNode message) {
        List<String> texts = new ArrayList<>();
        JsonNode parts = message.path("parts");
        if (!parts.isArray()) {
            return texts;
        }
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                texts.add(text);
            }
        }
        return texts;
    }
}
```

- [ ] **步骤 7：运行 invoker 测试**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=LangChain4jAgentMethodInvokerTest test
```

预期：PASS。

- [ ] **步骤 8：Commit**

```bash
cd /Users/huajiang/Desktop/h-agent
git add other-agents/src/main/java/com/h/otheragents/a2a/server \
        other-agents/src/test/java/com/h/otheragents/a2a/server/LangChain4jAgentMethodInvokerTest.java
git commit -m "feat: add a2a invocation and task primitives"
```

---

## 任务 7：实现 A2A executor 和 JSON-RPC transport wrapper

**文件：**
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2AAgentExecutor.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2ARequestHandler.java`
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/JsonRpcA2ATransportWrapper.java`
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/server/A2AAgentExecutorTest.java`
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/server/JsonRpcA2ATransportWrapperTest.java`

- [ ] **步骤 1：编写失败测试，要求 executor 创建 task/context 并返回 task JSON**

创建 `other-agents/src/test/java/com/h/otheragents/a2a/server/A2AAgentExecutorTest.java`：

```java
package com.h.otheragents.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.otheragents.a2a.export.A2AAgentExportRegistry;
import com.h.otheragents.a2a.export.A2AAgentExports;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class A2AAgentExecutorTest {

    interface DraftAgent {

        @Agent(outputKey = "story")
        String generate(@V("topic") String topic);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesAgentAndReturnsCompletedTask() throws Exception {
        DraftAgent bean = topic -> "draft:" + topic;
        A2AAgentExportRegistry registry = new A2AAgentExportRegistry(A2AAgentExports.builder()
                .export("creative-writer", bean, DraftAgent.class, "generate")
                .build());
        A2AAgentExecutor executor = new A2AAgentExecutor(
                registry,
                new LangChain4jAgentMethodInvoker(),
                new A2AMessageMapper(),
                new InMemoryA2ATaskStore()
        );

        JsonNode message = objectMapper.readTree("""
                {
                  "role": "user",
                  "contextId": "context-1",
                  "parts": [{"kind": "text", "text": "月球救援"}],
                  "metadata": {"userId": "u1"}
                }
                """);

        JsonNode task = executor.execute("creative-writer", message);

        assertEquals("task", task.path("kind").asText());
        assertFalse(task.path("id").asText().isBlank());
        assertEquals("context-1", task.path("contextId").asText());
        assertEquals("completed", task.path("status").path("state").asText());
        assertEquals("draft:月球救援", task.path("artifacts").get(0).path("parts").get(0).path("text").asText());
    }
}
```

- [ ] **步骤 2：运行 executor 测试验证失败**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=A2AAgentExecutorTest test
```

预期：编译失败，提示 `A2AAgentExecutor` 不存在。

- [ ] **步骤 3：实现 A2AAgentExecutor**

创建 `other-agents/src/main/java/com/h/otheragents/a2a/server/A2AAgentExecutor.java`：

```java
package com.h.otheragents.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.h.otheragents.a2a.export.A2AAgentExport;
import com.h.otheragents.a2a.export.A2AAgentExportRegistry;

import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class A2AAgentExecutor {

    private final A2AAgentExportRegistry registry;
    private final LangChain4jAgentMethodInvoker methodInvoker;
    private final A2AMessageMapper messageMapper;
    private final A2ATaskStore taskStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public A2AAgentExecutor(
            A2AAgentExportRegistry registry,
            LangChain4jAgentMethodInvoker methodInvoker,
            A2AMessageMapper messageMapper,
            A2ATaskStore taskStore
    ) {
        this.registry = registry;
        this.methodInvoker = methodInvoker;
        this.messageMapper = messageMapper;
        this.taskStore = taskStore;
    }

    public JsonNode execute(String agentId, JsonNode message) {
        A2AAgentExport export = registry.require(agentId);
        String contextId = stringOrGenerated(message.path("contextId").asText(null));
        String taskId = stringOrGenerated(message.path("taskId").asText(null));
        A2AInvocationContext context = A2AInvocationContext.fromMetadata(
                agentId,
                contextId,
                taskId,
                metadata(message.path("metadata"))
        );
        try {
            String text = methodInvoker.invoke(export, context, messageMapper.textParts(message));
            taskStore.save(new A2ATaskRecord(taskId, contextId, agentId, "completed", text, Instant.now()));
            return completedTask(taskId, contextId, text);
        } catch (RuntimeException error) {
            taskStore.save(new A2ATaskRecord(taskId, contextId, agentId, "failed", error.getMessage(), Instant.now()));
            return failedTask(taskId, contextId, error.getMessage());
        }
    }

    private JsonNode completedTask(String taskId, String contextId, String text) {
        ObjectNode task = baseTask(taskId, contextId, "completed");
        ArrayNode artifacts = task.putArray("artifacts");
        ObjectNode artifact = artifacts.addObject();
        artifact.put("artifactId", "artifact-" + taskId);
        ArrayNode parts = artifact.putArray("parts");
        ObjectNode part = parts.addObject();
        part.put("kind", "text");
        part.put("text", text);
        return task;
    }

    private JsonNode failedTask(String taskId, String contextId, String message) {
        ObjectNode task = baseTask(taskId, contextId, "failed");
        ObjectNode statusMessage = task.withObject("/status/message");
        statusMessage.put("role", "agent");
        ArrayNode parts = statusMessage.putArray("parts");
        ObjectNode part = parts.addObject();
        part.put("kind", "text");
        part.put("text", message == null ? "A2A agent execution failed" : message);
        return task;
    }

    private ObjectNode baseTask(String taskId, String contextId, String state) {
        ObjectNode task = objectMapper.createObjectNode();
        task.put("kind", "task");
        task.put("id", taskId);
        task.put("contextId", contextId);
        ObjectNode status = task.putObject("status");
        status.put("state", state);
        return task;
    }

    private static String stringOrGenerated(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    private static Map<String, Object> metadata(JsonNode metadata) {
        Map<String, Object> values = new HashMap<>();
        if (metadata == null || !metadata.isObject()) {
            return values;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = metadata.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            values.put(field.getKey(), field.getValue().asText());
        }
        return values;
    }
}
```

- [ ] **步骤 4：编写失败测试，要求 transport 保留 JSON-RPC id 并分发 message/send**

创建 `other-agents/src/test/java/com/h/otheragents/a2a/server/JsonRpcA2ATransportWrapperTest.java`：

```java
package com.h.otheragents.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonRpcA2ATransportWrapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesRpcIdAndWrapsHandlerResult() throws Exception {
        A2ARequestHandler handler = (agentId, method, params) -> {
            JsonNode message = params.path("message");
            return objectMapper.readTree("""
                    {
                      "kind": "task",
                      "id": "task-1",
                      "contextId": "context-1",
                      "status": {"state": "completed"}
                    }
                    """);
        };
        JsonRpcA2ATransportWrapper wrapper = new JsonRpcA2ATransportWrapper(handler);

        JsonNode response = wrapper.handle("creative-writer", objectMapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": "rpc-1",
                  "method": "message/send",
                  "params": {"message": {"parts": []}}
                }
                """));

        assertEquals("2.0", response.path("jsonrpc").asText());
        assertEquals("rpc-1", response.path("id").asText());
        assertEquals("task-1", response.path("result").path("id").asText());
    }

    @Test
    void unsupportedMethodReturnsMethodNotFound() throws Exception {
        JsonRpcA2ATransportWrapper wrapper = new JsonRpcA2ATransportWrapper((agentId, method, params) -> objectMapper.nullNode());

        JsonNode response = wrapper.handle("creative-writer", objectMapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": 7,
                  "method": "tasks/cancel",
                  "params": {}
                }
                """));

        assertEquals(7, response.path("id").asInt());
        assertEquals(-32601, response.path("error").path("code").asInt());
    }
}
```

- [ ] **步骤 5：实现 A2ARequestHandler**

创建 `other-agents/src/main/java/com/h/otheragents/a2a/server/A2ARequestHandler.java`：

```java
package com.h.otheragents.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface A2ARequestHandler {

    JsonNode handle(String agentId, String method, JsonNode params);

    static A2ARequestHandler messageSend(A2AAgentExecutor executor) {
        return (agentId, method, params) -> executor.execute(agentId, params.path("message"));
    }
}
```

- [ ] **步骤 6：实现 JsonRpcA2ATransportWrapper**

创建 `other-agents/src/main/java/com/h/otheragents/a2a/server/JsonRpcA2ATransportWrapper.java`：

```java
package com.h.otheragents.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class JsonRpcA2ATransportWrapper {

    public static final String MESSAGE_SEND = "message/send";

    private final A2ARequestHandler requestHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonRpcA2ATransportWrapper(A2ARequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    public JsonNode handle(String agentId, JsonNode request) {
        JsonNode id = request.path("id");
        String method = request.path("method").asText("");
        if (!MESSAGE_SEND.equals(method)) {
            return error(id, -32601, "Method not found");
        }
        JsonNode params = request.path("params");
        if (!params.has("message")) {
            return error(id, -32600, "Invalid request");
        }
        try {
            return success(id, requestHandler.handle(agentId, method, params));
        } catch (IllegalArgumentException error) {
            return error(id, -32600, error.getMessage());
        } catch (RuntimeException error) {
            return error(id, -32603, error.getMessage());
        }
    }

    private ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id.isMissingNode() ? objectMapper.nullNode() : id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id.isMissingNode() ? objectMapper.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }
}
```

- [ ] **步骤 7：运行 executor/transport 测试**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=A2AAgentExecutorTest,JsonRpcA2ATransportWrapperTest test
```

预期：PASS。

- [ ] **步骤 8：Commit**

```bash
cd /Users/huajiang/Desktop/h-agent
git add other-agents/src/main/java/com/h/otheragents/a2a/server \
        other-agents/src/test/java/com/h/otheragents/a2a/server/A2AAgentExecutorTest.java \
        other-agents/src/test/java/com/h/otheragents/a2a/server/JsonRpcA2ATransportWrapperTest.java
git commit -m "feat: add a2a executor and json rpc transport"
```

---

## 任务 8：接入 Spring Controller 和 Java 配置注册

**文件：**
- 创建：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/server/A2AAgentServer.java`
- 修改：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/config/A2AAgentConfig.java`
- 修改：`/Users/huajiang/Desktop/h-agent/other-agents/src/main/java/com/h/otheragents/a2a/interfaces/web/A2AController.java`
- 测试：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/interfaces/web/A2AControllerTest.java`

- [ ] **步骤 1：重写 controller 测试，要求统一 endpoint**

把 `other-agents/src/test/java/com/h/otheragents/a2a/interfaces/web/A2AControllerTest.java` 改为：

```java
package com.h.otheragents.a2a.interfaces.web;

import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.export.A2AAgentExportRegistry;
import com.h.otheragents.a2a.export.A2AAgentExports;
import com.h.otheragents.a2a.server.A2AAgentServer;
import com.h.otheragents.a2a.server.A2AMessageMapper;
import com.h.otheragents.a2a.server.InMemoryA2ATaskStore;
import com.h.otheragents.a2a.server.LangChain4jAgentMethodInvoker;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class A2AControllerTest {

    interface DraftAgent {

        @Agent(name = "创意写作者", description = "根据主题生成故事初稿", outputKey = "story")
        String generate(@V("topic") String topic);
    }

    private final WebTestClient client = WebTestClient.bindToController(controller()).build();

    @Test
    void agentCardUsesUnifiedEndpoint() {
        client.get()
                .uri("/a2a/agents/creative-writer/.well-known/agent-card.json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("creative-writer")
                .jsonPath("$.url").isEqualTo("http://localhost:8082/a2a/agents/creative-writer")
                .jsonPath("$.skills[0].id").isEqualTo("creative-writer");
    }

    @Test
    void messageSendCallsExportedAgent() {
        client.post()
                .uri("/a2a/agents/creative-writer")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(messageSendRequest("rpc-1", "月球救援"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("rpc-1")
                .jsonPath("$.result.kind").isEqualTo("task")
                .jsonPath("$.result.status.state").isEqualTo("completed")
                .jsonPath("$.result.artifacts[0].parts[0].text").isEqualTo("draft:月球救援");
    }

    @Test
    void oldEndpointIsNotMapped() {
        client.get()
                .uri("/creative-writer/.well-known/agent-card.json")
                .exchange()
                .expectStatus().isNotFound();
    }

    private static A2AController controller() {
        OtherAgentsA2AProperties properties = new OtherAgentsA2AProperties();
        DraftAgent bean = topic -> "draft:" + topic;
        A2AAgentExportRegistry registry = new A2AAgentExportRegistry(A2AAgentExports.builder()
                .export("creative-writer", bean, DraftAgent.class, "generate")
                .build());
        A2AAgentServer server = A2AAgentServer.create(
                properties,
                registry,
                new LangChain4jAgentMethodInvoker(),
                new A2AMessageMapper(),
                new InMemoryA2ATaskStore()
        );
        return new A2AController(server);
    }

    private static String messageSendRequest(String id, String text) {
        return """
                {
                  "jsonrpc": "2.0",
                  "id": "%s",
                  "method": "message/send",
                  "params": {
                    "message": {
                      "role": "user",
                      "parts": [
                        {
                          "kind": "text",
                          "text": "%s"
                        }
                      ],
                      "messageId": "user-message"
                    }
                  }
                }
                """.formatted(id, text);
    }
}
```

- [ ] **步骤 2：运行 controller 测试验证失败**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=A2AControllerTest test
```

预期：编译失败或测试失败，因为 controller 仍使用旧路由和旧 card service。

- [ ] **步骤 3：实现 A2AAgentServer facade**

创建 `other-agents/src/main/java/com/h/otheragents/a2a/server/A2AAgentServer.java`：

```java
package com.h.otheragents.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.export.A2AAgentExportRegistry;
import io.a2a.spec.AgentCard;

public class A2AAgentServer {

    private final A2AAgentExportRegistry registry;
    private final A2AAgentCardFactory cardFactory;
    private final JsonRpcA2ATransportWrapper transportWrapper;

    private A2AAgentServer(
            A2AAgentExportRegistry registry,
            A2AAgentCardFactory cardFactory,
            JsonRpcA2ATransportWrapper transportWrapper
    ) {
        this.registry = registry;
        this.cardFactory = cardFactory;
        this.transportWrapper = transportWrapper;
    }

    public static A2AAgentServer create(
            OtherAgentsA2AProperties properties,
            A2AAgentExportRegistry registry,
            LangChain4jAgentMethodInvoker methodInvoker,
            A2AMessageMapper messageMapper,
            A2ATaskStore taskStore
    ) {
        A2AAgentExecutor executor = new A2AAgentExecutor(registry, methodInvoker, messageMapper, taskStore);
        return new A2AAgentServer(
                registry,
                new A2AAgentCardFactory(properties),
                new JsonRpcA2ATransportWrapper(A2ARequestHandler.messageSend(executor))
        );
    }

    public AgentCard card(String agentId) {
        return cardFactory.card(registry.require(agentId));
    }

    public JsonNode handle(String agentId, JsonNode request) {
        registry.require(agentId);
        return transportWrapper.handle(agentId, request);
    }
}
```

- [ ] **步骤 4：替换 A2AController**

把 `other-agents/src/main/java/com/h/otheragents/a2a/interfaces/web/A2AController.java` 改为：

```java
package com.h.otheragents.a2a.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.h.otheragents.a2a.server.A2AAgentServer;
import io.a2a.spec.AgentCard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class A2AController {

    private final A2AAgentServer agentServer;

    public A2AController(A2AAgentServer agentServer) {
        this.agentServer = agentServer;
    }

    @GetMapping("/a2a/agents/{agentId}/.well-known/agent-card.json")
    public AgentCard agentCard(@PathVariable String agentId) {
        return agentServer.card(agentId);
    }

    @PostMapping("/a2a/agents/{agentId}")
    public JsonNode jsonRpc(@PathVariable String agentId, @RequestBody JsonNode request) {
        return agentServer.handle(agentId, request);
    }
}
```

- [ ] **步骤 5：在 A2AAgentConfig 注册 exports 和 server beans**

在 `other-agents/src/main/java/com/h/otheragents/a2a/config/A2AAgentConfig.java` 保留三个 LangChain4j agent bean，并新增：

```java
@Bean
public com.h.otheragents.a2a.export.A2AAgentExports a2aAgentExports(
        Agents.CreativeWriter creativeWriter,
        Agents.AudienceEditor audienceEditor,
        Agents.StyleEditor styleEditor
) {
    return com.h.otheragents.a2a.export.A2AAgentExports.builder()
            .export("creative-writer", creativeWriter, Agents.CreativeWriter.class, "generateStory")
            .export("audience-editor", audienceEditor, Agents.AudienceEditor.class, "editStory")
            .export("style-editor", styleEditor, Agents.StyleEditor.class, "editStory")
            .build();
}

@Bean
public com.h.otheragents.a2a.export.A2AAgentExportRegistry a2aAgentExportRegistry(
        com.h.otheragents.a2a.export.A2AAgentExports exports
) {
    return new com.h.otheragents.a2a.export.A2AAgentExportRegistry(exports);
}

@Bean
public com.h.otheragents.a2a.server.A2AAgentServer a2aAgentServer(
        OtherAgentsA2AProperties properties,
        com.h.otheragents.a2a.export.A2AAgentExportRegistry registry
) {
    return com.h.otheragents.a2a.server.A2AAgentServer.create(
            properties,
            registry,
            new com.h.otheragents.a2a.server.LangChain4jAgentMethodInvoker(),
            new com.h.otheragents.a2a.server.A2AMessageMapper(),
            new com.h.otheragents.a2a.server.InMemoryA2ATaskStore()
    );
}
```

- [ ] **步骤 6：运行 controller 测试**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=A2AControllerTest test
```

预期：PASS。

- [ ] **步骤 7：运行 other-agents 编译**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -DskipTests compile
```

预期：BUILD SUCCESS。

- [ ] **步骤 8：Commit**

```bash
cd /Users/huajiang/Desktop/h-agent
git add other-agents/src/main/java/com/h/otheragents/a2a \
        other-agents/src/test/java/com/h/otheragents/a2a/interfaces/web/A2AControllerTest.java
git add -u other-agents/src/main/java/com/h/otheragents/a2a
git commit -m "feat: expose langchain4j agents through a2a rpc"
```

---

## 任务 9：补齐 contextId/taskId 和 metadata 行为测试

**文件：**
- 修改：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/server/A2AAgentExecutorTest.java`
- 修改：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/server/LangChain4jAgentMethodInvokerTest.java`

- [ ] **步骤 1：增加 taskId 复用测试**

在 `A2AAgentExecutorTest` 添加：

```java
@Test
void reusesIncomingTaskIdAndContextId() throws Exception {
    DraftAgent bean = topic -> "draft:" + topic;
    A2AAgentExportRegistry registry = new A2AAgentExportRegistry(A2AAgentExports.builder()
            .export("creative-writer", bean, DraftAgent.class, "generate")
            .build());
    A2AAgentExecutor executor = new A2AAgentExecutor(
            registry,
            new LangChain4jAgentMethodInvoker(),
            new A2AMessageMapper(),
            new InMemoryA2ATaskStore()
    );

    JsonNode message = objectMapper.readTree("""
            {
              "role": "user",
              "contextId": "context-existing",
              "taskId": "task-existing",
              "parts": [{"kind": "text", "text": "月球救援"}]
            }
            """);

    JsonNode task = executor.execute("creative-writer", message);

    assertEquals("task-existing", task.path("id").asText());
    assertEquals("context-existing", task.path("contextId").asText());
}
```

- [ ] **步骤 2：增加 metadata 缺失不报错测试**

在 `A2AAgentExecutorTest` 添加：

```java
@Test
void missingMetadataDoesNotBreakExecution() throws Exception {
    DraftAgent bean = topic -> "draft:" + topic;
    A2AAgentExportRegistry registry = new A2AAgentExportRegistry(A2AAgentExports.builder()
            .export("creative-writer", bean, DraftAgent.class, "generate")
            .build());
    A2AAgentExecutor executor = new A2AAgentExecutor(
            registry,
            new LangChain4jAgentMethodInvoker(),
            new A2AMessageMapper(),
            new InMemoryA2ATaskStore()
    );

    JsonNode message = objectMapper.readTree("""
            {
              "role": "user",
              "parts": [{"kind": "text", "text": "月球救援"}]
            }
            """);

    JsonNode task = executor.execute("creative-writer", message);

    assertEquals("completed", task.path("status").path("state").asText());
    assertFalse(task.path("contextId").asText().isBlank());
    assertFalse(task.path("id").asText().isBlank());
}
```

- [ ] **步骤 3：增加 memory key fallback 测试**

在 `LangChain4jAgentMethodInvokerTest` 添加：

```java
@Test
void memoryKeyFallsBackFromMemoryIdToSessionIdToContextId() {
    assertEquals("memory-1", new A2AInvocationContext("a", "context-1", "task-1", null, "session-1", "memory-1").memoryKey());
    assertEquals("session-1", new A2AInvocationContext("a", "context-1", "task-1", null, "session-1", null).memoryKey());
    assertEquals("context-1", new A2AInvocationContext("a", "context-1", "task-1", null, null, null).memoryKey());
}
```

- [ ] **步骤 4：运行 metadata/context 测试**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=A2AAgentExecutorTest,LangChain4jAgentMethodInvokerTest test
```

预期：PASS。

- [ ] **步骤 5：Commit**

```bash
cd /Users/huajiang/Desktop/h-agent
git add other-agents/src/test/java/com/h/otheragents/a2a/server/A2AAgentExecutorTest.java \
        other-agents/src/test/java/com/h/otheragents/a2a/server/LangChain4jAgentMethodInvokerTest.java \
        other-agents/src/main/java/com/h/otheragents/a2a/server
git commit -m "test: cover a2a context task and metadata handling"
```

---

## 任务 10：端到端验证 LangChain4j client 可调用 other-agents A2A server

**文件：**
- 修改：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/contract/LangChain4jA2AClientContractTest.java`
- 可选测试辅助类：`/Users/huajiang/Desktop/h-agent/other-agents/src/test/java/com/h/otheragents/a2a/contract/TestA2AClientAgents.java`

- [ ] **步骤 1：增加 LangChain4j client 端到端测试**

在 `LangChain4jA2AClientContractTest` 中新增 interface 和测试。该测试用 Spring `WebTestClient` 或随机端口启动 controller，如果现有测试框架不方便起完整 Spring context，使用 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`：

```java
interface ClientCreativeWriter {

    @dev.langchain4j.agentic.Agent(outputKey = "story")
    String generateStory(@dev.langchain4j.service.V("topic") String topic);
}
```

新增测试：

```java
@Test
void langChain4jA2AClientCanCallOtherAgentsEndpoint() {
    String serverUrl = "http://localhost:" + port + "/a2a/agents/creative-writer";

    ClientCreativeWriter writer = dev.langchain4j.agentic.AgenticServices
            .a2aBuilder(serverUrl, ClientCreativeWriter.class)
            .outputKey("story")
            .build();

    String story = writer.generateStory("月球救援");

    assertTrue(story != null && !story.isBlank());
}
```

如果测试启动真实 `ChatModel` 会触发外部模型调用，则不要把本测试接入生产 `A2AAgentConfig`。在测试内定义 `@TestConfiguration`，注册假的 `Agents.CreativeWriter`、`Agents.AudienceEditor`、`Agents.StyleEditor` bean，并注册同一套 `A2AAgentExports/A2AAgentServer`：

```java
@TestConfiguration
static class TestA2AConfig {

    @Bean
    Agents.CreativeWriter creativeWriter() {
        return topic -> "draft:" + topic;
    }

    @Bean
    Agents.AudienceEditor audienceEditor() {
        return (story, audience) -> "audience:" + story + ":" + audience;
    }

    @Bean
    Agents.StyleEditor styleEditor() {
        return (story, style) -> "style:" + story + ":" + style;
    }
}
```

- [ ] **步骤 2：运行端到端测试验证失败或暴露兼容问题**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=LangChain4jA2AClientContractTest test
```

预期：第一次可能失败。常见失败和处理：

- agent-card 路径不匹配：确认 `AgentCard.url` 是 `/a2a/agents/{agentId}`，client builder 传入同一个 URL。
- result 解析失败：确认 response 是 JSON-RPC `result`，其中 `kind` 是 `task`，task 有 `status.state=completed`，artifact 的 text part 使用 `kind=text` 和 `text` 字段。
- message role 枚举不匹配：按 LangChain4j client 发送的 request JSON 调整 server 对 role 的宽容解析，server 不应依赖固定大小写。

- [ ] **步骤 3：修正 wire shape 直到 LangChain4j client 端到端通过**

改动只允许发生在以下文件：

```text
other-agents/src/main/java/com/h/otheragents/a2a/server/A2AAgentCardFactory.java
other-agents/src/main/java/com/h/otheragents/a2a/server/A2AAgentExecutor.java
other-agents/src/main/java/com/h/otheragents/a2a/server/A2AMessageMapper.java
other-agents/src/main/java/com/h/otheragents/a2a/server/JsonRpcA2ATransportWrapper.java
```

不要改 LangChain4j 源码，不要恢复 backend 自研 client。

- [ ] **步骤 4：运行端到端测试验证通过**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn -Dtest=LangChain4jA2AClientContractTest test
```

预期：PASS。

- [ ] **步骤 5：Commit**

```bash
cd /Users/huajiang/Desktop/h-agent
git add other-agents/src/test/java/com/h/otheragents/a2a/contract \
        other-agents/src/main/java/com/h/otheragents/a2a/server
git commit -m "test: verify langchain4j a2a client compatibility"
```

---

## 任务 11：全量验证与旧代码扫描

**文件：**
- 可能修改：`/Users/huajiang/Desktop/h-agent/backend/pom.xml`
- 可能修改：`/Users/huajiang/Desktop/h-agent/other-agents/pom.xml`
- 可能修改：`/Users/huajiang/Desktop/h-agent/docs/superpowers/specs/2026-07-02-a2a-adapter-redesign.md`

- [ ] **步骤 1：扫描旧实现残留**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent
rg -n "RemoteCreativeWriterAgent|RemoteAudienceEditorAgent|RemoteStyleEditorAgent|A2ARemoteAgentRegistry|A2ARemoteAgentInvoker|OtherAgentsA2AClient|remote-agents|/creative-writer/a2a|/audience-editor/a2a|/style-editor/a2a" backend other-agents
```

预期：无输出。  
如果输出来自测试快照或文档迁移说明，逐条判断；生产代码不能再引用这些旧实现。

- [ ] **步骤 2：运行 backend 测试**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/backend
mvn test
```

预期：BUILD SUCCESS。

- [ ] **步骤 3：运行 other-agents 测试**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn test
```

预期：BUILD SUCCESS。

- [ ] **步骤 4：检查依赖版本一致性**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent
rg -n "<langchain4j.version>|langchain4j-agentic-a2a|1\\.17\\.0-beta27|1\\.17\\.0" backend/pom.xml other-agents/pom.xml
```

预期：

```text
backend/pom.xml 使用 langchain4j.version=1.17.0
backend/pom.xml 使用 langchain4j-agentic-a2a ${langchain4j.version}-beta27
other-agents/pom.xml 使用 langchain4j.version=1.17.0
other-agents/pom.xml 使用 langchain4j-agentic-a2a ${langchain4j.version}-beta27
```

- [ ] **步骤 5：启动 other-agents 做手动 smoke test**

运行：

```bash
cd /Users/huajiang/Desktop/h-agent/other-agents
mvn spring-boot:run
```

另开终端运行：

```bash
curl -s http://localhost:8082/a2a/agents/creative-writer/.well-known/agent-card.json | jq '.name, .url, .skills[0].id'
curl -s -X POST http://localhost:8082/a2a/agents/creative-writer \
  -H 'content-type: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": "smoke-1",
    "method": "message/send",
    "params": {
      "message": {
        "role": "user",
        "parts": [{"kind": "text", "text": "月球救援"}],
        "messageId": "manual-user-message"
      }
    }
  }' | jq '.id, .result.kind, .result.status.state, .result.contextId, .result.id'
```

预期：

```text
"creative-writer"
"http://localhost:8082/a2a/agents/creative-writer"
"creative-writer"
"smoke-1"
"task"
"completed"
非空 contextId
非空 task id
```

- [ ] **步骤 6：更新设计文档的实现状态**

在 `docs/superpowers/specs/2026-07-02-a2a-adapter-redesign.md` 末尾追加：

```markdown
## 实现状态

- backend 已切换为 LangChain4j A2A client：`AgenticServices.a2aBuilder(...)`。
- other-agents 已将标准 LangChain4j agent bean 通过 A2A RPC 出口层暴露。
- A2A 统一 endpoint：`/a2a/agents/{agentId}`。
- `contextId/taskId` 通过 A2A envelope 传递；`userId/sessionId/memoryId` 作为可选 metadata 由 server 兼容读取。
```

- [ ] **步骤 7：最终 commit**

```bash
cd /Users/huajiang/Desktop/h-agent
git add backend other-agents docs/superpowers/specs/2026-07-02-a2a-adapter-redesign.md
git commit -m "chore: verify a2a rpc export layer"
```

---

## 完成标准

- `backend` 中不再存在自研 A2A JSON-RPC client、远端 registry、固定 remote wrapper。
- `backend` 通过 LangChain4j `AgenticServices.a2aBuilder(...)` 创建远端 agent proxy。
- `other-agents` 中业务 agent 仍然是 `Agents.CreativeWriter`、`Agents.AudienceEditor`、`Agents.StyleEditor` 这样的标准 LangChain4j interface/bean。
- `other-agents` 的 A2A server 结构分为 export registry、agent-card factory、request handler、executor、task store、transport wrapper、controller。
- 新端点为：
  - `GET /a2a/agents/{agentId}/.well-known/agent-card.json`
  - `POST /a2a/agents/{agentId}`
- 旧端点不再可用：
  - `/creative-writer/.well-known/agent-card.json`
  - `/creative-writer/a2a`
  - `/audience-editor/.well-known/agent-card.json`
  - `/audience-editor/a2a`
  - `/style-editor/.well-known/agent-card.json`
  - `/style-editor/a2a`
- `contextId/taskId` 能进入响应并可被 LangChain4j client 写回 `AgenticScope`。
- `userId/sessionId/memoryId` metadata 缺失时 server 不报错；存在时 server 读取；`@MemoryId` fallback 顺序为 `memoryId -> sessionId -> contextId`。
- `cd /Users/huajiang/Desktop/h-agent/backend && mvn test` 通过。
- `cd /Users/huajiang/Desktop/h-agent/other-agents && mvn test` 通过。
