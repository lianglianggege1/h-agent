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
                .jsonPath("$.result.task.id").isNotEmpty()
                .jsonPath("$.result.task.status.state").isEqualTo("TASK_STATE_COMPLETED")
                .jsonPath("$.result.task.artifacts[0].parts[0].text").isEqualTo("draft:月球救援");
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
