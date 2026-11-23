package com.h.otheragents.team.tool.springai;

import com.h.capabilities.memory.application.AgentMemory;
import com.h.capabilities.memory.application.AgentMemoryContext;
import com.h.capabilities.memory.application.MemoryToolContract;
import com.h.capabilities.memory.domain.AgentMemoryPolicyCatalog;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Spring AI 原生记忆工具集：把共享 MemoryToolContract 的每个定义构建成 FunctionToolCallback，
 * 可信身份在调用时从 ToolContext 取出（每轮由 ChatClient.toolContext 注入）。
 * Spring AI 无原生长效记忆，工具一律以 mem0_* 暴露（内部执行 canonical memory_*）；
 * 是否放行由 {@link AgentMemoryPolicyCatalog} 按逻辑 Agent 身份集中裁定。
 */
@Component
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class SpringMemoryToolProvider implements ToolCallbackProvider {

    private final AgentMemory memory;
    private final ObjectMapper json;
    private final AgentMemoryPolicyCatalog policies;

    public SpringMemoryToolProvider(AgentMemory memory, ObjectMapper json, AgentMemoryPolicyCatalog policies) {
        this.memory = memory;
        this.json = json;
        this.policies = policies;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return MemoryToolContract.definitions().stream()
                .map(definition -> FunctionToolCallback
                        .builder(MemoryToolContract.mem0Name(definition.name()),
                                (Map<String, Object> args, ToolContext toolContext) -> {
                                    AgentMemoryContext ctx = SpringToolContext.from(toolContext).memory();
                                    if (!policies.policyOf(ctx.stableAgentId()).explicitMemoryToolsEnabled()) {
                                        return "{\"error\":\"memory tools disabled for this agent\"}";
                                    }
                                    return memory.execute(ctx, UUID.randomUUID().toString(), definition.name(), args);
                                })
                        .description("Mem0：" + definition.description())
                        .inputSchema(json.writeValueAsString(definition.schema()))
                        .inputType(Map.class)
                        .build())
                .toArray(ToolCallback[]::new);
    }
}
