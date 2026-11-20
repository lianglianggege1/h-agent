package com.h.otheragents.team.tool.langchain4j;

import com.h.capabilities.knowledge.application.KnowledgeToolContract;
import com.h.capabilities.knowledge.application.KnowledgeTools;
import com.h.capabilities.memory.application.AgentMemoryContext;
import com.h.capabilities.shared.infrastructure.ai.ToolSchemas;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/** LangChain4j 原生知识库工具集：每次调用固定可信身份，把共享契约构建为 ToolSpecification。 */
@Component
public class WorkerKnowledgeToolProvider implements ToolProvider {

    private final KnowledgeTools knowledge;
    private final ObjectMapper json;

    public WorkerKnowledgeToolProvider(KnowledgeTools knowledge, ObjectMapper json) {
        this.knowledge = knowledge;
        this.json = json;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        var builder = ToolProviderResult.builder();
        if (request == null || request.invocationParameters() == null) {
            return builder.build();
        }
        if (!(request.invocationParameters().get(AgentMemoryContext.KEY) instanceof AgentMemoryContext ctx)) {
            return builder.build();
        }
        for (var definition : KnowledgeToolContract.definitions()) {
            var spec = ToolSpecification.builder()
                    .name(definition.name())
                    .description(definition.description())
                    .parameters((JsonObjectSchema) ToolSchemas.schema(definition.schema()))
                    .build();
            builder.add(spec, (call, id) ->
                    knowledge.execute(ctx, call.id(), definition.name(), json.readValue(call.arguments(), Map.class)));
        }
        return builder.build();
    }

    @Override
    public boolean isDynamic() {
        return true;
    }
}