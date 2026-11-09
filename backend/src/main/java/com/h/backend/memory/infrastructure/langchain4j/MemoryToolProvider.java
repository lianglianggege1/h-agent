package com.h.backend.memory.infrastructure.langchain4j;

import com.h.backend.memory.application.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.service.tool.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Captures immutable trusted identity once per AI Service invocation. */
@Component
public class MemoryToolProvider implements ToolProvider {
    private final AgentMemory memory;
    private final ObjectMapper json;
    public MemoryToolProvider(AgentMemory memory,ObjectMapper json) {this.memory=memory;this.json=json;}
    @Override public ToolProviderResult provideTools(ToolProviderRequest request) {
        var builder=ToolProviderResult.builder();
        if(request==null || request.invocationParameters()==null) return builder.build();
        Object value=request.invocationParameters().get(AgentMemoryContext.KEY);
        if(!(value instanceof AgentMemoryContext ctx)) return builder.build();
        for(var definition:MemoryToolContract.definitions()) {
            var spec=ToolSpecification.builder().name(definition.name()).description(definition.description())
                    .parameters((JsonObjectSchema)schema(definition.schema())).build();
            builder.add(spec,(call,id) -> memory.execute(ctx,call.id(),definition.name(),json.readValue(call.arguments(),Map.class)));
        }
        return builder.build();
    }
    @Override public boolean isDynamic() {return true;}
    @SuppressWarnings("unchecked")
    static JsonSchemaElement schema(Map<String,Object> s) {
        String description=Objects.toString(s.get("description"),"");
        if(s.containsKey("enum")) return JsonEnumSchema.builder().enumValues((List<String>)s.get("enum")).description(description).build();
        return switch(s.get("type").toString()) {
            case "object" -> {
                var b=JsonObjectSchema.builder().additionalProperties(false).required((List<String>)s.get("required"));
                ((Map<String,Object>)s.get("properties")).forEach((key,value)->b.addProperty(key,schema((Map<String,Object>)value)));
                yield b.build();
            }
            case "array" -> JsonArraySchema.builder().items(schema((Map<String,Object>)s.get("items"))).description(description).build();
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            default -> JsonStringSchema.builder().description(description).build();
        };
    }
}
