package com.h.otheragents.a2a.interfaces.web;

import com.h.otheragents.a2a.application.A2AAgentCardApplicationService;
import com.h.otheragents.a2a.application.A2AMessageApplicationService;
import com.h.otheragents.a2a.application.CreativeWritingApplicationService;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.domain.service.LangChain4jStoryAgentService;
import com.h.otheragents.a2a.domain.service.RemoteStoryAgents;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class A2AControllerTest {

    private final WebTestClient client = WebTestClient.bindToController(controller()).build();

    @Test
    void agentCardUsesConfiguredA2AEndpoint() {
        client.get()
                .uri("/.well-known/agent-card.json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("remote-creative-writer")
                .jsonPath("$.url").isEqualTo("http://localhost:8082/a2a")
                .jsonPath("$.capabilities.streaming").isEqualTo(false)
                .jsonPath("$.skills.length()").isEqualTo(3);
    }

    @Test
    void messageSendReturnsAgentTextMessage() {
        client.post()
                .uri("/a2a")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "jsonrpc": "2.0",
                          "id": "test-message",
                          "method": "message/send",
                          "params": {
                            "message": {
                              "role": "user",
                              "parts": [
                                {
                                  "kind": "text",
                                  "text": "月球救援"
                                }
                              ],
                              "messageId": "user-message"
                            }
                          }
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("test-message")
                .jsonPath("$.result.kind").isEqualTo("message")
                .jsonPath("$.result.role").isEqualTo("agent")
                .jsonPath("$.result.parts[0].text").isEqualTo("draft:月球救援");
    }

    @Test
    void messageSendCanRouteToAudienceEditorByMetadata() {
        client.post()
                .uri("/a2a")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "jsonrpc": "2.0",
                          "id": "audience-message",
                          "method": "message/send",
                          "params": {
                            "metadata": {
                              "agent": "audience-editor"
                            },
                            "message": {
                              "role": "user",
                              "parts": [
                                {
                                  "kind": "text",
                                  "text": "原故事"
                                },
                                {
                                  "kind": "text",
                                  "text": "儿童"
                                }
                              ],
                              "messageId": "user-message"
                            }
                          }
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.parts[0].text").isEqualTo("audience:原故事:儿童");
    }

    @Test
    void messageSendCanInferAudienceEditorFromCurrentBackendPayload() {
        client.post()
                .uri("/a2a")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "jsonrpc": "2.0",
                          "id": "audience-message",
                          "method": "message/send",
                          "params": {
                            "message": {
                              "role": "user",
                              "parts": [
                                {
                                  "kind": "text",
                                  "text": "原故事"
                                },
                                {
                                  "kind": "text",
                                  "text": "儿童读者"
                                }
                              ],
                              "messageId": "user-message"
                            }
                          }
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.parts[0].text").isEqualTo("audience:原故事:儿童读者");
    }

    @Test
    void messageSendCanRouteToStyleEditorByMetadata() {
        client.post()
                .uri("/a2a")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "jsonrpc": "2.0",
                          "id": "style-message",
                          "method": "message/send",
                          "params": {
                            "metadata": {
                              "agent": "style-editor"
                            },
                            "message": {
                              "role": "user",
                              "parts": [
                                {
                                  "kind": "text",
                                  "text": "原故事"
                                },
                                {
                                  "kind": "text",
                                  "text": "赛博朋克"
                                }
                              ],
                              "messageId": "user-message"
                            }
                          }
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.parts[0].text").isEqualTo("style:原故事:赛博朋克");
    }

    @Test
    void messageSendCanInferStyleEditorFromCurrentBackendPayload() {
        client.post()
                .uri("/a2a")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "jsonrpc": "2.0",
                          "id": "style-message",
                          "method": "message/send",
                          "params": {
                            "message": {
                              "role": "user",
                              "parts": [
                                {
                                  "kind": "text",
                                  "text": "原故事"
                                },
                                {
                                  "kind": "text",
                                  "text": "赛博朋克"
                                }
                              ],
                              "messageId": "user-message"
                            }
                          }
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.parts[0].text").isEqualTo("style:原故事:赛博朋克");
    }

    private static A2AController controller() {
        OtherAgentsA2AProperties properties = new OtherAgentsA2AProperties();
        LangChain4jStoryAgentService storyAgentService = new LangChain4jStoryAgentService(
                topic -> "draft:" + topic,
                (story, audience) -> "audience:" + story + ":" + audience,
                (story, style) -> "style:" + story + ":" + style
        );
        CreativeWritingApplicationService creativeWritingApplicationService =
                new CreativeWritingApplicationService(storyAgentService);
        return new A2AController(
                new A2AAgentCardApplicationService(properties),
                new A2AMessageApplicationService(creativeWritingApplicationService)
        );
    }
}
