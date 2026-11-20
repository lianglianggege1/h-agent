package com.h.otheragents.team.tool.springai;

import com.h.backend.memory.application.AgentMemory;
import com.h.backend.memory.application.MemoryToolContract;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Spring AI 原生记忆工具集：把共享 MemoryToolContract 的每个定义构建成 FunctionToolCallback，
 * 可信身份在调用时从 ToolContext 取出（每轮由 ChatClient.toolContext 注入）。
 */
@Component
public class SpringMemoryToolProvider implements ToolCallbackProvider {

    private final AgentMemory memory;
    private final ObjectMapper json;

    public SpringMemoryToolProvider(AgentMemory memory, ObjectMapper json) {
        this.memory = memory;
        this.json = json;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return MemoryToolContract.definitions().stream()
                .map(definition -> FunctionToolCallback
                        .builder(definition.name(), (Map<String, Object> args, ToolContext toolContext) ->
                                memory.execute(SpringToolContext.from(toolContext).memory(),
                                        UUID.randomUUID().toString(), definition.name(), args))
                        .description(definition.description())
                        .inputSchema(json.writeValueAsString(definition.schema()))
                        .inputType(Map.class)
                        .build())
                .toArray(ToolCallback[]::new);
    }
}