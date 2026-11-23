package com.h.backend.chat.infrastructure.ai.a2a;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.NoopAgentObservability;
import com.h.registry.NacosRegistryProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NacosStoryReviewTest {
    @Test
    void scoresThroughTheRegisteredAgentWithoutAnAdminCatalog() throws Exception {
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/score", exchange -> {
            try (exchange) {
                request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = """
                        {"result":{"task":{"id":"task-1","contextId":"context-1",
                        "status":{"state":"TASK_STATE_COMPLETED"},"artifacts":[{"parts":[{"text":"评分 85 分"}]}]}}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var runtime = mock(AiService.class);
            var card = new AgentCardDetailInfo();
            card.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/score");
            card.setDefaultInputModes(List.of("text/plain"));
            card.setDefaultOutputModes(List.of("text/plain"));
            var skill = new AgentSkill();
            skill.setId("style-score");
            skill.setTags(List.of("review"));
            card.setSkills(List.of(skill));
            when(runtime.getAgentCard("story-review", null, "SERVICE")).thenReturn(card);
            var properties = new NacosRegistryProperties("127.0.0.1:1", "public", "DEFAULT_GROUP", "DEFAULT",
                    "", "", "", 0, 100, Map.of());
            var review = new NacosStoryReview(runtime, properties, NoopAgentObservability.getInstance());

            assertEquals("评分 85 分", review.run("月球基地的孩子用一盏旧灯找回了机器人", "style-score"));
            var payload = new ObjectMapper().readTree(request.get());
            assertEquals("style-score", payload.at("/params/message/parts/1/text").asText());
        } finally {
            server.stop(0);
        }
    }
}
