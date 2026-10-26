package com.h.backend.order;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.agui.AguiChatRuntime;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.common.exception.BusinessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class OrderCommandCoordinator {
    public record Request(String commandId,String orderId,String action,String generation,long revision,String detail,Long anchorMessageId) {
        public Request(String commandId,String orderId,String action,String generation,long revision,String detail){
            this(commandId,orderId,action,generation,revision,detail,null);
        }
    }
    private final OrderJobMapper jobs;
    private final OrderMcpClient mcp;
    private final ChatSessionService sessions;
    private final AguiChatRuntime runtime;
    private final ObjectMapper json=new ObjectMapper();
    private final java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.Semaphore permits=new java.util.concurrent.Semaphore(4);
    @org.springframework.beans.factory.annotation.Autowired
    private com.h.backend.chat.infrastructure.persistence.mapper.AguiRunSnapshotMapper snapshots;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.h.backend.chat.application.HarnessCollaborationService collaboration;
    @org.springframework.beans.factory.annotation.Autowired
    private com.h.backend.chat.application.HarnessMemoryDocumentManager memory;
    public OrderCommandCoordinator(OrderJobMapper jobs,OrderMcpClient mcp,ChatSessionService sessions,AguiChatRuntime runtime){
        this.jobs=jobs;this.mcp=mcp;this.sessions=sessions;this.runtime=runtime;
    }
    public void owned(long user,String thread){
        var session=sessions.getSessionDetail(user,thread);
        if(!ChatAgentIds.ORDER_PROCESSING.equals(session.agentId()))throw new BusinessException(40300,"请在订单助手中操作");
    }
    public Map<String,Object> query(long user,String thread,String orderId){owned(user,thread);return mcp.query(user,thread,orderId);}
    public OrderJobMapper.Job submit(long user,String thread,Request request){
        if(request==null)throw new BusinessException(40000,"订单命令不能为空");
        if("RESUME_CHAT".equals(request.action()))throw new BusinessException(40300,"该操作仅供服务恢复使用");
        return enqueue(user,thread,request,true);
    }
    private OrderJobMapper.Job enqueue(long user,String thread,Request request,boolean operator){
        owned(user,thread);
        if(request.commandId()==null || !request.commandId().matches("[a-zA-Z0-9_-]{1,64}") || request.generation()==null
                || request.action()==null || request.orderId()==null || request.detail()!=null && request.detail().length()>1000)
            throw new BusinessException(40000,"订单命令格式无效");
        if(request.generation().length()>64 || request.orderId().length()>64 || request.action().length()>60 || request.revision()<0)
            throw new BusinessException(40000,"订单命令参数超出范围");
        String body=encode(request),hash=hash(thread+"\n"+operator+"\n"+body);
        jobs.insert(new OrderJobMapper.Job(request.commandId(),user,thread,hash,body,"PENDING",null,null,0,operator));
        var job=jobs.find(user,request.commandId());
        if(job==null || !job.requestHash().equals(hash))throw new BusinessException(40901,"命令编号已被其他请求使用");
        return job;
    }
    public OrderJobMapper.Job find(long user,String id){
        var job=jobs.find(user,id);if(job==null)throw new BusinessException(40404,"订单任务不存在");return job;
    }
    public void reference(long user,String thread,String orderId){
        var data=query(user,thread,orderId);runtime.publishOrders(user,thread,UUID.randomUUID().toString(),data);
    }

    public void projectToolResult(long user,String actualSession,Map<String,Object> result){
        String thread=collaboration.resolveExecutionSession(user,actualSession).rootSessionId();
        owned(user,thread);runtime.publishOrders(user,thread,UUID.randomUUID().toString(),result);
    }

    public Map<String,Object> agentCommand(long user,String actualSession,String orderId,String action,
            String generation,long revision,String detail){
        String thread=collaboration.resolveExecutionSession(user,actualSession).rootSessionId();
        String id=UUID.randomUUID().toString();
        Request request=new Request(id,orderId,action,generation,revision,detail);
        enqueue(user,thread,request,false);
        try {
            var result=mcp.command(user,actualSession,false,orderId,action,id,generation,revision,detail);
            jobs.applied(id,encode(result));
            runtime.publishOrders(user,thread,id,result);
            jobs.finish(id,"COMPLETED",null);
            return result;
        }catch(BusinessException error){jobs.finish(id,"FAILED",error.getMessage());throw error;}
    }

    @Scheduled(fixedDelay=1000)
    public void tick(){
        jobs.recoverStale();
        if(!permits.tryAcquire())return;
        OrderJobMapper.Job job;
        try{job=jobs.claim();}catch(RuntimeException error){permits.release();throw error;}
        if(job==null){permits.release();return;}
        workers.submit(()->{try{process(job);}finally{permits.release();}});
    }

    @jakarta.annotation.PreDestroy public void close(){workers.shutdownNow();}

    private void process(OrderJobMapper.Job job){
        try {
            owned(job.userId(),job.sessionId());
            var state=runtime.thread(job.userId(),job.sessionId());
            if(Boolean.TRUE.equals(state.get("executing"))){jobs.defer(job.id());return;}
            Request request=json.readValue(job.requestJson(),Request.class);
            if("RESUME_CHAT".equals(request.action())){
                notifyAssistant(job,request);jobs.finish(job.id(),"COMPLETED",null);return;
            }
            Map<String,Object> result;
            if(job.resultJson()!=null)result=json.readValue(job.resultJson(),new TypeReference<>(){});
            else {
                result=mcp.command(job.userId(),job.sessionId(),job.operator(),request.orderId(),request.action(),job.id(),
                        request.generation(),request.revision(),request.detail());
                jobs.applied(job.id(),encode(result));
            }
            runtime.publishOrders(job.userId(),job.sessionId(),job.id(),result);
            sessions.appendAssistantMessageIdempotent(job.userId(),job.sessionId(),summary(request,result),"order-job:"+job.id());
            var current=jobs.find(job.userId(),job.id());
            if(current!=null && "CANCELLED".equals(current.status()))return;
            if("RESET".equals(request.action()) && memory!=null){
                var scoped=memory.forAssistant(ChatAgentIds.ORDER_PROCESSING);var document=scoped.view(job.userId());
                String note="\n\n订单数据已重置，当前代次："+result.get("generation")+"。此前订单事件仅是历史记录，操作前必须查询当前 MCP 事实。";
                if(!document.content().contains(Objects.toString(result.get("generation"))))
                    scoped.save(job.userId(),document.content()+note,document.revision());
            }
            notifyAssistant(job,request);
            jobs.finish(job.id(),"COMPLETED",null);
        }catch(Exception error){
            String message=Objects.toString(error.getMessage(),"订单处理暂不可用");
            boolean business=error instanceof BusinessException;
            jobs.finish(job.id(),business||job.attempts()>=3?"FAILED":job.resultJson()==null?"PENDING":"APPLIED",
                    message.substring(0,Math.min(message.length(),500)));
        }
    }

    private void notifyAssistant(OrderJobMapper.Job job,Request request)throws Exception{
        if("RESET".equals(request.action()))return;
        String runId="order-notify-"+job.id();
        var previous=snapshots.find(job.userId(),runId);
        if(previous!=null && Set.of("FAILED","INTERRUPTED","CANCELLED").contains(previous.status()))runId+="-"+job.attempts();
        String message="RESUME_CHAT".equals(request.action())
                ? request.detail()
                : "订单 "+request.orderId()+" 的操作已处理，请查询当前业务状态，继续此前已授权的正常流程，并汇总结果和等待条件。取消、退款或特批仍必须有已确认业务方案。";
        String input=encode(Map.of("threadId",job.sessionId(),"runId",runId,
                "messages",List.of(Map.of("id","event-"+job.id(),"role","user","content",message)),
                "tools",List.of(),"context",List.of(),"state",Map.of()));
        var serializer=new com.agui.community.spring.server.core.JacksonSerializer();
        var parsed=serializer.deserialize(input,com.agui.community.core.agent.RunAgentInput.class);
        var stream="RESUME_CHAT".equals(request.action()) && request.anchorMessageId()!=null
                ? runtime.continueOrder(job.userId(),parsed,request.anchorMessageId()) : runtime.run(job.userId(),parsed);
        var event=stream
                .blockLast(java.time.Duration.ofMinutes(3));
        if(event instanceof com.agui.community.core.event.RunErrorEvent)throw new IllegalStateException("订单助手续办未完成，稍后重试");
    }

    @SuppressWarnings("unchecked") private String summary(Request request,Map<String,Object> result){
        if("RESET".equals(request.action()))return "订单数据已重置，旧业务方案和事件已失效。";
        var orders=(List<Map<String,Object>>)result.get("orders");
        var order=orders.getFirst();
        return "订单 "+order.get("id")+" 的操作已处理。当前阶段："+stage(order.get("stage"))+"；支付/退款："+stage(order.get("payment"))+"。详情及下一步请查看订单卡片。";
    }
    private String stage(Object value){return switch(Objects.toString(value,"")){
        case "NEW"->"待审核";case "WAITING_PAYMENT"->"等待支付";case "READY"->"待履约";case "SHIPPED"->"已发货";
        case "COMPLETED"->"已完成";case "CANCELLED"->"已取消";case "LOST"->"丢件结案";case "PAID"->"已支付";
        case "UNPAID"->"未支付";case "REFUNDED"->"已退款";case "REFUND_FAILED"->"退款失败，等待重试";default->Objects.toString(value,"");};}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String hash(String body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
