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
