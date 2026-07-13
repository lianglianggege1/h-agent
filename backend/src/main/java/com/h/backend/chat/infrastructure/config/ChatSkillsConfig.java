package com.h.backend.chat.infrastructure.config;

import dev.langchain4j.skills.FileSystemSkill;
import dev.langchain4j.skills.FileSystemSkillLoader;
import dev.langchain4j.skills.Skills;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Configuration
@EnableConfigurationProperties(ChatSkillsProperties.class)
public class ChatSkillsConfig {

    @Bean
    @ConditionalOnProperty(prefix = "chat.skills", name = "enabled", havingValue = "true")
    public Skills chatSkills(ChatSkillsProperties properties) {
        Path directory = resolveDirectory(properties);
        List<FileSystemSkill> loadedSkills = FileSystemSkillLoader.loadSkills(directory);
        if (loadedSkills.isEmpty()) {
            throw new IllegalStateException("No valid skills found in " + directory);
        }
        return Skills.from(loadedSkills);
    }

    private Path resolveDirectory(ChatSkillsProperties properties) {
        if (properties.getDirectory() == null || properties.getDirectory().isBlank()) {
            throw new IllegalStateException("chat.skills.directory must be configured when chat.skills.enabled=true");
        }

        Path directory = Path.of(properties.getDirectory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Skill directory does not exist or is not a directory: " + directory);
        }
        return directory;
    }
}
