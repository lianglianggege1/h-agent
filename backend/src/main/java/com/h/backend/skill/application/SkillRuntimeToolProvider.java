package com.h.backend.skill.application;

import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.skills.Skills;
import org.springframework.stereotype.Component;

/**
 * 按请求解析 Skill 工具集（设计 §14）：以 memoryId 找到本次执行固定的
 * Runtime Snapshot，把快照内已验证的 Skill 暴露为 activate_skill /
 * read_skill_resource。快照未注册（Subagent 或无 Skill 用户）时返回空结果，
 * 不回源查询“最新版”。
 */
@Component
public class SkillRuntimeToolProvider implements ToolProvider {

    private final SkillRuntimeService skillRuntimeService;

    public SkillRuntimeToolProvider(SkillRuntimeService skillRuntimeService) {
        this.skillRuntimeService = skillRuntimeService;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        if (request == null) {
            return ToolProviderResult.builder().build();
        }
        Object memoryId = request.chatMemoryId();
        if (memoryId == null) {
            return ToolProviderResult.builder().build();
        }
        Skills skills = skillRuntimeService.langchainSkillsFor(memoryId.toString());
        if (skills == null) {
            return ToolProviderResult.builder().build();
        }
        return skills.toolProvider().provideTools(request);
    }

    @Override
    public boolean isDynamic() {
        return true;
    }
}
