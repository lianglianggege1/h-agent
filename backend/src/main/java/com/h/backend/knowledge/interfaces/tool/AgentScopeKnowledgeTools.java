package com.h.backend.knowledge.interfaces.tool;

import com.h.backend.knowledge.application.*;
import com.h.backend.memory.infrastructure.MemoryExecutionContextResolver;
import io.agentscope.core.tool.*;
import io.agentscope.core.message.ToolResultBlock;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.Map;

@Component
public class AgentScopeKnowledgeTools {
    private final KnowledgeTools knowledge;
    private final MemoryExecutionContextResolver identity;
    public AgentScopeKnowledgeTools(KnowledgeTools knowledge,MemoryExecutionContextResolver identity){this.knowledge=knowledge;this.identity=identity;}
    public void register(Toolkit toolkit) {
        for(var d:KnowledgeToolContract.definitions()) toolkit.registerAgentTool(new AgentTool(){
            public String getName(){return d.name();}
            public String getDescription(){return d.description();}
            public Map<String,Object> getParameters(){return d.schema();}
            public boolean isReadOnly(){return d.readOnly();}
            public Mono<ToolResultBlock> callAsync(ToolCallParam call){
                return Mono.fromCallable(()->ToolResultBlock.text(knowledge.execute(identity.resolve(call.getRuntimeContext()),
                        call.getToolUseBlock().getId(),d.name(),call.getInput()))).subscribeOn(Schedulers.boundedElastic());
            }
        });
    }
}
