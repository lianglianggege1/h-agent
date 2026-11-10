package com.h.backend.knowledge;

import com.h.backend.knowledge.application.*;
import com.h.backend.knowledge.interfaces.tool.*;
import com.h.backend.memory.application.AgentMemoryContext;
import com.h.backend.memory.infrastructure.MemoryExecutionContextResolver;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.*;
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

class KnowledgeToolAdaptersTest {
    final AgentMemoryContext ctx=new AgentMemoryContext(7L,"root","root","harness-agent","assistant",null,"run");
    @Test void toolsExposeOnlyTrustedIdentityAndDoNotBrowseOrSearchDuringRegistration() {
        var knowledge=mock(KnowledgeTools.class);
        var provider=new KnowledgeToolProvider(knowledge,new ObjectMapper());
        var request=ToolProviderRequest.builder().userMessage(UserMessage.from("hello")).invocationContext(
                InvocationContext.builder().invocationParameters(InvocationParameters.from(AgentMemoryContext.KEY,ctx)).build()).build();
        var result=provider.provideTools(request);
        assertEquals(7,result.tools().size());verifyNoInteractions(knowledge);
        when(knowledge.execute(ctx,"actual-call","knowledge_save",Map.of("title","method","content","body"))).thenReturn("OK");
        assertEquals("OK",result.toolExecutorByName("knowledge_save").execute(ToolExecutionRequest.builder().id("actual-call")
                .name("knowledge_save").arguments("{\"title\":\"method\",\"content\":\"body\"}").build(),"ignored"));
        var resolver=mock(MemoryExecutionContextResolver.class);var runtime=RuntimeContext.empty();when(resolver.resolve(runtime)).thenReturn(ctx);
        var toolkit=new Toolkit();new AgentScopeKnowledgeTools(knowledge,resolver).register(toolkit);
        assertTrue(toolkit.getTool("knowledge_search").isReadOnly());assertFalse(toolkit.getTool("knowledge_save").isReadOnly());
        toolkit.getTool("knowledge_save").callAsync(ToolCallParam.builder().runtimeContext(runtime)
                .toolUseBlock(ToolUseBlock.builder().id("actual-call").name("knowledge_save").input(Map.of("title","method","content","body")).build())
                .input(Map.of("title","method","content","body")).build()).block();
        verify(knowledge,times(2)).execute(ctx,"actual-call","knowledge_save",Map.of("title","method","content","body"));
        assertFalse(toolkit.getToolNames().contains("knowledge_delete"));
        for(var definition:KnowledgeToolContract.definitions()) {
            String schema=definition.schema().toString();assertFalse(schema.contains("promptId"));assertFalse(schema.contains("userId"));
        }
    }
}
