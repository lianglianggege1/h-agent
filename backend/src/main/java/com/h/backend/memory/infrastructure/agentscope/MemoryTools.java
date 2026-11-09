package com.h.backend.memory.infrastructure.agentscope;

import com.h.backend.memory.application.*;
import com.h.backend.memory.infrastructure.MemoryExecutionContextResolver;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.Map;

/** Programmatic tools retain the framework's real tool-call ID for idempotency. */
@Component
public class MemoryTools {
    private final AgentMemory memory;
    private final MemoryExecutionContextResolver contexts;
    public MemoryTools(AgentMemory memory,MemoryExecutionContextResolver contexts) {this.memory=memory;this.contexts=contexts;}
    public void register(Toolkit toolkit) {
        for(var definition:MemoryToolContract.definitions()) toolkit.registerAgentTool(new AgentTool() {
            public String getName() {return MemoryToolContract.harnessName(definition.name());}
            public String getDescription() {return "Mem0：" + definition.description();}
            public Map<String,Object> getParameters() {return definition.schema();}
            public boolean isReadOnly() {return definition.readOnly();}
            public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
                return Mono.fromCallable(() -> {
                    AgentMemoryContext ctx=contexts.resolve(call.getRuntimeContext());
                    String result=memory.execute(ctx,call.getToolUseBlock().getId(),definition.name(),call.getInput());
                    return ToolResultBlock.text(result);
                }).subscribeOn(Schedulers.boundedElastic());
            }
        });
    }
}
