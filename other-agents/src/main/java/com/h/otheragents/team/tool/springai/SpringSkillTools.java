package com.h.otheragents.team.tool.springai;

import com.h.backend.skill.application.SkillRuntimeService;
import com.h.backend.skill.application.SkillRuntimeService.SkillContent;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spring AI 原生技能工具入口：按本轮固定的快照内容构建 activate_skill / read_skill_resource，
 * 语义对齐 LangChain4j Skills（激活返回 SKILL.md 指令，资源读取返回预加载文本）。
 */
public final class SpringSkillTools {

    private SpringSkillTools() {
    }

    public static ToolCallback[] forRun(SkillRuntimeService skills, String memoryId, ObjectMapper json) {
        List<SkillContent> contents = skills.skillContentsFor(memoryId);
        if (contents.isEmpty()) {
            return new ToolCallback[0];
        }
        Map<String, SkillContent> byName = contents.stream()
                .collect(Collectors.toMap(SkillContent::name, Function.identity(), (a, b) -> a,
                        java.util.LinkedHashMap::new));
        List<ToolCallback> callbacks = new ArrayList<>();
        callbacks.add(FunctionToolCallback
                .builder("activate_skill", (Map<String, Object> args) -> activate(byName, args))
                .description("Returns the full instructions for a skill. Call this before following any skill-specific steps.")
                .inputSchema(json.writeValueAsString(Map.of(
                        "type", "object",
                        "properties", Map.of("skill_name", Map.of(
                                "type", "string", "description", "The name of the skill to activate")),
                        "required", List.of("skill_name"),
                        "additionalProperties", false)))
                .inputType(Map.class)
                .build());
        boolean hasResources = contents.stream().anyMatch(skill -> !skill.resources().isEmpty());
        if (hasResources) {
            callbacks.add(FunctionToolCallback
                    .builder("read_skill_resource", (Map<String, Object> args) -> readResource(byName, args))
                    .description("Returns the content of a resource referenced in the skill")
                    .inputSchema(json.writeValueAsString(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "skill_name", Map.of("type", "string",
                                            "description", "The name of the skill for which the resource should be read"),
                                    "relative_path", Map.of("type", "string",
                                            "description", "Relative path to the resource.")),
                            "required", List.of("skill_name", "relative_path"),
                            "additionalProperties", false)))
                    .inputType(Map.class)
                    .build());
        }
        return callbacks.toArray(new ToolCallback[0]);
    }

    private static String activate(Map<String, SkillContent> byName, Map<String, Object> args) {
        String name = String.valueOf(args.get("skill_name"));
        SkillContent skill = byName.get(name);
        if (skill == null) {
            return "There is no skill with name '%s'. Available skills: [%s]".formatted(name, available(byName));
        }
        return skill.instructions();
    }

    private static String readResource(Map<String, SkillContent> byName, Map<String, Object> args) {
        String name = String.valueOf(args.get("skill_name"));
        String path = String.valueOf(args.get("relative_path"));
        SkillContent skill = byName.get(name);
        if (skill == null) {
            return "There is no skill with name '%s'".formatted(name);
        }
        String content = skill.resources().get(path);
        if (content == null) {
            String available = skill.resources().keySet().stream()
                    .map(p -> "'" + p + "'").collect(Collectors.joining(", "));
            return "There is no resource for skill '%s' with the path '%s'. Available resources: [%s]"
                    .formatted(name, path, available);
        }
        return content;
    }

    private static String available(Map<String, SkillContent> byName) {
        return byName.keySet().stream().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
    }
}