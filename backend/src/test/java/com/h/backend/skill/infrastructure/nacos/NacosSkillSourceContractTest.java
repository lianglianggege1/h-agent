package com.h.backend.skill.infrastructure.nacos;

import com.alibaba.nacos.maintainer.client.ai.SkillMaintainerServiceImpl;
import com.alibaba.nacos.maintainer.client.ai.SkillMaintainerService;
import com.alibaba.nacos.maintainer.client.config.ConfigMaintainerFactory;
import com.h.backend.skill.domain.SkillFileSet;
import com.h.backend.skill.domain.SkillPlatformException;
import com.h.backend.skill.domain.SkillPlatformErrorKind;
import com.h.backend.skill.domain.tar.DeterministicSkillTarBuilder;
import com.h.backend.skill.domain.tar.SkillTarReader;
import com.h.backend.skill.infrastructure.config.SkillPlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in. Creates and removes ONLY its own random namespace and test Skills. */
@EnabledIfEnvironmentVariable(named = "NACOS_SKILLS_CONTRACT", matches = "true")
class NacosSkillSourceContractTest {
    @Test
    void realServerPreservesBytesAtBusinessCapacityAndSupportsExactVersionLifecycle() throws Exception {
        String namespace = "h-agent-skill-contract-" + UUID.randomUUID();
        Properties sdk = new Properties();
        sdk.setProperty("serverAddr", System.getenv("NACOS_SERVER_ADDR"));
        sdk.setProperty("username", System.getenv().getOrDefault("NACOS_USERNAME", ""));
        sdk.setProperty("password", System.getenv().getOrDefault("NACOS_PASSWORD", ""));
        sdk.setProperty("namespace", namespace);
        var core = ConfigMaintainerFactory.createConfigMaintainerService(sdk);
        boolean created = false;
        try {
            assertThat(core.createNamespace(namespace, namespace, "Temporary Skill adapter contract test")).isTrue();
            created = true;
            var config = new SkillPlatformProperties();
            config.getNacos().setNamespace(namespace);
            var json = new ObjectMapper();
            var client = new SkillMaintainerServiceImpl(sdk);
            var repository = new NacosSkillSourceRepository(config, new DeterministicSkillTarBuilder(json), new SkillTarReader(json), json, client);
            Map<String, byte[]> files = new LinkedHashMap<>();
            String md = "---\r\nname: same-user-key\r\ndescription: 中文测试\r\n---\r\n\nExact original.\r\n\n";
            files.put("SKILL.md", md.getBytes(StandardCharsets.UTF_8));
            files.put("references/empty.txt", new byte[0]);
            files.put("references/deep/nested.txt", "中文\r\n".getBytes(StandardCharsets.UTF_8));
            var small = SkillFileSet.of(files);
            var first = repository.openDraft(11, small);
            small.files().forEach((path, bytes) -> assertThat(first.files().get(path)).isEqualTo(bytes));
            var sameKey = repository.openDraft(12, small);
            assertThat(sameKey.revision()).isEqualTo(first.revision());
            // 10MiB total source, mostly incompressible; every resource below 1MiB.
            Random random = new Random(37);
            int remaining = 10 * 1024 * 1024 - (int) small.totalBytes();
            for (int i = 0; remaining > 0; i++) {
                byte[] image = new byte[Math.min(remaining, 1024 * 1024)];
                random.nextBytes(image);
                files.put("assets/image-" + i + ".png", image);
                remaining -= image.length;
            }
            var large = repository.save(11, first.version(), first.revision(), SkillFileSet.of(files));
            files.forEach((path, bytes) -> assertThat(large.files().get(path)).isEqualTo(bytes));
            assertThatThrownBy(() -> repository.save(11, first.version(), first.revision(), small)).isInstanceOf(SkillPlatformException.class);
            var finalDraft = repository.save(11, first.version(), large.revision(), small);
            publishAfterConfiguredReview(repository, client, namespace, 11, finalDraft.version(), finalDraft.revision());
            repository.publish(11, finalDraft.version(), finalDraft.revision());
            var pinned = repository.read(11, first.version());
            assertThat(pinned.files().get("SKILL.md")).isEqualTo(small.get("SKILL.md"));
            assertThatThrownBy(() -> repository.discard(11, first.version(), first.revision())).isInstanceOf(SkillPlatformException.class);
            // Fork can choose ANY original content, not just the Nacos latest base.
            var next = repository.openDraft(11, SkillFileSet.of(Map.of("SKILL.md", "invalid draft\n".getBytes(StandardCharsets.UTF_8))));
            assertThat(next.version()).isNotEqualTo(first.version());
            repository.discard(11, next.version(), next.revision());
            assertThat(repository.read(11, first.version()).revision()).isEqualTo(first.revision());
            var historical = importAfterConfiguredReview(repository, client, namespace, 13, "v1", small);
            assertThat(repository.importPublished(13, "v1", small).version()).isEqualTo(historical.version());
            var historicalTwo = importAfterConfiguredReview(repository, client, namespace, 13, "v2", small);
            assertThat(historicalTwo.version()).isEqualTo("v2");
        } finally {
            if (created) {
                // Namespace deletion alone does not promise to delete underlying config files.
                var client = new SkillMaintainerServiceImpl(sdk);
                for (long id : new long[]{11, 12, 13}) {
                    try { client.deleteSkill(namespace, "h-agent-skill-" + id); }
                    catch (com.alibaba.nacos.api.exception.NacosException ex) { if (ex.getErrCode() != 404) throw ex; }
                }
                // Removed resource files can remain outside the current version manifest.
                // Only enumerate the random namespace created by THIS test.
                for (int page = 0; page < 10; page++) {
                    var leftovers = core.listConfigs(namespace).getPageItems();
                    if (leftovers == null || leftovers.isEmpty()) break;
                    for (var file : leftovers) {
                        assertThat(core.deleteConfig(file.getDataId(), file.getGroupName(), namespace)).isTrue();
                    }
                }
                assertThat(core.listConfigs(namespace).getPageItems()).isEmpty();
                assertThat(core.deleteNamespace(namespace)).isTrue();
            }
            core.shutdown();
        }
    }
    private static void publishAfterConfiguredReview(NacosSkillSourceRepository repository,
            SkillMaintainerService client, String namespace, long id, String version, String revision) throws Exception {
        awaitReview(() -> { repository.publish(id, version, revision); return null; }, client, namespace, id, version);
    }

