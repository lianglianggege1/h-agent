package com.h.otheragents.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.otheragents.mcp.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.netty.http.server.HttpServer;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OrderMcpProtocolTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void realProtocolCarriesIdentityWithoutObservabilityAndProtectsOperatorActions() throws Exception {
        var props=new McpEndpointProperties();var endpoint=new McpEndpointProperties.Endpoint();
        endpoint.setPath("/orders/mcp");endpoint.setToken("protocol-test-token");props.setEndpoints(Map.of("orders",endpoint));
        var service=new OrderDemoService(new OrderDemoServiceTest.MemoryStore());
        try(var server=McpEndpointServers.build(props,Map.of("orders",List.of(new OrderDemoMcpTools(service))),null)){
            var http=HttpServer.create().host("127.0.0.1").port(0).handle(new ReactorHttpHandlerAdapter(
                    RouterFunctions.toHttpHandler(server.routerFunction()))).bindNow();
            try(var agent=client(http.port(),"1","agent");var operator=client(http.port(),"1","operator");
                var other=client(http.port(),"2","operator");var anonymous=client(http.port(),null,"agent")){
                assertThat(agent.listTools()).extracting(t->t.name()).containsExactlyInAnyOrder("order_query","order_command");
                var queried=call(agent,"order_query",Map.of("orderId","ORD-DEMO-001"));
                var payload=json.readTree(queried.resultText());
                assertThat(payload.path("kind").asText()).isEqualTo("order-demo");
                String generation=payload.path("generation").asText();
                var command=Map.<String,Object>of("orderId","ORD-DEMO-001","action","PROCESS","commandId","ship-1",
                        "generation",generation,"revision",0,"detail","");
                assertThat(call(agent,"order_command",command).isError()).isFalse();
                assertThat(json.readTree(call(other,"order_query",Map.of("orderId","ORD-DEMO-001")).resultText())
                        .path("orders").get(0).path("stage").asText()).isEqualTo("NEW");
                var fakeEvent=new HashMap<>(command);fakeEvent.put("action","DELIVERED");fakeEvent.put("commandId","fake");fakeEvent.put("revision",1);
                assertThatThrownBy(()->call(agent,"order_command",fakeEvent)).hasMessageContaining("演示入口");
                assertThat(call(operator,"order_command",fakeEvent).isError()).isFalse();
                assertThatThrownBy(()->call(anonymous,"order_query",Map.of("orderId","ORD-DEMO-001"))).hasMessageContaining("可信订单业务身份");
            } finally {http.disposeNow();}
        }
    }
    private McpClient client(int port,String user,String actor){
        var headers=new HashMap<String,String>();headers.put("Authorization","Bearer protocol-test-token");
        if(user!=null)headers.put("X-Order-User-Id",user);headers.put("X-Order-Actor",actor);
        return new DefaultMcpClient.Builder().transport(StreamableHttpMcpTransport.builder()
                .url("http://127.0.0.1:"+port+"/orders/mcp").customHeaders(()->headers).build())
                .toolExecutionTimeout(Duration.ofSeconds(5)).build();
    }
    private dev.langchain4j.service.tool.ToolExecutionResult call(McpClient client,String name,Map<String,Object> args)throws Exception{
        return client.executeTool(ToolExecutionRequest.builder().name(name).arguments(json.writeValueAsString(args)).build());
    }
}
