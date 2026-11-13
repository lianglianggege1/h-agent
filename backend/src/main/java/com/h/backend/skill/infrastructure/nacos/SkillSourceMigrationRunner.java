package com.h.backend.skill.infrastructure.nacos;

import com.h.backend.skill.application.SkillCatalogService;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillDefinitionMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicit opt-in migration before readiness. Each Skill commits independently and can be retried. */
@Component
@ConditionalOnProperty(prefix = "skill-platform.migration", name = "enabled", havingValue = "true")
public class SkillSourceMigrationRunner implements ApplicationRunner {
    private final SkillDefinitionMapper definitions;
    private final SkillCatalogService catalog;

    public SkillSourceMigrationRunner(SkillDefinitionMapper definitions, SkillCatalogService catalog) {
        this.definitions = definitions;
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (var definition : definitions.selectSourceMigrationCandidates()) {
            catalog.migrateSources(definition.getOwnerUserId(), definition.getId());
        }
    }
}
