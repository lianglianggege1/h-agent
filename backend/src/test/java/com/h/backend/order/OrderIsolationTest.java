package com.h.backend.order;

import com.h.backend.chat.application.HarnessMemoryDocumentManager;
import com.h.backend.chat.application.agui.AguiRunProjection;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.domain.approval.ApprovalMode;
import com.agui.community.core.event.*;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderIsolationTest {
    @Test void orderMemoryIsSeparateFromSameUsersGenericHarnessAndOtherUsers(){
        var store=new InMemoryStore();var generic=new HarnessMemoryDocumentManager(store,Clock.systemUTC());
        var orders=generic.forAssistant(ChatAgentIds.ORDER_PROCESSING);
        generic.save(1,"通用偏好",0);orders.save(1,"订单偏好",0);orders.save(2,"另一个用户",0);
        assertEquals("通用偏好",generic.view(1).content());assertEquals("订单偏好",orders.view(1).content());
        assertEquals("另一个用户",orders.view(2).content());assertFalse(generic.view(2).exists());
        assertEquals(ApprovalMode.DEFAULT,ApprovalMode.resolveForNewSession(ChatAgentIds.ORDER_PROCESSING,ApprovalMode.DEFAULT));
    }
    @Test void cardIdentityIsStableWithinAChatAndDistinctAcrossChats(){
        var payload=Map.<String,Object>of("kind","order-demo","generation","g1","orders",List.of(Map.of("id","ORD-1","revision",1)));
        var a=new AguiRunProjection("chat-a","run-1",ignored->{});
        var b=new AguiRunProjection("chat-b","run-2",ignored->{});
        a.orders(payload);a.orders(payload);b.orders(payload);
        assertEquals(1,a.activityEvents().size());
        var first=(ActivitySnapshotEvent)a.activityEvents().getFirst();
        var second=(ActivitySnapshotEvent)b.activityEvents().getFirst();
        assertNotEquals(first.messageId(),second.messageId());
        assertEquals("chat-a",((Map<?,?>)first.content()).get("threadId"));
        var restored=new AguiRunProjection("chat-a","run-1",ignored->{});restored.restore(a.snapshot());
        assertEquals(a.activityEvents(),restored.activityEvents());
    }
}
