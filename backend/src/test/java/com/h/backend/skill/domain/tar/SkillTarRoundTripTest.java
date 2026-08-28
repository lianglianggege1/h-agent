package com.h.backend.skill.domain.tar;

import com.h.backend.skill.domain.SkillFileSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillTarRoundTripTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeterministicSkillTarBuilder builder = new DeterministicSkillTarBuilder(objectMapper);
    private final SkillTarReader reader = new SkillTarReader(objectMapper);

    private static Map<String, byte[]> sampleFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", """
                ---
                name: demo-skill
                description: Demo skill
                ---

                # Demo

                Body.
                """.getBytes());
        files.put("skill.yaml", """
                schemaVersion: 1
                key: demo-skill
                displayName: Demo
                capabilities:
                  scripts: false
                """.getBytes());
        files.put("references/guide.md", "guide".getBytes());
        return files;
    }

    @Test
    void buildIsDeterministicAcrossInsertionOrderAndRuns() {
        Map<String, byte[]> first = new LinkedHashMap<>();
        first.put("SKILL.md", "a".getBytes());
        first.put("references/b.md", "b".getBytes());

        Map<String, byte[]> second = new LinkedHashMap<>();
        second.put("references/b.md", "b".getBytes());
        second.put("SKILL.md", "a".getBytes());

        byte[] builtFirst = builder.build(SkillFileSet.of(first));
        byte[] builtSecond = builder.build(SkillFileSet.of(second));
        byte[] builtAgain = builder.build(SkillFileSet.of(first));

        assertThat(builtFirst).isEqualTo(builtSecond);
        assertThat(builtFirst).isEqualTo(builtAgain);
        assertThat(builtFirst.length % 10240).isZero();
    }

    @Test
    void roundTripPreservesFilesAndManifest() {
        SkillTarReader.ParsedBundle parsed = reader.parse(builder.build(SkillFileSet.of(sampleFiles())));

        assertThat(parsed.files().requireText("SKILL.md")).contains("name: demo-skill");
        assertThat(parsed.files().requireText("references/guide.md")).isEqualTo("guide");
        assertThat(parsed.manifest().schemaVersion()).isEqualTo(1);
        assertThat(parsed.manifest().files())
                .extracting(SkillBundleManifest.Entry::path)
                .containsExactly("SKILL.md", "references/guide.md", "skill.yaml");
    }

    @Test
    void manifestDigestMatchesFileContent() {
        SkillTarReader.ParsedBundle parsed = reader.parse(builder.build(SkillFileSet.of(sampleFiles())));

        for (SkillBundleManifest.Entry entry : parsed.manifest().files()) {
            assertThat(entry.sha256())
                    .isEqualTo(DeterministicSkillTarBuilder.sha256Hex(parsed.files().get(entry.path())));
            assertThat(entry.size()).isEqualTo(parsed.files().get(entry.path()).length);
        }
    }

    @Test
    void parseRejectsBundleWithoutManifest() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", "x".getBytes());

        byte[] tar = builder.build(SkillFileSet.of(files));
        tar[0] = 'R';

        assertThatThrownBy(() -> reader.parse(tar)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parseRejectsTamperedContent() {
        byte[] tar = builder.build(SkillFileSet.of(sampleFiles()));
        // SKILL.md 是排序后的第一个条目：512 字节头之后即其内容区
        tar[512 + 20] ^= 0x01;

        assertThatThrownBy(() -> reader.parse(tar)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parseRejectsEmptyBundle() {
        assertThatThrownBy(() -> reader.parse(new byte[0])).isInstanceOf(IllegalStateException.class);
    }
}
