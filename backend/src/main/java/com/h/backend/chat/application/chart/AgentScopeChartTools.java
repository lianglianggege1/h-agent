package com.h.backend.chat.application.chart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.capabilities.chart.ChatChart;
import com.h.capabilities.chart.ChatChartTools;
import com.h.capabilities.skill.infrastructure.artifact.BundledSystemSkillArtifacts;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
public class AgentScopeChartTools implements HarnessRuntimeMiddleware {
    private final ChatChartTools tools;
    private final BundledSystemSkillArtifacts skills;
    private final ObjectMapper json = new ObjectMapper();

    public AgentScopeChartTools(ChatChartTools tools, BundledSystemSkillArtifacts skills) {
        this.tools = tools;
        this.skills = skills;
    }

    public void register(Toolkit toolkit, Function<RuntimeContext, ChatChartTools.Scope> scope) {
        for (var specification : ChatChartTools.specifications()) {
            var parameters = JsonSchemaElementJsonUtils.toMap(specification.parameters());
            toolkit.registerAgentTool(new AgentTool() {
                public String getName() { return specification.name(); }
                public String getDescription() { return specification.description(); }
                public Map<String, Object> getParameters() { return parameters; }
                public boolean isReadOnly() { return !ChatChart.TOOL_NAME.equals(getName()); }
                public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
                    return Mono.fromCallable(() -> {
                        var context = scope.apply(call.getRuntimeContext());
                        var scoped = new ChatChartTools.Scope(context.readChart(), context.publish(), path -> {
                            var file = readWorkspaceFile(call, path);
                            return file != null || context.readMapFile() == null ? file : context.readMapFile().apply(path);
                        });
                        var result = tools.invoke(getName(), json.writeValueAsString(call.getInput()), scoped);
                        return result.isError() ? ToolResultBlock.error(result.resultText()) : ToolResultBlock.text(result.resultText());
                    }).subscribeOn(Schedulers.boundedElastic());
                }
            });
        }
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        if (!hasTool(agent, context, ChatChart.TOOL_NAME)) return Mono.just(prompt);
        var instructions = skills.open("h-agent-chat-charts").files().requireText("SKILL.md");
        return Mono.just(prompt + "\n\n" + instructions);
    }

    private static byte[] readWorkspaceFile(ToolCallParam call, String path) {
        var context = call.getRuntimeContext();
        if (!(call.getAgent() instanceof ReActAgent agent) || !hasTool(agent, context, "read_file")) return null;
        AbstractFilesystem.validatePath(path);
        var state = context.getAgentState();
        if (state != null && state.getPermissionContext().getDenyRules().containsKey("read_file")) return null;
        var fs = context.get(AbstractFilesystem.class);
        if (fs != null) {
            var normalizer = context.get(WorkspacePathNormalizer.class);
            var files = fs.downloadFiles(context, List.of(normalizer == null ? path : normalizer.normalize(path)));
            if (files.size() == 1 && files.getFirst().isSuccess()) return files.getFirst().content();
        }
        // Direct child ReAct executions can bind the workspace to the tool instead of RuntimeContext.
        var input = Map.<String, Object>of("path", path);
        var use = ToolUseBlock.builder().id(call.getToolUseBlock().getId() + "-geojson")
                .name("read_file").input(input).build();
        var result = agent.getToolkit().getTool("read_file").callAsync(ToolCallParam.builder()
                .agent(agent).runtimeContext(context).toolUseBlock(use).input(input).build())
                .block(Duration.ofSeconds(15));
        if (result == null || result.getOutput().size() != 1
                || !(result.getOutput().getFirst() instanceof TextBlock text)) return null;
        String content = text.getText();
        return content == null || content.startsWith("Error:") ? null : content.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean hasTool(Agent agent, RuntimeContext context, String name) {
        if (!(agent instanceof ReActAgent react)) return false;
        var toolkit = react.getToolkit();
        var state = context == null ? null : context.getAgentState();
        var schemas = state == null ? toolkit.getToolSchemas()
                : toolkit.getToolSchemas(state.getToolContext().getActivatedGroups());
        return schemas.stream().anyMatch(schema -> name.equals(schema.getName()));
    }
}
