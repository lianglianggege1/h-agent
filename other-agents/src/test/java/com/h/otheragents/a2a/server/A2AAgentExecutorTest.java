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
                  "parts": [{"text": "月球救援"}],
                  "metadata": {"userId": "u1"}
                }
                """);

        JsonNode result = executor.execute("creative-writer", message);
        JsonNode task = result.path("task");

        assertFalse(task.path("id").asText().isBlank());
        assertEquals("context-1", task.path("contextId").asText());
        assertEquals("TASK_STATE_COMPLETED", task.path("status").path("state").asText());
        assertEquals("draft:月球救援", task.path("artifacts").get(0).path("parts").get(0).path("text").asText());
    }

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
                  "parts": [{"text": "月球救援"}]
                }
                """);

        JsonNode result = executor.execute("creative-writer", message);
        JsonNode task = result.path("task");

        assertEquals("task-existing", task.path("id").asText());
        assertEquals("context-existing", task.path("contextId").asText());
    }

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
                  "parts": [{"text": "月球救援"}]
                }
                """);

        JsonNode result = executor.execute("creative-writer", message);
        JsonNode task = result.path("task");

        assertEquals("TASK_STATE_COMPLETED", task.path("status").path("state").asText());
        assertFalse(task.path("contextId").asText().isBlank());
        assertFalse(task.path("id").asText().isBlank());
    }
}
