package com.h.backend.order;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.common.exception.BusinessException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/** AgentScope tools use this protocol adapter; identity belongs to each call, never a global client field. */
@Component
public class OrderMcpClient {
    private final String url;
    private final String token;
    private final ObjectMapper json = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired
    private com.h.agent.observability.AgentObservability observability = com.h.agent.observability.NoopAgentObservability.getInstance();
    public OrderMcpClient(@Value("${chat.orders.mcp.url:${ORDER_MCP_URL:http://localhost:8082/orders/mcp}}") String url,
                          @Value("${chat.orders.mcp.token:${ORDER_MCP_TOKEN:dev-token-orders}}") String token) {
        this.url = url; this.token = token;
    }

    public Map<String,Object> query(long userId, String sessionId, String orderId) {
        return call(userId, sessionId, false, "order_query", Map.of("orderId", Objects.toString(orderId,"")));
    }

    public Map<String,Object> command(long userId, String sessionId, boolean operator, String orderId,
            String action, String commandId, String generation, long revision, String detail) {
        return call(userId, sessionId, operator, "order_command", Map.of("orderId", orderId, "action",action,
                "commandId",commandId,"generation",generation,"revision",revision,"detail",Objects.toString(detail,"")));
    }

    private Map<String,Object> call(long userId, String sessionId, boolean operator, String name, Map<String,Object> args) {
        if(userId<=0 || sessionId==null || sessionId.isBlank()) throw new BusinessException(40300,"缺少订单调用身份");
        Map<String,String> headers=Map.of("Authorization","Bearer "+token,"X-Order-User-Id",String.valueOf(userId),
                "X-Order-Actor",operator?"operator":"agent","X-Order-Session-Id",sessionId,
                "X-Order-Assistant-Id","order-processing-assistant");
        try (McpClient client=new DefaultMcpClient.Builder().transport(StreamableHttpMcpTransport.builder()
                .url(url).customHeaders(new com.h.backend.observability.mcp.ObservingMcpHeadersSupplier(observability,()->headers)).build())
                .toolExecutionTimeout(Duration.ofSeconds(15)).build()) {
            var result=client.executeTool(ToolExecutionRequest.builder().id(UUID.randomUUID().toString())
                    .name(name).arguments(json.writeValueAsString(args)).build());
            if(result.isError()) throw new BusinessException(40901,"订单操作未完成："+safe(result.resultText()));
            String text=result.resultText();
            // MCP adapters may return a JSON string value around an already serialized result.
            var node=json.readTree(text);
            if(node.isTextual()) node=json.readTree(node.textValue());
            Map<String,Object> payload=json.convertValue(node,new TypeReference<>(){});
            if(!"order-demo".equals(payload.get("kind"))) throw new IllegalStateException("订单工具结果格式无效");
            return payload;
        } catch(dev.langchain4j.mcp.client.McpApplicationErrorException error) {
            if(Objects.toString(error.getMessage(),"").contains("ORDER_UNAVAILABLE"))
                throw new IllegalStateException("订单业务存储暂不可用",error);
            throw new BusinessException(40901,safe(error.getMessage()));
        } catch(BusinessException error) {throw error;}
        catch(Exception error){throw new IllegalStateException("订单 MCP 暂不可用",error);}
    }

    private String safe(String message) {return message==null?"请刷新后重试":message.substring(0,Math.min(message.length(),300));}
}
