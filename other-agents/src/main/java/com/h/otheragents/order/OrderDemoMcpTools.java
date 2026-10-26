package com.h.otheragents.order;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public final class OrderDemoMcpTools {
    public static final String USER_CONTEXT = "order-demo.user";
    public static final String ACTOR_CONTEXT = "order-demo.actor";
    private final OrderDemoService service;
    public OrderDemoMcpTools(OrderDemoService service) { this.service = service; }

    @Tool(name = "order_query", description = "查询当前用户订单、完整操作记录、工单和业务方案。空 orderId 查询全部预置订单。")
    public Map<String, Object> query(@ToolParam(description = "订单编号；空字符串查询全部") String orderId, ToolContext context) {
        try { return service.query(user(context), orderId); }
        catch (IllegalStateException error) { throw new IllegalStateException("ORDER_UNAVAILABLE: 订单业务存储暂不可用",error); }
    }

    @Tool(name = "order_command", description = "执行受状态校验的订单命令。人工确认和模拟事件要求 operator 身份，agent 身份无法执行。")
    public Map<String, Object> command(String orderId, String action, String commandId,
                                       String generation, long revision, String detail, ToolContext context) {
        var exchange = McpToolUtils.getMcpExchange(context).orElse(null);
        boolean operator = exchange != null && "operator".equals(exchange.transportContext().get(ACTOR_CONTEXT));
        try { return service.execute(user(context), operator, orderId, action, commandId, generation, revision, detail); }
        catch (IllegalStateException error) { throw new IllegalStateException("ORDER_UNAVAILABLE: 订单业务存储暂不可用",error); }
    }

    private long user(ToolContext context) {
        var exchange = McpToolUtils.getMcpExchange(context).orElse(null);
        Object value = exchange == null ? null : exchange.transportContext().get(USER_CONTEXT);
        try {
            long id = Long.parseLong(String.valueOf(value));
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("缺少可信订单业务身份");
    }
}
