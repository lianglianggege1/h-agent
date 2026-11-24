package com.h.backend.skill;

import com.h.backend.chat.application.impl.ChatServiceImpl;
import com.h.capabilities.skill.domain.ArtifactDescriptor;
import com.h.capabilities.skill.domain.tar.DeterministicSkillTarBuilder;
import com.h.capabilities.skill.domain.tar.SkillTarReader;
import com.h.capabilities.skill.infrastructure.artifact.BundledSystemSkillArtifacts;
import com.h.capabilities.skill.infrastructure.config.SkillPlatformProperties;
import com.h.capabilities.skill.infrastructure.validation.SkillContentValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.UrlResource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class BundledChartSkillTest {
    @Test
    void deploymentDeclaresAndShipsAVerifiedChartSkill() throws Exception {
        var mainResources = ChatServiceImpl.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        var configuration = new YamlPropertySourceLoader()
                .load("application", new UrlResource(mainResources.resolve("application.yml")));
        var skills = new Binder(ConfigurationPropertySources.from(configuration))
                .bind("skill-platform.system-skills", Bindable.listOf(SkillPlatformProperties.SystemSkill.class))
                .get();
        var skill = skills.stream().filter(item -> "h-agent-chat-charts".equals(item.getKey())).findFirst().orElseThrow();
        assertThat(skill.isEnabled()).isTrue();
        assertThat(skill.isBundled()).isTrue();
        assertThat(skill.getRevision()).isEqualTo("3");
        assertThat(skill.getRequiredTools()).containsExactly("show_chart");

        var json = JsonMapper.builder().build();
        var artifacts = new BundledSystemSkillArtifacts(
                new SkillContentValidator(new SkillContentValidator.Quotas(20, 1048576, 10485760, 200, 8)),
                new DeterministicSkillTarBuilder(json), new SkillTarReader(json));
        var bundle = artifacts.open(skill.getKey());
        assertThat(bundle.descriptor().mediaType()).isEqualTo(ArtifactDescriptor.MEDIA_TYPE);
        assertThat(bundle.descriptor().digest()).matches("sha256:[0-9a-f]{64}");
        assertThat(bundle.files().requireText("SKILL.md")).contains("show_chart", "\"intent\": \"trend\"", "optionJson", "read_chat_chart");
        assertThat(artifacts.open(skill.getKey())).isSameAs(bundle);
    }
}
