package com.h.otheragents.a2a.interfaces.web;

import com.h.otheragents.a2a.application.A2AAgentCardApplicationService;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.infrastructure.ai.Agents;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class A2AControllerTest {

    private final WebTestClient client = WebTestClient.bindToController(controller()).build();

    @Test
    void creativeWriterAgentCardUsesCreativeWriterEndpoint() {
        client.get()
                .uri("/creative-wtiter/.well-known/agent-card.json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("creative-writer")
                .jsonPath("$.url").isEqualTo("http://localhost:8082/creative-wtiter/a2a")
                .jsonPath("$.skills[0].id").isEqualTo("creative-writer");
    }

    @Test
    void audienceEditorAgentCardUsesAudienceEditorEndpoint() {
        client.get()
                .uri("/audience-editor/.well-known/agent-card.json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("audience-editor")
                .jsonPath("$.url").isEqualTo("http://localhost:8082/audience-editor/a2a")
                .jsonPath("$.skills[0].id").isEqualTo("audience-editor");
    }

    @Test
    void styleEditorAgentCardUsesStyleEditorEndpoint() {
        client.get()
                .uri("/style-editor/.well-known/agent-card.json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("style-editor")
                .jsonPath("$.url").isEqualTo("http://localhost:8082/style-editor/a2a")
                .jsonPath("$.skills[0].id").isEqualTo("style-editor");
    }

    @Test
    void creativeWriterEndpointCallsCreativeWriterAgent() {
        client.post()
                .uri("/creative-wtiter/a2a")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(messageSendRequest("test-message", "月球救援"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("test-message")
                .jsonPath("$.result.kind").isEqualTo("message")
                .jsonPath("$.result.role").isEqualTo("agent")
                .jsonPath("$.result.parts[0].text").isEqualTo("draft:月球救援");
    }

    @Test
    void audienceEditorEndpointCallsAudienceEditorAgent() {
        client.post()
                .uri("/audience-editor/a2a")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(messageSendRequest("audience-message", "原故事", "儿童"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.parts[0].text").isEqualTo("audience:原故事:儿童");
    }

    @Test
    void styleEditorEndpointCallsStyleEditorAgent() {
        client.post()
                .uri("/style-editor/a2a")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(messageSendRequest("style-message", "原故事", "赛博朋克"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.parts[0].text").isEqualTo("style:原故事:赛博朋克");
    }

    private static A2AController controller() {
        OtherAgentsA2AProperties properties = new OtherAgentsA2AProperties();
        Agents.CreativeWriter creativeWriter = topic -> "draft:" + topic;
        Agents.AudienceEditor audienceEditor = (story, audience) -> "audience:" + story + ":" + audience;
        Agents.StyleEditor styleEditor = (story, style) -> "style:" + story + ":" + style;
        return new A2AController(
                new A2AAgentCardApplicationService(properties),
                creativeWriter,
                audienceEditor,
                styleEditor
        );
    }

    private static String messageSendRequest(String id, String... texts) {
        StringBuilder parts = new StringBuilder();
        for (int i = 0; i < texts.length; i++) {
            if (i > 0) {
                parts.append(",");
            }
            parts.append("""
                    {
                      "kind": "text",
                      "text": "%s"
                    }
                    """.formatted(texts[i]));
        }
        return """
                {
                  "jsonrpc": "2.0",
                  "id": "%s",
                  "method": "message/send",
                  "params": {
                    "message": {
                      "role": "user",
                      "parts": [
                        %s
                      ],
                      "messageId": "user-message"
                    }
                  }
                }
                """.formatted(id, parts);
    }
}
