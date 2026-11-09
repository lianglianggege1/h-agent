package com.h.backend.memory;

import com.h.backend.memory.application.*;
import com.h.backend.memory.infrastructure.MemoryExecutionContextResolver;
import com.h.backend.memory.infrastructure.agentscope.MemoryTools;
import com.h.backend.memory.infrastructure.langchain4j.MemoryToolProvider;
import dev.langchain4j.invocation.*;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolProviderRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryToolAdaptersTest {
    final AgentMemoryContext ctx=new AgentMemoryContext(7L,"root","root","standard-chat","standard-chat",19L,"run");
    @Test void langchainToolsCaptureIdentityWithoutEagerRecall() {
        var module=mock(AgentMemory.class);
        var provider=new MemoryToolProvider(module,new ObjectMapper());
        var request=ToolProviderRequest.builder().userMessage(UserMessage.from("hello")).invocationContext(InvocationContext.builder()
                .invocationParameters(InvocationParameters.from(AgentMemoryContext.KEY,ctx))
                .userMessage(UserMessage.from("hello")).build()).build();
        var tools=provider.provideTools(request);
        assertEquals(MemoryToolContract.definitions().size(),tools.tools().size());
        verifyNoInteractions(module);
        when(module.execute(ctx,"call-9","memory_save",Map.of("text","remember","scope","USER"))).thenReturn("ok");
        String result=tools.toolExecutorByName("memory_save").execute(ToolExecutionRequest.builder()
                .id("call-9").name("memory_save").arguments("{\"text\":\"remember\",\"scope\":\"USER\"}").build(),"untrusted-id");
        assertEquals("ok",result);
        assertFalse(provider.provideTools(ToolProviderRequest.builder().userMessage(UserMessage.from("hello")).invocationContext(InvocationContext.builder()
                .userMessage(UserMessage.from("hello")).build()).build()).tools().size()>0);
    }
    @Test void agentscopeUsesActualFrameworkToolCallIdAndSameSchema() {
        var module=mock(AgentMemory.class);
        var resolver=mock(MemoryExecutionContextResolver.class);
        var runtime=RuntimeContext.builder().userId("7").sessionId("root").build();
        when(resolver.resolve(runtime)).thenReturn(ctx);
        when(module.execute(ctx,"actual-call","memory_search",Map.of("query","past decision"))).thenReturn("{\"status\":\"OK\"}");
        var toolkit=new Toolkit();
        new MemoryTools(module,resolver).register(toolkit);
        verifyNoInteractions(module);
        assertEquals(MemoryToolContract.definitions().size(),toolkit.getToolNames().size());
        var tool=toolkit.getTool("mem0_search");
        assertTrue(tool.isReadOnly());
        assertFalse(toolkit.getTool("mem0_save").isReadOnly());
        tool.callAsync(ToolCallParam.builder().runtimeContext(runtime)
                .toolUseBlock(ToolUseBlock.builder().id("actual-call").name("mem0_search").input(Map.of("query","past decision")).build())
                .input(Map.of("query","past decision")).build()).block();
        verify(module).execute(ctx,"actual-call","memory_search",Map.of("query","past decision"));
        assertFalse(tool.getParameters().toString().contains("userId"));
    }
}
