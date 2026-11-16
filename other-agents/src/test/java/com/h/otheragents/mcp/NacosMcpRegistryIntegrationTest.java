package com.h.otheragents.mcp;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.h.registry.NacosMcpDiscovery;
import com.h.registry.NacosRegistryProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.reactive.context.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in: persistent definitions must only be published into a disposable Nacos 3.2.4. */
@EnabledIfEnvironmentVariable(named = "NACOS_MCP_TEST_ADDR", matches = ".+")
@org.springframework.test.context.TestPropertySource(properties = {
        "other-agents.mcp.endpoints.test1.version=0.0.1", "other-agents.mcp.endpoints.test2.version=0.0.1",
        "order-demo.mcp.version=0.0.1", "order-demo.mcp.token=it-orders-token"})
@org.springframework.context.annotation.Import({com.h.otheragents.order.OrderDemoMcpConfig.class,
        com.h.otheragents.order.OrderDemoMcpTools.class,NacosMcpRegistryIntegrationTest.OrderBeans.class})
class NacosMcpRegistryIntegrationTest extends MultiEndpointMcpProtocolIT {
    @Autowired List<McpEndpointServers> servers;
    @Autowired com.h.otheragents.order.OrderDemoService orders;

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class OrderBeans {
        @org.springframework.context.annotation.Bean
        com.h.otheragents.order.OrderDemoService orders() {
            return mock(com.h.otheragents.order.OrderDemoService.class);
        }
    }

    private String awaitUrl(NacosMcpDiscovery discovery, String name) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do {
            try { return discovery.resolve(name,"0.0.1",null); }
            catch (IllegalStateException transientFailure) {
                if (System.nanoTime() >= deadline) throw transientFailure;
                Thread.sleep(50);
            }
        } while (true);
    }

    @Test
    void publishesDiscoversCallsAndRemovesRuntimeEndpoints() throws Exception {
        Properties client = new Properties();
        client.setProperty(PropertyKeyConst.SERVER_ADDR, System.getenv("NACOS_MCP_TEST_ADDR"));
        client.setProperty(PropertyKeyConst.NAMESPACE,"public");
        AiService publisher = AiFactory.createAiService(client);
        AiService consumer = AiFactory.createAiService(client);
        var context = mock(ReactiveWebServerApplicationContext.class);
        var web = mock(WebServer.class); when(web.getPort()).thenReturn(port);
        var registration = new NacosMcpRegistration(publisher,new NacosRegistryProperties(
                System.getenv("NACOS_MCP_TEST_ADDR"),"public","DEFAULT_GROUP","DEFAULT","","","127.0.0.1",0,5000,Map.of()),
                servers,context);
        try {
            registration.onApplicationEvent(new ReactiveWebServerInitializedEvent(web,context));
            var discovery = new NacosMcpDiscovery(consumer);
            String url = awaitUrl(discovery,"test1-mcp");
            assertEquals("http://127.0.0.1:" + port + "/test1/mcp",url);
            try (var mcp = new DefaultMcpClient.Builder().transport(StreamableHttpMcpTransport.builder()
                    .url(url).customHeaders(() -> Map.of("Authorization","Bearer it-token-test1")).build()).build()) {
                assertEquals("3",mcp.executeTool(ToolExecutionRequest.builder().name("add_numbers")
                        .arguments("{\"a\":1,\"b\":2}").build()).resultText());
            }
            assertEquals("http://127.0.0.1:" + port + "/test2/mcp",awaitUrl(discovery,"test2-mcp"));
            String orderUrl = awaitUrl(discovery,"orders-mcp");
            when(orders.query(7L,"example")).thenReturn(Map.of("kind","order-demo"));
            try (var orderClient = new DefaultMcpClient.Builder().transport(StreamableHttpMcpTransport.builder()
                    .url(orderUrl).customHeaders(() -> Map.of("Authorization","Bearer it-orders-token",
                            "X-Order-User-Id","7","X-Order-Actor","agent")).build()).build()) {
                assertFalse(orderClient.executeTool(ToolExecutionRequest.builder().name("order_query")
                        .arguments("{\"orderId\":\"example\"}").build()).isError());
                verify(orders).query(7L,"example");
            }
            // A process restart reuses the immutable definition.
            registration.destroy();
            registration.onApplicationEvent(new ReactiveWebServerInitializedEvent(web,context));
            registration.destroy();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            boolean unavailable = false;
            do {
                try { discovery.resolve("test1-mcp","0.0.1",null); }
                catch (IllegalStateException expected) { unavailable = true; break; }
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            assertTrue(unavailable,"Deregistered endpoint must not be discoverable");
        } finally {
            registration.destroy(); consumer.shutdown(); publisher.shutdown();
        }
    }
}
