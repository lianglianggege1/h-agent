package com.h.backend.shared.infrastructure.ai;

import dev.langchain4j.model.chat.request.json.*;
import java.util.*;

/** Schema conversion shared by framework adapters, independent of any business module. */
public final class ToolSchemas {
    private ToolSchemas() {}
    @SuppressWarnings("unchecked")
    public static JsonSchemaElement schema(Map<String,Object> s) {
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
