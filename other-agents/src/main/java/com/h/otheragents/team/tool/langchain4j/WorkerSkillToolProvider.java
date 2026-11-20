package com.h.otheragents.team.tool.langchain4j;

import com.h.capabilities.skill.application.SkillRuntimeService;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.skills.Skills;
import org.springframework.stereotype.Component;

/**
 * LangChain4j 原生技能工具集：以 memoryId 找到本轮固定的 Runtime Snapshot，
 * 直接用框架 Skills API 暴露 activate_skill / read_skill_resource；无快照返回空。
 */
@Component
public class WorkerSkillToolProvider implements ToolProvider {

    private final SkillRuntimeService skillRuntimeService;

    public WorkerSkillToolProvider(SkillRuntimeService skillRuntimeService) {
        this.skillRuntimeService = skillRuntimeService;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        if (request == null || request.chatMemoryId() == null) {
            return ToolProviderResult.builder().build();
        }
        Skills skills = skillRuntimeService.langchainSkillsFor(request.chatMemoryId().toString());
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