    private static com.h.backend.skill.domain.source.SkillSourceRepository.SourceSnapshot importAfterConfiguredReview(
            NacosSkillSourceRepository repository, SkillMaintainerService client, String namespace, long id,
            String version, SkillFileSet files) throws Exception {
        return awaitReview(() -> repository.importPublished(id, version, files), client, namespace, id, version);
    }

    private static <T> T awaitReview(java.util.concurrent.Callable<T> action, SkillMaintainerService client,
            String namespace, long id, String version) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        String lastReview = null;
        while (true) {
            try { return action.call(); }
            catch (SkillPlatformException ex) {
                if (ex.kind() != SkillPlatformErrorKind.OPERATION_CONFLICT) {
                    var failed = client.getSkillMeta(namespace, "h-agent-skill-" + id).getVersions().stream()
                            .filter(v -> version.equals(v.getVersion())).findFirst().orElseThrow();
                    throw new AssertionError("Target source failed while reviewing: " + failed.getStatus()
                            + "; " + failed.getPublishPipelineInfo(), ex);
                }
                var summary = client.getSkillMeta(namespace, "h-agent-skill-" + id).getVersions().stream()
                        .filter(v -> version.equals(v.getVersion())).findFirst().orElseThrow();
                String state = summary.getStatus();
                String review = state + "; " + summary.getPublishPipelineInfo();
                if (!review.equals(lastReview)) {
                    System.out.println("Target test review: " + review);
                    lastReview = review;
                }
                if ("draft".equals(state) || System.nanoTime() >= deadline) {
                    throw new AssertionError("Target review gate did not approve: status=" + state
                            + ", pipeline=" + summary.getPublishPipelineInfo(), ex);
                }
                Thread.sleep(1000);
            }
        }
    }
}
