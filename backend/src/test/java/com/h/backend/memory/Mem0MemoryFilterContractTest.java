package com.h.backend.memory;

import com.h.backend.memory.infrastructure.config.LongTermMemoryProperties;
import com.h.backend.memory.infrastructure.mem0.Mem0HttpGateway;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class Mem0MemoryFilterContractTest {
    @Test void productionGatewaySendsTrustedFiltersAndReconcilesOnlyExactOperationKeys() throws Exception {
        var json=new ObjectMapper();
        var request=new AtomicReference<Map>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/search", exchange -> {
            request.set(json.readValue(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),Map.class));
            byte[] response="""
                    {"results":[
                      {"id":"right","memory":"decision","metadata":{"operation_key":"op-1"}},
                      {"id":"wrong","memory":"decision","metadata":{"operation_key":"different"}}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var properties=new LongTermMemoryProperties();
            properties.getMem0().setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            properties.getMem0().setApiKey("test-only-key");
            var gateway=new Mem0HttpGateway(properties,json);
            gateway.searchFiltered("owned-user","decision",40,Map.of("run_id","root","user_id","ignored-owner"));
            var filters=(Map<?,?>)request.get().get("filters");
            assertEquals("owned-user",filters.get("user_id"));
            assertEquals("root",filters.get("run_id"));
            var found=gateway.findByOperationKey("owned-user","op-1","decision");
            assertEquals(1,found.size());assertEquals("right",found.getFirst().id());
            assertEquals("op-1",((Map<?,?>)request.get().get("filters")).get("operation_key"));
        } finally {server.stop(0);}
    }
}
