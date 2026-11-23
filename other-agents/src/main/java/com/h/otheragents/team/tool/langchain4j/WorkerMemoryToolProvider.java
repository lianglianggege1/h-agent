package com.h.otheragents.team.tool.langchain4j;

import com.h.capabilities.memory.application.AgentMemory;
import com.h.capabilities.memory.application.AgentMemoryContext;
import com.h.capabilities.memory.application.MemoryToolContract;
import com.h.capabilities.memory.domain.AgentMemoryPolicyCatalog;
import com.h.capabilities.shared.infrastructure.ai.ToolSchemas;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * LangChain4j 原生记忆工具集：每次调用固定可信身份，把共享契约构建为 ToolSpecification。
 * LangChain4j 无原生长效记忆，工具一律以 mem0_* 暴露（内部执行 canonical memory_*）；
 * 是否提供由 {@link AgentMemoryPolicyCatalog} 按逻辑 Agent 身份集中裁定。
 */
@Component
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class WorkerMemoryToolProvider implements ToolProvider {

    private final AgentMemory memory;
    private final ObjectMapper json;
    private final AgentMemoryPolicyCatalog policies;

    public WorkerMemoryToolProvider(AgentMemory memory, ObjectMapper json, AgentMemoryPolicyCatalog policies) {
        this.memory = memory;
        this.json = json;
        this.policies = policies;
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
        if (!policies.policyOf(ctx.stableAgentId()).explicitMemoryToolsEnabled()) {
            return builder.build();
        }
        for (var definition : MemoryToolContract.definitions()) {
            var spec = ToolSpecification.builder()
                    .name(MemoryToolContract.mem0Name(definition.name()))
                    .description("Mem0：" + definition.description())
                    .parameters((JsonObjectSchema) ToolSchemas.schema(definition.schema()))
                    .build();
            builder.add(spec, (call, id) ->
                    memory.execute(ctx, call.id(), definition.name(), json.readValue(call.arguments(), Map.class)));
        }
        return builder.build();
    }

    @Override
    public boolean isDynamic() {
        return true;
    }
}
