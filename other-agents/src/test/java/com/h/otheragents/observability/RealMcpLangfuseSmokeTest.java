package com.h.otheragents.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservabilityTesting;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.EnvFileLoader;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ObservationScope;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端 MCP W3C 传播 smoke 测试（设计 14/19.5）：真实 LangChain4j
 * StreamableHttpMcpTransport + 真实 HTTP POST + 真实运行中的 other-agents MCP
 * endpoint + 真实 Langfuse。客户端侧复刻 backend 生产接缝
 * {@code ObservingMcpHeadersSupplier}/{@code ObservingMcpToolExecutor} 的行为
 * （逐 POST 动态注入 current context、工具执行外创建 remote_call scope）；服务端
 * 是本 JVM 内的生产 WebFilter/contextExtractor/Tool Observation 代码。
 * <p>
 * 验证：同一 McpClient 复用同一 MCP Session 并行两个根 Trace，每次 POST 携带
 * 各自 traceparent，Langfuse 中形成
 *
 * <pre>
 * remote_call mcp.tools/call (client, root)
 *   -> remote_call mcp.server (每个 JSON-RPC POST 一个，首次调用含 initialize)
 *     -> tool add_numbers
 * </pre>
 *
 * Langfuse 不可达时自动跳过；经代理访问时用
 * {@code -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9090 -Dhttp.nonProxyHosts="localhost|127.0.0.1"}。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
        }
)
class RealMcpLangfuseSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLIENT_SPAN = "remote_call mcp.tools/call";
    private static final String SERVER_SPAN = "remote_call mcp.server";

    @LocalServerPort
    private int port;

    @Autowired
    private AgentObservability observability;

    private McpClient mcpClient;
    private String baseUrl;
    private String publicKey;
    private String secretKey;

    @BeforeAll
    void requireConfiguration() {
        Map<String, String> fileValues = EnvFileLoader.load();
        baseUrl = EnvFileLoader.resolve(fileValues, "LANGFUSE_BASE_URL");
        publicKey = EnvFileLoader.resolve(fileValues, "LANGFUSE_PUBLIC_KEY");
        secretKey = EnvFileLoader.resolve(fileValues, "LANGFUSE_SECRET_KEY");
        Assumptions.assumeTrue(baseUrl != null && publicKey != null && secretKey != null,
                "LANGFUSE_BASE_URL/PUBLIC_KEY/SECRET_KEY not configured; skipping MCP smoke test");
        mcpClient = new DefaultMcpClient.Builder()
                .transport(new StreamableHttpMcpTransport.Builder()
                        .url("http://localhost:" + port + "/test1/mcp")
                        .customHeaders(this::dynamicHeaders)
                        .build())
                .toolExecutionTimeout(Duration.ofSeconds(30))
                .build();
    }

    @AfterAll
    void closeClient() throws Exception {
        if (mcpClient != null) {
            mcpClient.close();
        }
    }

    @Test
    void sameClientParallelRootTracesCarryDistinctW3cContextsIntoRealLangfuse() throws Exception {
        String firstResult = runToolCallInRoot("agent run one", 3, 5);
        String secondResult = runToolCallInRoot("agent run two", 2, 7);
        AgentObservabilityTesting.flush(observability);

        assertTrue(firstResult.contains("8"), "real MCP tool result must survive propagation: " + firstResult);
        assertTrue(secondResult.contains("9"), "real MCP tool result must survive propagation: " + secondResult);

        List<JsonNode> first = awaitObservations(firstTraceId.get());
        List<JsonNode> second = awaitObservations(secondTraceId.get());
        assertNotEquals(firstTraceId.get(), secondTraceId.get(),
                "two parallel calls through the same McpClient must form two distinct root traces");

        JsonNode firstAgent = byName(first, "agent run one");
        JsonNode firstClient = byName(first, CLIENT_SPAN);
        JsonNode firstTool = byName(first, "tool add_numbers");
        assertTrue(firstAgent.path("parentObservationId").asText("").isBlank(),
                "agent root must start the trace: " + firstAgent);
        assertEquals(firstAgent.path("id").asText(), firstClient.path("parentObservationId").asText(),
                "client remote_call must nest under the agent root");
        List<JsonNode> servers = allByName(first, SERVER_SPAN);
        assertTrue(servers.size() >= 1, "each JSON-RPC POST must create a server remote_call");
        for (JsonNode server : servers) {
            assertEquals(firstClient.path("id").asText(), server.path("parentObservationId").asText(),
                    "server remote_call must nest under the client remote_call across the real HTTP hop");
        }
        assertTrue(servers.stream().anyMatch(server ->
                        firstTool.path("parentObservationId").asText().equals(server.path("id").asText())),
                "tool add_numbers must nest under one of the server remote_call spans");

        JsonNode secondTool = byName(second, "tool add_numbers");
        List<JsonNode> secondServers = allByName(second, SERVER_SPAN);
        assertEquals(1, secondServers.size(),
                "session reuse means the second call performs exactly one JSON-RPC POST: " + secondServers.size());
        assertEquals(secondServers.get(0).path("id").asText(), secondTool.path("parentObservationId").asText(),
                "second call's tool must nest under its own server span");

        JsonNode toolInput = firstTool.path("input");
        assertTrue(toolInput.toString().contains("3"), "tool input must carry the real MCP arguments: " + toolInput);
        System.out.println("[mcp-smoke] trace1=" + firstTraceId + " trace2=" + secondTraceId
                + " serverSpans=" + servers.size() + "/" + secondServers.size());
    }

    private final AtomicReference<String> firstTraceId = new AtomicReference<>();
    private final AtomicReference<String> secondTraceId = new AtomicReference<>();

    private String runToolCallInRoot(String rootName, int a, int b) throws Exception {
        AgentObservation root = observability.span(
                ObservationSpec.of(rootName, HObsKind.AGENT, "langchain4j"), observability.currentContext());
        AgentObservation remoteCall = observability.span(
                ObservationSpec.of(CLIENT_SPAN, HObsKind.REMOTE_CALL, "mcp-client",
                        Map.of(HAttrs.TOOL_NAME, "add_numbers")),
                root.context());
        try (ObservationScope rootScope = observability.scope(root.context());
             ObservationScope callScope = observability.scope(remoteCall.context())) {
            String result = mcpClient.executeTool(ToolExecutionRequest.builder()
                    .id("call-" + a + b)
                    .name("add_numbers")
                    .arguments("{\"a\":" + a + ",\"b\":" + b + "}")
                    .build()).resultText();
            remoteCall.succeed();
            root.succeed();
            if (firstTraceId.get() == null) {
                firstTraceId.set(remoteCall.traceId());
            } else {
                secondTraceId.set(remoteCall.traceId());
            }
            return result;
        }
    }

    private Map<String, String> dynamicHeaders(dev.langchain4j.mcp.client.McpCallContext callContext) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer dev-token-test1");
        observability.inject(observability.currentContext(), headers);
        return headers;
    }

    private List<JsonNode> awaitObservations(String traceId) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String auth = Base64.getEncoder().encodeToString(
                (publicKey + ":" + secretKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String url = baseUrl.replaceAll("/+$", "")
                + "/api/public/v2/observations?traceId=" + traceId
                + "&fields=core,basic,io,usage&limit=100";

        long deadline = System.currentTimeMillis() + 60_000;
        JsonNode lastBody = null;
        while (System.currentTimeMillis() < deadline) {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("Authorization", "Basic " + auth)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                lastBody = JSON.readTree(response.body());
                if (lastBody.path("data").size() >= 3) {
                    List<JsonNode> result = new ArrayList<>();
                    lastBody.withArray("data").forEach(result::add);
                    return result;
                }
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("Langfuse did not ingest the MCP trace within 60s; last: " + lastBody);
    }

    private JsonNode byName(List<JsonNode> observations, String name) {
        return observations.stream()
                .filter(obs -> name.equals(obs.path("name").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("observation not found: " + name + " in " + observations));
    }

    private List<JsonNode> allByName(List<JsonNode> observations, String name) {
        return observations.stream()
                .filter(obs -> name.equals(obs.path("name").asText()))
                .toList();
    }
}
