package com.h.backend.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.agui.AguiChatRuntime;
import com.h.backend.chat.infrastructure.persistence.mapper.AguiRunSnapshotMapper;
import com.h.backend.chat.interfaces.dto.ChatSessionMetaDto;
import com.agui.community.core.event.RunFinishedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderCommandCoordinatorTest {
    @Test void recoveryUsesSavedResultWithoutRepeatingBusinessCommand()throws Exception{
        var jobs=mock(OrderJobMapper.class);var mcp=mock(OrderMcpClient.class);var sessions=mock(ChatSessionService.class);var runtime=mock(AguiChatRuntime.class);
        var request=new OrderCommandCoordinator.Request("cmd","ORD-1","PAYMENT_RECEIVED","g",1,"");
        var payload=Map.of("kind","order-demo","generation","g","orders",List.of(Map.of("id","ORD-1","stage","SHIPPED","payment","PAID")));
        var json=new ObjectMapper();
        var job=new OrderJobMapper.Job("cmd",1L,"chat","hash",json.writeValueAsString(request),"APPLIED",json.writeValueAsString(payload),null,1,true);
        when(jobs.claim()).thenReturn(job,null);when(sessions.getSessionDetail(1L,"chat")).thenReturn(meta());
        when(runtime.thread(1L,"chat")).thenReturn(Map.of("executing",false));
        when(runtime.run(eq(1L),any())).thenReturn(Flux.just(new RunFinishedEvent("chat","run")));
        var coordinator=new OrderCommandCoordinator(jobs,mcp,sessions,runtime);
        ReflectionTestUtils.setField(coordinator,"snapshots",mock(AguiRunSnapshotMapper.class));
        try {
            coordinator.tick();
            verify(jobs,timeout(3000)).finish("cmd","COMPLETED",null);
            verifyNoInteractions(mcp);
            verify(runtime).publishOrders(eq(1L),eq("chat"),eq("cmd"),anyMap());
            verify(sessions).appendAssistantMessageIdempotent(eq(1L),eq("chat"),contains("已发货"),eq("order-job:cmd"));
        }finally{coordinator.close();}
    }
    @Test void aBusyChatDefersItsJobWithoutExecutingIt()throws Exception{
        var jobs=mock(OrderJobMapper.class);var mcp=mock(OrderMcpClient.class);var sessions=mock(ChatSessionService.class);var runtime=mock(AguiChatRuntime.class);
        when(jobs.claim()).thenReturn(new OrderJobMapper.Job("cmd",1L,"chat","hash","{}","PENDING",null,null,1,true));
        when(sessions.getSessionDetail(1L,"chat")).thenReturn(meta());when(runtime.thread(1L,"chat")).thenReturn(Map.of("executing",true));
        var coordinator=new OrderCommandCoordinator(jobs,mcp,sessions,runtime);
        try{coordinator.tick();verify(jobs,timeout(3000)).defer("cmd");verifyNoInteractions(mcp);}finally{coordinator.close();}
    }
    @Test void anotherAssistantCannotUseTheOrderOperationEntry(){
        var sessions=mock(ChatSessionService.class);when(sessions.getSessionDetail(1L,"chat")).thenReturn(
                new ChatSessionMetaDto("chat","",null,"harness-agent","协作","","HARNESS_STREAMING",null,0,null,null,false));
        var mcp=mock(OrderMcpClient.class);var coordinator=new OrderCommandCoordinator(mock(OrderJobMapper.class),mcp,sessions,mock(AguiChatRuntime.class));
        try{assertThrows(com.h.backend.common.exception.BusinessException.class,()->coordinator.query(1,"chat","ORD-1"));verifyNoInteractions(mcp);}
        finally{coordinator.close();}
    }
    private ChatSessionMetaDto meta(){return new ChatSessionMetaDto("chat","",null,"order-processing-assistant","订单","","HARNESS_STREAMING",null,0,null,null,false);}
}
