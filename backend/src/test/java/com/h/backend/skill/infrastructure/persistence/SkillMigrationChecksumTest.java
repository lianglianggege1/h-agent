package com.h.backend.skill.infrastructure.persistence;

import org.flywaydb.core.internal.resource.StringResource;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SkillMigrationChecksumTest {
    @Test
    void preservesAlreadyAppliedNacosSourceMigrationChecksum() throws Exception {
        try (var in = getClass().getResourceAsStream("/db/migration/V20261113_01__nacos_skill_sources.sql")) {
            assertNotNull(in);
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(136503525, ChecksumCalculator.calculate(new StringResource(sql)),
                    "Already applied migration must stay immutable; use a new version for additional DDL");
        }
    }
}
