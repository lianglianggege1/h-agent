package com.h.backend.order;

import com.h.agent.observability.NoopAgentObservability;
import com.h.backend.chat.domain.agent.*;
import com.h.backend.chat.infrastructure.agentscope.DisabledHarnessModel;
import com.h.backend.observability.agentscope.AgentScopeObservationInstaller;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.*;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderHarnessConfigTest {
    @TempDir Path workspace;
    @Test void buildsIndependentHarnessAndRealRestrictedStageAgents(){
        var tools=new OrderAgentTools(mock(OrderMcpClient.class),mock(OrderCommandCoordinator.class));
        var files=new InMemoryStore();
        var store=DistributedStore.builder().agentStateStore(mock(io.agentscope.core.state.AgentStateStore.class)).baseStore(files).build();
        var config=new OrderHarnessConfig();
        try(var root=config.orderHarness(new DisabledHarnessModel(),store,MemoryConfig.builder().build(),CompactionConfig.builder().build(),
                tools,new AgentScopeObservationInstaller(NoopAgentObservability.getInstance()),new ParentAssignmentSystemPromptMiddleware(),
                new HarnessSubagentLifecycleMiddleware((u,s,a,c)->{}),workspace.toString())){
            assertEquals(ChatAgentIds.ORDER_PROCESSING,root.getName());
            assertEquals(AgentRuntimeType.HARNESS_STREAMING,config.orderAgentDefinition(root).runtimeType());
            var context=RuntimeContext.builder().userId("11").sessionId("root-session").build();
            root.getWorkspaceManager().writeUtf8WorkspaceRelative(context,"MEMORY.md","订单偏好");
            var manager=new com.h.backend.chat.application.HarnessMemoryDocumentManager(files,java.time.Clock.systemUTC());
            assertEquals("订单偏好",manager.forAssistant(ChatAgentIds.ORDER_PROCESSING).view(11).content());
            assertFalse(manager.view(11).exists());
            for(String name:new String[]{"order-audit","order-fulfillment","order-aftersales"}){
                var child=root.getSubagentAgentManager().createAgentIfPresent(name,context).orElseThrow();
                assertInstanceOf(HarnessAgent.class,child);
                try(var harness=(HarnessAgent)child){
                    assertTrue(harness.getToolkit().getToolNames().contains("order_lookup"));
                    assertTrue(harness.getToolkit().getToolNames().contains("order_operate"));
                    assertFalse(harness.getToolkit().getToolNames().contains("write_file"));
                    assertFalse(harness.getToolkit().getToolNames().contains("memory_get"));
                    assertFalse(harness.getToolkit().getToolNames().contains("agent_spawn"));
                }
            }
        }
    }
    @Test void workerCannotInvokeAnOperationOutsideItsStage(){
        var delegate=mock(OrderAgentTools.class);var worker=new OrderWorkerTools(delegate,"order-audit");
        assertThrows(IllegalArgumentException.class,()->worker.operate(null,"order","FULFILL","g",1,""));
        verifyNoInteractions(delegate);
    }

    @Test void nativeAgentLoopInjectsTrustedIdentityAndReconstructsConversationFromTheStore(){
        var mcp=mock(OrderMcpClient.class);var coordinator=mock(OrderCommandCoordinator.class);
        when(mcp.query(11,"chat-a","ORD-DEMO-001")).thenReturn(java.util.Map.of("kind","order-demo","generation","g","orders",java.util.List.of()));
        var tools=new OrderAgentTools(mcp,coordinator);
        var state=mock(io.agentscope.core.state.AgentStateStore.class,
                org.mockito.AdditionalAnswers.delegatesTo(new InMemoryAgentStateStore()));
        var store=DistributedStore.builder().agentStateStore(state).baseStore(new InMemoryStore()).build();
        io.agentscope.core.model.Model model=new io.agentscope.core.model.Model(){
            @Override public String getModelName(){return "scripted-order-test";}
            @Override public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(
                    java.util.List<io.agentscope.core.message.Msg> messages,java.util.List<io.agentscope.core.model.ToolSchema> schemas,
                    io.agentscope.core.model.GenerateOptions options){
                var last=messages.getLast();
                boolean query=last.getRole()==io.agentscope.core.message.MsgRole.USER && schemas.stream().anyMatch(s->"order_lookup".equals(s.getName()));
                io.agentscope.core.message.ContentBlock content=query
                        ?io.agentscope.core.message.ToolUseBlock.builder().id("lookup-1").name("order_lookup")
                            .input(java.util.Map.of("order_id","ORD-DEMO-001")).content("{\"order_id\":\"ORD-DEMO-001\"}").build()
                        :io.agentscope.core.message.TextBlock.builder().text("查询完成").build();
                return reactor.core.publisher.Flux.just(io.agentscope.core.model.ChatResponse.builder().id(java.util.UUID.randomUUID().toString())
                        .content(java.util.List.of(content)).finishReason(query?"tool_use":"stop").build());
            }
        };
        var config=new OrderHarnessConfig();
        var observation=new AgentScopeObservationInstaller(NoopAgentObservability.getInstance());
        var assignment=new ParentAssignmentSystemPromptMiddleware();var lifecycle=new HarnessSubagentLifecycleMiddleware((u,s,a,c)->{});
        var memory=MemoryConfig.builder().flushTrigger(MemoryConfig.FlushTrigger.never()).build();
        try(var root=config.orderHarness(model,store,memory,CompactionConfig.builder().build(),tools,observation,assignment,lifecycle,workspace.toString())){
            var reply=root.call("请查询订单，用户一的独立会话",RuntimeContext.builder().userId("11").sessionId("chat-a").build()).block(java.time.Duration.ofSeconds(15));
            assertNotNull(reply);assertEquals("查询完成",reply.getTextContent());
            verify(mcp).query(11,"chat-a","ORD-DEMO-001");
        }
        try(var restored=config.orderHarness(model,store,memory,CompactionConfig.builder().build(),tools,observation,assignment,lifecycle,workspace.toString())){
            var history=restored.getDelegate().getAgentState("11","chat-a").getContext();
            assertTrue(history.stream().anyMatch(m->m.getTextContent()!=null && m.getTextContent().contains("用户一的独立会话")));
            assertTrue(restored.getDelegate().getAgentState("22","chat-b").getContext().isEmpty());
        }
    }
}
