package com.h.otheragents.a2a.interfaces.web;

import com.h.otheragents.a2a.infrastructure.ai.Agents;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class A2AControllerTest {

    private final WebTestClient client = WebTestClient.bindToController(controller()).build();

    @Test
    void creativeWriterEndpointCallsCreativeWriterAgent() {
        client.post()
                .uri("/creative-writer/a2a")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(messageSendRequest("test-message", "月球救援"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("test-message")
                .jsonPath("$.result.role").isEqualTo("ROLE_AGENT")
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
        Agents.CreativeWriter creativeWriter = topic -> "draft:" + topic;
        Agents.AudienceEditor audienceEditor = (story, audience) -> "audience:" + story + ":" + audience;
        Agents.StyleEditor styleEditor = (story, style) -> "style:" + story + ":" + style;
        return new A2AController(creativeWriter, audienceEditor, styleEditor);
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
