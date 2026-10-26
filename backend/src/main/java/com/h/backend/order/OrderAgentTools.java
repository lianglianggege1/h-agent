package com.h.backend.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class OrderAgentTools {
    private static final Set<String> ALLOWED=Set.of("AUDIT","FULFILL","PROCESS","AFTERSALES","PROPOSE_CANCEL",
            "PROPOSE_RETURN","PROPOSE_ADDRESS","PROPOSE_SPECIAL_REFUND","PROPOSE_REJECT_RETURN","RECHECK","NOTE","RETRY_REFUND");
    private final OrderMcpClient mcp;
    private final OrderCommandCoordinator coordinator;
    private final ObjectMapper json=new ObjectMapper();
    public OrderAgentTools(OrderMcpClient mcp,@org.springframework.context.annotation.Lazy OrderCommandCoordinator coordinator){this.mcp=mcp;this.coordinator=coordinator;}

    @Tool(name="order_lookup",description="通过真实 MCP 查询本用户订单、完整操作轨迹、工单和待确认方案；order_id 为空时列出预置订单。",readOnly=true)
    public String lookup(RuntimeContext context,@ToolParam(name="order_id") String orderId){
        var result=mcp.query(user(context),context.getSessionId(),orderId);
        coordinator.projectToolResult(user(context),context.getSessionId(),result);
        return encode(result);
    }

    @Tool(name="order_operate",description="仅在用户明确要求处理时使用。先 lookup，提交 generation/revision。action 可用 AUDIT/FULFILL/AFTERSALES/PROCESS、PROPOSE_CANCEL/RETURN/ADDRESS/SPECIAL_REFUND/REJECT_RETURN、RECHECK、NOTE、RETRY_REFUND。方案只能提出，无法代用户确认；不能模拟支付、签收、验收等外部事实。",concurrencySafe=false)
    public String operate(RuntimeContext context,@ToolParam(name="order_id") String orderId,
            @ToolParam(name="action") String action,@ToolParam(name="generation") String generation,
            @ToolParam(name="revision") long revision,@ToolParam(name="detail",required=false) String detail){
        if(!ALLOWED.contains(action))throw new IllegalArgumentException("订单助手无权执行此操作");
        return encode(coordinator.agentCommand(user(context),context.getSessionId(),orderId,action,generation,revision,detail));
    }

    private long user(RuntimeContext context){
        if(context==null || context.getUserId()==null || context.getSessionId()==null)throw new IllegalStateException("缺少订单运行身份");
        return Long.parseLong(context.getUserId());
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
