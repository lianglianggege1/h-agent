package com.h.backend.chat.infrastructure.config;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicBaseFormatter;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicChatFormatter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HarnessModelTurnBoundaryTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void realAnthropicRequestDisablesToolsForSummaryAndPendingDocument(boolean summary) throws Exception {
        var json = new ObjectMapper();
        var captured = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            captured.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            String events = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg-test","content":[],"model":"test-model","role":"assistant","stop_reason":null,"stop_sequence":null,"type":"message","usage":{"input_tokens":1,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"文档正在后台处理，完成后继续。"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":10}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
            byte[] bytes = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Class<?> type = Class.forName(HarnessAgentConfig.class.getName() + "$LoggingAnthropicChatModel");
            var ctor = type.getDeclaredConstructor(String.class, String.class, String.class,
                    GenerateOptions.class, AnthropicBaseFormatter.class);
            ctor.setAccessible(true);
            var options = GenerateOptions.builder().maxTokens(256).build();
            Model model = (Model) ctor.newInstance("http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-only", "test-model", options, new AnthropicChatFormatter());
            var messages = summary ? List.of(Msg.builder().role(MsgRole.USER).textContent("summarize").build())
                    : List.of(Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.of("call1", "parse_document",
                            TextBlock.builder().text("{\"taskId\":\"doc1\",\"status\":\"OCR_RUNNING\"}").build())).build());
            var tools = summary ? null : List.of(ToolSchema.builder().name("parse_document")
                    .description("document").parameters(Map.of("type", "object", "properties", Map.of())).build());
            var result = model.stream(messages, tools, options).collectList().block(Duration.ofSeconds(10));
            assertNotNull(result);
            assertFalse(result.isEmpty());
            assertEquals("none", captured.get().path("tool_choice").path("type").asText());
            assertTrue(captured.get().path("tools").isMissingNode() || captured.get().path("tools").isEmpty());
        } finally {
            server.stop(0);
        }
    }
}
