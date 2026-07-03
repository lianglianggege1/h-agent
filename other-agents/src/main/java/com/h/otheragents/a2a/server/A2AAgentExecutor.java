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
