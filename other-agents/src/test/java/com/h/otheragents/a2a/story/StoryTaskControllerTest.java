package com.h.otheragents.a2a.story;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StoryTaskControllerTest {
    private static final String ROOT = "/a2a/agents/story-workflow";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void restoresRemoteQuestionAndResumesTheOriginalTask() throws Exception {
        var store = new TestTaskStore();
        var first = send(client(store), "alice", "创作一个月球故事", "message-1", null);
        String taskId = first.path("task").path("id").asText();
        assertEquals("TASK_STATE_INPUT_REQUIRED", first.at("/task/status/state").asText());
        assertEquals("请补充故事风格、目标受众。", first.at("/task/status/message/parts/0/text").asText());

        // A fresh HTTP controller does not own the dialogue or task identity.
        var reopened = client(store);
        reopened.post().uri(ROOT).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer alice")
                .bodyValue(getTaskRequest(taskId))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.result.status.state").isEqualTo("TASK_STATE_INPUT_REQUIRED");
        reopened.post().uri(ROOT).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer bob")
                .bodyValue(getTaskRequest(taskId))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.error.code").isEqualTo(-32001);

        var result = send(reopened, "alice", "科幻风格，儿童读者", "message-2", taskId);
        assertEquals(taskId, result.at("/task/id").asText());
        assertEquals(first.at("/task/contextId"), result.at("/task/contextId"));
        assertEquals("TASK_STATE_COMPLETED", result.at("/task/status/state").asText());
        assertEquals("月球/科幻/儿童", result.at("/task/artifacts/0/parts/0/text").asText());
        var repeated = send(reopened, "alice", "科幻风格，儿童读者", "message-2", taskId);
        assertEquals(result, repeated);
    }

    @Test
    void inferredStyleAndAudienceCannotSkipTheClarification() throws Exception {
        var client = client(new TestTaskStore(), new StoryTaskWorkflow.StoryModel() {
            @Override
            public StoryTaskWorkflow.StoryBrief extract(String conversation) {
                return new StoryTaskWorkflow.StoryBrief("月球基地", "轻松幽默的科幻风格", "8–12 岁儿童");
            }

            @Override
            public String write(StoryTaskWorkflow.StoryBrief brief) {
                return "月球故事";
            }
        });
        var first = send(client, "alice", "写一个发生在月球基地的故事", "grounded-1", null).path("task");
        assertEquals("TASK_STATE_INPUT_REQUIRED", first.at("/status/state").asText());
        assertEquals("请补充故事风格、目标受众。", first.at("/status/message/parts/0/text").asText());

        var second = send(client, "alice", "面向 8–12 岁儿童，轻松幽默的科幻风格", "grounded-2",
                first.path("id").asText()).path("task");
        assertEquals(first.path("id"), second.path("id"));
        assertEquals("TASK_STATE_COMPLETED", second.at("/status/state").asText());
    }

    @Test
    void extendedCardAndPrivateSkillUseTheSameAuthorizationBoundary() {
        var client = client(new TestTaskStore());
        client.get().uri(ROOT + "/.well-known/agent-card.json")
                .exchange().expectBody().jsonPath("$.skills.length()").isEqualTo(1);
        String card = """
                {"jsonrpc":"2.0","id":"card","method":"GetExtendedAgentCard","params":{}}
                """;
        client.post().uri(ROOT).contentType(MediaType.APPLICATION_JSON).bodyValue(card)
                .exchange().expectStatus().isUnauthorized();
        client.post().uri(ROOT).contentType(MediaType.APPLICATION_JSON).bodyValue(card)
                .header(HttpHeaders.AUTHORIZATION, "Bearer alice")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.result.skills.length()").isEqualTo(1);
        client.post().uri(ROOT).contentType(MediaType.APPLICATION_JSON).bodyValue(card)
                .header(HttpHeaders.AUTHORIZATION, "Bearer editor")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.result.skills[1].id").isEqualTo("write-novel");
        String novel = """
                {"jsonrpc":"2.0","id":"send","method":"SendMessage",
                 "params":{"message":{"role":"ROLE_USER","messageId":"novel",
                 "parts":[{"text":"写一个月球科幻儿童故事"}],"metadata":{"skillId":"write-novel"}}}}
                """;
        client.post().uri(ROOT).contentType(MediaType.APPLICATION_JSON).bodyValue(novel)
                .header(HttpHeaders.AUTHORIZATION, "Bearer alice")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.error.code").isEqualTo(-32004);
        client.post().uri(ROOT).contentType(MediaType.APPLICATION_JSON).bodyValue(novel)
                .header(HttpHeaders.AUTHORIZATION, "Bearer editor")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.result.task.status.state").isEqualTo("TASK_STATE_COMPLETED");
    }

    @Test
    void streamsAStoryThroughTheRemoteTask() throws Exception {
        var client = client(new TestTaskStore());
        var response = client.post().uri(ROOT).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer alice")
                .bodyValue("""
                        {"jsonrpc":"2.0","id":"stream","method":"SendStreamingMessage",
                         "params":{"message":{"role":"ROLE_USER","messageId":"stream-one",
                         "parts":[{"text":"写一个月球科幻儿童故事"}]}}}
                        """)
                .exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class);
        var wire = response.getResponseBody().collectList().block(java.time.Duration.ofSeconds(10));
        assertNotNull(wire);
        var first = JSON.readTree(wire.getFirst()).path("result").path("task");
        assertEquals("TASK_STATE_WORKING", first.at("/status/state").asText());
        assertEquals(true, wire.stream().anyMatch(event -> event.contains("artifactUpdate")
                && event.contains("月球/科幻/儿童")));
        assertEquals(true, wire.getLast().contains("TASK_STATE_COMPLETED"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "A2A_STORY_TEST_DB_URL", matches = ".+")
    void persistsPendingTaskInPostgresAcrossServiceInstances() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("A2A_STORY_TEST_DB_URL"), "postgres", "");
        var first = send(client(new JdbcStoryTaskStore(JdbcClient.create(source))),
                "db-fixture", "月球故事", java.util.UUID.randomUUID().toString(), null);
        String id = first.at("/task/id").asText();
        assertEquals("TASK_STATE_INPUT_REQUIRED", first.at("/task/status/state").asText());
        var restarted = client(new JdbcStoryTaskStore(JdbcClient.create(source)));
        var finished = send(restarted, "db-fixture", "科幻，儿童", "reply", id);
        assertEquals("TASK_STATE_COMPLETED", finished.at("/task/status/state").asText());
        assertEquals(id, finished.at("/task/id").asText());
    }

    private static WebTestClient client(StoryTaskWorkflow.Store store) {
        StoryTaskWorkflow.StoryModel model = new StoryTaskWorkflow.StoryModel() {
            @Override
            public StoryTaskWorkflow.StoryBrief extract(String conversation) {
                return conversation.contains("儿童")
                        ? new StoryTaskWorkflow.StoryBrief("月球", "科幻", "儿童")
                        : new StoryTaskWorkflow.StoryBrief("月球", null, null);
            }

            @Override
            public String write(StoryTaskWorkflow.StoryBrief brief) {
                return String.join("/", brief.topic(), brief.style(), brief.audience());
            }

            @Override
            public String revise(String prior, String instruction, java.util.function.Consumer<String> chunks) {
                return prior + "，" + instruction;
            }
        };
        return client(store, model);
    }

    private static WebTestClient client(StoryTaskWorkflow.Store store, StoryTaskWorkflow.StoryModel model) {
        return WebTestClient.bindToController(new StoryTaskController(
                new OtherAgentsA2AProperties(), new StoryTaskWorkflow(store, model),
                token -> Optional.of(new StoryTaskController.Caller(token, "editor".equals(token))))).build();
    }

    @Test
    void revisionReferencesTheCompletedDraftWithoutReplacingIt() throws Exception {
        var client = client(new TestTaskStore());
        var original = send(client, "author", "儿童科幻月球故事", "draft", null).path("task");
        var request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", "revise").put("method", "SendMessage");
        var message = request.putObject("params").putObject("message").put("messageId", "revision").put("role", "ROLE_USER")
                .put("contextId", original.path("contextId").asText());
        message.putArray("referenceTaskIds").add(original.path("id").asText());
        message.putArray("parts").addObject().put("text", "调整结尾");
        byte[] response = client.post().uri(ROOT).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer author").bodyValue(request.toString())
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        var revised = JSON.readTree(response).at("/result/task");
        assertNotEquals(original.path("id").asText(), revised.path("id").asText());
        assertEquals(original.path("contextId").asText(), revised.path("contextId").asText());
        assertEquals(original.path("id").asText(), revised.at("/metadata/revisesTaskId").asText());
        assertEquals("月球/科幻/儿童，调整结尾", revised.at("/artifacts/0/parts/0/text").asText());
        assertEquals("故事修订稿", revised.at("/artifacts/0/name").asText());
    }

    private static String getTaskRequest(String taskId) {
        return JSON.createObjectNode().put("jsonrpc", "2.0").put("id", "get")
                .put("method", "GetTask").set("params", JSON.createObjectNode().put("id", taskId)).toString();
    }

    private static JsonNode send(WebTestClient client, String who, String text, String messageId, String taskId)
            throws Exception {
        var message = JSON.createObjectNode().put("role", "ROLE_USER").put("messageId", messageId);
        if (taskId != null) message.put("taskId", taskId);
        message.putArray("parts").addObject().put("text", text);
        var request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", "send").put("method", "SendMessage");
        request.putObject("params").set("message", message);
        byte[] response = client.post().uri(ROOT).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + who)
                .bodyValue(request.toString())
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        assertNotNull(response);
        return JSON.readTree(response).path("result");
    }

    private static final class TestTaskStore implements StoryTaskWorkflow.Store {
        private final ConcurrentHashMap<String, StoryTaskWorkflow.Task> tasks = new ConcurrentHashMap<>();

        @Override
        public Optional<StoryTaskWorkflow.Task> find(String owner, String id) {
            return Optional.ofNullable(tasks.get(id)).filter(task -> task.owner().equals(owner));
        }

        @Override
        public StoryTaskWorkflow.Task start(StoryTaskWorkflow.Task task) {
            return tasks.computeIfAbsent(task.id(), ignored -> task);
        }

        @Override
        public boolean replace(StoryTaskWorkflow.Task expected, StoryTaskWorkflow.Task updated) {
            return tasks.replace(expected.id(), expected, updated);
        }
    }
}
