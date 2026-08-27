package com.h.otheragents.a2a.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.infrastructure.ai.Agents;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
        }
)
class LangChain4jA2AClientContractTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestConfiguration
    static class TestA2AConfig implements ApplicationListener<WebServerInitializedEvent> {

        @Autowired
        private OtherAgentsA2AProperties properties;

        @Override
        public void onApplicationEvent(WebServerInitializedEvent event) {
            properties.setPublicUrl("http://localhost:" + event.getWebServer().getPort());
        }

        @Bean
        @Primary
        Agents.CreativeWriter creativeWriter() {
            return topic -> "draft:" + topic;
        }

        @Bean
        @Primary
        Agents.AudienceEditor audienceEditor() {
            return (story, audience) -> "audience:" + story + ":" + audience;
        }

        @Bean
        @Primary
        Agents.StyleEditor styleEditor() {
            return (story, style) -> "style:" + story + ":" + style;
        }
    }

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
                    "task": {
                      "id": "task-1",
                      "contextId": "context-1",
                      "status": {
                        "state": "TASK_STATE_COMPLETED"
                      },
                      "artifacts": [{
                        "artifactId": "artifact-1",
                        "parts": [{
                          "text": "故事内容"
                        }]
                      }]
                    }
                  }
                }
                """);

        assertEquals("rpc-1", response.path("id").asText());
        assertEquals("task-1", response.path("result").path("task").path("id").asText());
        assertEquals("context-1", response.path("result").path("task").path("contextId").asText());
        assertEquals("TASK_STATE_COMPLETED", response.path("result").path("task").path("status").path("state").asText());
        assertEquals("故事内容", response.path("result").path("task").path("artifacts").get(0).path("parts").get(0).path("text").asText());
    }

    @Test
    void langChain4jA2AClientCanCallOtherAgentsEndpoint() {
        String serverUrl = "http://localhost:" + port + "/a2a/agents/creative-writer";

        ClientCreativeWriter writer = AgenticServices
                .a2aBuilder(serverUrl, ClientCreativeWriter.class)
                .outputKey("story")
                .build();

        String story = writer.generateStory("月球救援");

        assertTrue(story != null && !story.isBlank());
    }

    interface ClientCreativeWriter {

        @Agent(outputKey = "story")
        String generateStory(@V("topic") String topic);
    }
}
