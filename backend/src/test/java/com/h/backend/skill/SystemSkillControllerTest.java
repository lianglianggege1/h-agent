package com.h.backend.skill;

import com.h.backend.common.exception.GlobalExceptionHandler;
import com.h.backend.skill.interfaces.web.SystemSkillController;
import com.h.capabilities.skill.domain.tar.DeterministicSkillTarBuilder;
import com.h.capabilities.skill.domain.tar.SkillTarReader;
import com.h.capabilities.skill.infrastructure.artifact.BundledSystemSkillArtifacts;
import com.h.capabilities.skill.infrastructure.config.SkillPlatformProperties;
import com.h.capabilities.skill.infrastructure.validation.SkillContentValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SystemSkillControllerTest {
    private static final String SYSTEM_SKILLS = "/api/me/skills/system";
    private static final String CHARTS = SYSTEM_SKILLS + "/h-agent-chat-charts";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var properties = new SkillPlatformProperties();
        var charts = new SkillPlatformProperties.SystemSkill();
        charts.setKey("h-agent-chat-charts");
        charts.setDisplayName("聊天图表");
        charts.setRevision("4");
        charts.setBundled(true);
        var external = new SkillPlatformProperties.SystemSkill();
        external.setKey("external-skill");
        properties.getSystemSkills().addAll(List.of(charts, external));

        var json = JsonMapper.builder().build();
        var artifacts = new BundledSystemSkillArtifacts(
                new SkillContentValidator(new SkillContentValidator.Quotas(20, 1048576, 10485760, 200, 8)),
                new DeterministicSkillTarBuilder(json), new SkillTarReader(json));
        mvc = MockMvcBuilders.standaloneSetup(new SystemSkillController(properties, artifacts))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listsOnlyBundledSkillsWithTheirConfiguredMetadata() throws Exception {
        mvc.perform(get(SYSTEM_SKILLS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].skillKey").value("h-agent-chat-charts"))
                .andExpect(jsonPath("$.data[0].displayName").value("聊天图表"))
                .andExpect(jsonPath("$.data[0].revision").value("4"));
    }

    @Test
    void readsTheSameBundledFilesUsedByTheRuntime() throws Exception {
        mvc.perform(get(CHARTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].path").value("SKILL.md"))
                .andExpect(jsonPath("$.data.files[0].content").value(containsString("show_chart")))
                .andExpect(jsonPath("$.data.files[1].path").value("skill.yaml"))
                .andExpect(jsonPath("$.data.files[1].content").value(containsString("key: h-agent-chat-charts")));
    }

    @Test
    void rejectsWritesAndDoesNotExposeExternalSkillFiles() throws Exception {
        mvc.perform(put(CHARTS)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(CHARTS)).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(CHARTS)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get(SYSTEM_SKILLS + "/external-skill")).andExpect(status().isNotFound());
    }
}
