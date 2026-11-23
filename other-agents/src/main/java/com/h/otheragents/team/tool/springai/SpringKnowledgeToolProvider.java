package com.h.otheragents.team.tool.springai;

import com.h.capabilities.knowledge.application.KnowledgeToolContract;
import com.h.capabilities.knowledge.application.KnowledgeTools;
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
 * Spring AI 原生知识库工具集：把共享 KnowledgeToolContract 的每个定义构建成 FunctionToolCallback，
 * 可信身份在调用时从 ToolContext 取出。
 */
@Component
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class SpringKnowledgeToolProvider implements ToolCallbackProvider {

    private final KnowledgeTools knowledge;
    private final ObjectMapper json;

    public SpringKnowledgeToolProvider(KnowledgeTools knowledge, ObjectMapper json) {
        this.knowledge = knowledge;
        this.json = json;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return KnowledgeToolContract.definitions().stream()
                .map(definition -> FunctionToolCallback
                        .builder(definition.name(), (Map<String, Object> args, ToolContext toolContext) ->
                                knowledge.execute(SpringToolContext.from(toolContext).memory(),
                                        UUID.randomUUID().toString(), definition.name(), args))
                        .description(definition.description())
                        .inputSchema(json.writeValueAsString(definition.schema()))
                        .inputType(Map.class)
                        .build())
                .toArray(ToolCallback[]::new);
    }
}
