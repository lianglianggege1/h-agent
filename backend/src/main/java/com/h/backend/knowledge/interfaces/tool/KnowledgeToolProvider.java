package com.h.backend.knowledge.interfaces.tool;

import com.h.backend.knowledge.application.*;
import com.h.backend.memory.application.AgentMemoryContext;
import com.h.backend.shared.infrastructure.ai.ToolSchemas;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;

@Component
public class KnowledgeToolProvider implements ToolProvider {
    private final KnowledgeTools knowledge;
    private final ObjectMapper json;
    public KnowledgeToolProvider(KnowledgeTools knowledge,ObjectMapper json){this.knowledge=knowledge;this.json=json;}
    @Override public ToolProviderResult provideTools(ToolProviderRequest request) {
        var tools=ToolProviderResult.builder();
        if(request==null || request.invocationParameters()==null) return tools.build();
        Object value=request.invocationParameters().get(AgentMemoryContext.KEY);
        if(!(value instanceof AgentMemoryContext ctx)) return tools.build();
        for(var definition:KnowledgeToolContract.definitions()) {
            var spec=ToolSpecification.builder().name(definition.name()).description(definition.description())
                    .parameters((JsonObjectSchema)ToolSchemas.schema(definition.schema())).build();
            tools.add(spec,(call,ignored)->knowledge.execute(ctx,call.id(),definition.name(),json.readValue(call.arguments(),Map.class)));
        }
        return tools.build();
    }
    @Override public boolean isDynamic(){return true;}
}
