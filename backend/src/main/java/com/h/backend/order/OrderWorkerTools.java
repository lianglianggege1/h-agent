package com.h.backend.order;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Set;

/** Each collaborator receives only its stage's command capabilities. */
public final class OrderWorkerTools {
    private final OrderAgentTools delegate;
    private final Set<String> actions;
    public OrderWorkerTools(OrderAgentTools delegate,String role){
        this.delegate=delegate;
        this.actions=switch(role){
            case "order-audit"->Set.of("AUDIT","NOTE");
            case "order-fulfillment"->Set.of("FULFILL");
            case "order-aftersales"->Set.of("AFTERSALES","PROPOSE_CANCEL","PROPOSE_RETURN","PROPOSE_ADDRESS",
                    "PROPOSE_SPECIAL_REFUND","PROPOSE_REJECT_RETURN","RECHECK","NOTE","RETRY_REFUND");
            default->throw new IllegalArgumentException("未知订单协作者");
        };
    }
    @Tool(name="order_lookup",description="查询当前用户订单、工单、方案与操作轨迹。",readOnly=true)
    public String lookup(RuntimeContext context,@ToolParam(name="order_id")String orderId){return delegate.lookup(context,orderId);}
    @Tool(name="order_operate",description="执行本协作者职责内的订单动作；必须先查询 generation/revision。无法确认业务方案或模拟外部事实。",concurrencySafe=false)
    public String operate(RuntimeContext context,@ToolParam(name="order_id")String orderId,@ToolParam(name="action")String action,
            @ToolParam(name="generation")String generation,@ToolParam(name="revision")long revision,@ToolParam(name="detail",required=false)String detail){
        if(!actions.contains(action))throw new IllegalArgumentException("该订单协作者无权执行此动作");
        return delegate.operate(context,orderId,action,generation,revision,detail);
    }
}
