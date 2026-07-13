package com.h.backend.chat.config;

import com.h.backend.chat.infrastructure.config.ChatSkillsConfig;
import com.h.backend.chat.infrastructure.config.ChatSkillsProperties;
import dev.langchain4j.skills.Skills;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSkillsConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldNotCreateSkillsBeanWhenDisabled() {
        contextRunner
                .withPropertyValues("chat.skills.enabled=false")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeansOfType(Skills.class)).isEmpty();
                });
    }

    @Test
    void shouldLoadSkillsFromConfiguredExternalDirectory(@TempDir Path skillsDirectory) throws Exception {
        Path skillDirectory = Files.createDirectories(skillsDirectory.resolve("greeting"));
        Files.writeString(skillDirectory.resolve("SKILL.md"), """
                ---
                name: greeting
                description: Greets the user
                ---

                Say hello.
                """);

        contextRunner
                .withPropertyValues(
                        "chat.skills.enabled=true",
                        "chat.skills.directory=" + skillsDirectory
                )
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    Skills skills = context.getBean(Skills.class);
                    assertThat(skills.formatAvailableSkills()).contains("greeting", "Greets the user");
                });
    }

    @Test
    void shouldFailWhenEnabledDirectoryDoesNotExist(@TempDir Path temporaryDirectory) {
        contextRunner
                .withPropertyValues(
                        "chat.skills.enabled=true",
                        "chat.skills.directory=" + temporaryDirectory.resolve("missing")
                )
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Skill directory does not exist or is not a directory: "
                                + temporaryDirectory.resolve("missing").toAbsolutePath().normalize()));
    }

    @Configuration
    @EnableConfigurationProperties(ChatSkillsProperties.class)
    @Import(ChatSkillsConfig.class)
    static class TestConfig {
    }
}
