package com.h.backend.chat.infrastructure.config;

import dev.langchain4j.skills.FileSystemSkillLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class VideoPromptSkillTest {
    @Test
    void loadsVideoPromptSkillFromConfiguredSkillsDirectory() {
        Path skillsDirectory = Path.of("src", "main", "resources", "skills").toAbsolutePath();

        assertFalse(FileSystemSkillLoader.loadSkills(skillsDirectory).isEmpty());
    }
}
