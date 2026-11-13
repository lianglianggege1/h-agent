package com.h.backend.skill.infrastructure.nacos;

import com.alibaba.nacos.api.ai.model.skills.Skill;
import com.alibaba.nacos.api.ai.model.skills.SkillMeta;
import com.alibaba.nacos.api.ai.model.skills.SkillResource;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.maintainer.client.ai.SkillMaintainerService;
import com.alibaba.nacos.maintainer.client.ai.SkillMaintainerServiceImpl;
import com.h.backend.skill.domain.SkillFileSet;
import com.h.backend.skill.domain.SkillPlatformErrorKind;
import com.h.backend.skill.domain.SkillPlatformException;
import com.h.backend.skill.domain.source.SkillSourceRepository;
import com.h.backend.skill.domain.tar.DeterministicSkillTarBuilder;
import com.h.backend.skill.domain.tar.SkillTarReader;
import com.h.backend.skill.infrastructure.config.SkillPlatformProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;

/** Nacos normalizes frontmatter. The versioned source envelope preserves the exact original
 * files (including invalid drafts and binary assets); SKILL.md is a readable mirror only.
 * Readable resources remain inspectable by the configured security scanner.
 */
@Component
public class NacosSkillSourceRepository implements SkillSourceRepository {
    private static final String TYPE = "h-agent-source";
    private static final String HEADER = "snapshot.json";
    private static final Set<String> TEXT_EXTENSIONS = Set.of("md", "txt", "json", "yaml", "yml");
    private static final int MAX_BUNDLE_BYTES = 16 * 1024 * 1024;
    private static final String EMPTY_FILE = "H Agent empty file.\n";
    private final SkillPlatformProperties.Nacos config;
    private final SkillPlatformProperties.Validation limits;
    private final DeterministicSkillTarBuilder builder;
    private final SkillTarReader reader;
    private final ObjectMapper json;
    private volatile SkillMaintainerService client;

    @Autowired
    public NacosSkillSourceRepository(SkillPlatformProperties properties, DeterministicSkillTarBuilder builder,
                                      SkillTarReader reader, ObjectMapper json) {
        this(properties, builder, reader, json, null);
    }

    NacosSkillSourceRepository(SkillPlatformProperties properties, DeterministicSkillTarBuilder builder,
                               SkillTarReader reader, ObjectMapper json, SkillMaintainerService client) {
        this.config = properties.getNacos();
        this.limits = properties.getValidation();
        this.builder = builder;
        this.reader = reader;
        this.json = json;
        this.client = client;
    }

    private synchronized SkillMaintainerService client() throws NacosException {
        if (client == null) {
            if (config.getServerAddr().isBlank() || config.getNamespace().isBlank()
                    || config.getUsername().isBlank() || config.getPassword().isBlank()) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.CREDENTIAL_UNAVAILABLE,
                        "Skill Nacos 地址、专用 namespace 和凭据必须配置");
            }
            Properties props = new Properties();
            props.setProperty("serverAddr", config.getServerAddr());
            props.setProperty("namespace", config.getNamespace());
            props.setProperty("username", config.getUsername());
            props.setProperty("password", config.getPassword());
            client = new SkillMaintainerServiceImpl(props);
        }
        return client;
    }

    private String name(long skillId) {
        String name = config.getNamePrefix() + skillId;
        if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || name.length() > 63) {
            throw new IllegalStateException("Skill Nacos name-prefix 必须生成至多 63 字符的 kebab-case 名称");
        }
        return name;
    }

    @Override
    public SourceSnapshot openDraft(long skillId, SkillFileSet seed) {
        return openDraft(skillId, seed, null);
    }

    private SourceSnapshot openDraft(long skillId, SkillFileSet seed, String targetVersion) {
        return call(() -> {
            String name = name(skillId);
            SkillMeta meta = findMeta(name);
            String version;
            if (meta != null && meta.getReviewingVersion() != null && !meta.getReviewingVersion().isBlank()) {
                throw conflict("Nacos 版本仍在审核，请重试原发布请求");
            }
            if (meta != null && meta.getEditingVersion() != null && !meta.getEditingVersion().isBlank()) {
                version = meta.getEditingVersion();
                if (targetVersion != null && !targetVersion.equals(version)) throw conflict("存在不同目标的迁移草稿");
                Skill existing = client().getSkillVersionDetail(config.getNamespace(), name, version);
                // A failed first creation may leave only the non-sensitive skeleton.
                if (existing.getResource() != null && existing.getResource().values().stream()
                        .anyMatch(r -> TYPE.equals(r.getType()) && HEADER.equals(r.getName()))) {
                    SourceSnapshot snapshot = decode(existing, version);
                    requirePrivate(name);
                    if (snapshot.revision().equals(fingerprint(seed))) return snapshot;
                    // No PG Proposal refers to this draft (caller invariant). This can be
                    // a fork whose seed write failed before PG registered the Proposal.
                    write(name, seed, null);
                    return verify(skillId, version, fingerprint(seed));
                }
            } else {
                // Never send user content before PRIVATE visibility is confirmed.
                String base = null;
                if (meta != null && meta.getVersions() != null) {
                    base = meta.getVersions().stream()
                            .filter(v -> "online".equals(v.getStatus()) || "offline".equals(v.getStatus()))
                            .map(SkillMeta.SkillVersionSummary::getVersion).findFirst().orElse(null);
                }
                // The HTTP form requires skillCard unless basedOnVersion is explicit,
                // even though the internal service can resolve an implicit latest base.
                String initial = base == null ? card(name, "", Map.of()) : null;
                version = client().createDraft(config.getNamespace(), name, base, targetVersion, initial, "h-agent draft");
            }
            requirePrivate(name);
            write(name, seed, null);
            return verify(skillId, version, fingerprint(seed));
        });
    }

    @Override
    public SourceSnapshot importPublished(long skillId, String targetVersion, SkillFileSet files) {
        return call(() -> {
            String revision = fingerprint(files);
            // Exact, deterministic migration coordinate. getSkillMeta exposes only 200 versions;
            // scanning that list would create duplicates when recovering a large history.
            Skill existing = null;
            try { existing = client().getSkillVersionDetail(config.getNamespace(), name(skillId), targetVersion); }
            catch (NacosException ex) { if (ex.getErrCode() != 404) throw ex; }
            if (existing != null && existing.getResource() != null && !existing.getResource().isEmpty()) {
                SourceSnapshot snapshot = read(skillId, targetVersion);
                if (!revision.equals(snapshot.revision())) throw drift("迁移目标版本已有不同内容");
                String state = status(client().getSkillMeta(config.getNamespace(), name(skillId)), targetVersion);
                if (!"offline".equals(state)) publish(skillId, targetVersion, revision);
                return snapshot;
            }
            SourceSnapshot draft = openDraft(skillId, files, targetVersion);
            publish(skillId, draft.version(), revision);
            return verify(skillId, draft.version(), revision);
        });
    }

    @Override
    public SourceSnapshot read(long skillId, String version) {
        if (version == null || version.isBlank() || "latest".equals(version)) {
            throw drift("必须指定准确的 Nacos 源码版本");
        }
        return call(() -> {
            SkillMeta meta = client().getSkillMeta(config.getNamespace(), name(skillId));
            if (meta == null || !"PRIVATE".equals(meta.getScope())) {
                throw drift("托管 Skill 不是 PRIVATE");
            }
            return decode(client().getSkillVersionDetail(config.getNamespace(), name(skillId), version), version);
        });
    }

    @Override
    public SourceSnapshot save(long skillId, String version, String expectedRevision, SkillFileSet files) {
        return call(() -> {
            SourceSnapshot before = read(skillId, version);
            String target = fingerprint(files);
            if (!before.revision().equals(expectedRevision)) {
                // Exact lost-response retry only; never overwrite a newer draft.
                if (before.revision().equals(target) && expectedRevision.equals(before.parentRevision())) return before;
                throw SkillPlatformException.of(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH);
            }
            if (target.equals(expectedRevision)) return before;
            SkillMeta meta = client().getSkillMeta(config.getNamespace(), name(skillId));
            if ("reviewed".equals(status(meta, version))) {
                if (!client().redraft(config.getNamespace(), name(skillId), version)) throw conflict("Nacos 无法恢复草稿编辑");
                meta = client().getSkillMeta(config.getNamespace(), name(skillId));
            }
            if (!version.equals(meta.getEditingVersion()) || !"draft".equals(status(meta, version))) {
                throw conflict("远端版本已提交发布，不能继续编辑");
            }
            write(name(skillId), files, expectedRevision);
            return verify(skillId, version, target);
        });
    }

    @Override
    public void publish(long skillId, String version, String expectedRevision) {
        call(() -> {
            verify(skillId, version, expectedRevision);
            SkillMeta meta = client().getSkillMeta(config.getNamespace(), name(skillId));
            String status = status(meta, version);
            if ("draft".equals(status)) {
                client().submit(config.getNamespace(), name(skillId), version);
                meta = client().getSkillMeta(config.getNamespace(), name(skillId));
                status = status(meta, version);
            }
            String review = reviewStatus(meta, version);
            if ("REJECTED".equals(review) || "FAILED".equals(review) || "ERROR".equals(review)) {
                throw conflict("Nacos 安全审核未通过，请查看审核结果并修改草稿");
            }
            if (!review.isBlank() && !"APPROVED".equals(review)) {
                throw conflict("Nacos 审核尚未完成，请稍后重试原发布请求");
            }
            if ("reviewed".equals(status) || ("reviewing".equals(status) && "APPROVED".equals(review)) || status == null) {
                if (!client().publish(config.getNamespace(), name(skillId), version, false)) {
                    throw conflict("Nacos 发布未完成");
                }
                status = status(client().getSkillMeta(config.getNamespace(), name(skillId)), version);
                // Ordinary publish is idempotent for online versions outside the metadata page.
                if (status == null) status = "online";
            }
            if (!"online".equals(status)) {
                throw conflict("Nacos 审核尚未通过，请查看后重试发布；不会自动设为生效");
            }
            verify(skillId, version, expectedRevision);
            return null;
        });
    }

    @Override
    public void discard(long skillId, String version, String expectedRevision) {
        call(() -> {
            SkillMeta meta = findMeta(name(skillId));
            if (meta == null) return null;
            String state = status(meta, version);
            if (state == null) return null; // Exact retry after delete succeeded but PG failed.
            boolean workingVersion = version.equals(meta.getEditingVersion()) || version.equals(meta.getReviewingVersion());
            if (!workingVersion || (!"draft".equals(state) && !"reviewed".equals(state))) {
                throw conflict("已提交或已发布的远端版本不能作为草稿删除，请完成原发布请求");
            }
            if (!expectedRevision.equals(read(skillId, version).revision())) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH);
            }
            if (!client().deleteDraft(config.getNamespace(), name(skillId))) throw conflict("Nacos 草稿删除未完成");
            return null;
        });
    }

    private SourceSnapshot verify(long skillId, String version, String expected) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (true) {
            try {
                SourceSnapshot snapshot = read(skillId, version);
                if (!expected.equals(snapshot.revision())) throw drift("Nacos 读回内容与确认内容不一致");
                return snapshot;
            } catch (SkillPlatformException ex) {
                // The Config-backed provider can briefly expose a new manifest with old
                // cached files after update. Accept ONLY the fully verified target digest.
                if (ex.kind() != SkillPlatformErrorKind.SOURCE_DRIFTED || System.nanoTime() >= deadline) throw ex;
                try { Thread.sleep(100); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw SkillPlatformException.of(SkillPlatformErrorKind.SOURCE_UNAVAILABLE);
                }
            }
        }
    }

    private void requirePrivate(String name) throws NacosException {
        if (!client().updateScope(config.getNamespace(), name, "PRIVATE")
                || !"PRIVATE".equals(client().getSkillMeta(config.getNamespace(), name).getScope())) {
            throw drift("无法确认 Nacos Skill 私有可见性");
        }
    }

    private void write(String name, SkillFileSet files, String parent) throws NacosException {
        byte[] bundle = builder.build(files);
        if (bundle.length > MAX_BUNDLE_BYTES) throw conflict("Skill 源码包过大");
        // Validate our serialization before replacing any remote content.
        reader.parse(bundle);
        Map<String, SkillResource> resources = new LinkedHashMap<>();
        List<SourceFile> entries = new ArrayList<>();
        for (String path : files.sortedPaths()) {
            if (path.startsWith(TYPE + "/")) throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_INVALID, "Skill 文件占用源码清单保留路径");
            String nativePath = "SKILL.md".equals(path) ? TYPE + "/original-skill.md" : path;
            byte[] bytes = files.get(path);
            String text = textContent(path, bytes);
            String encoding = bytes.length == 0 ? "empty" : text == null ? "base64" : "utf8";
            add(resources, nativePath, "empty".equals(encoding) ? EMPTY_FILE : "base64".equals(encoding)
                    ? Base64.getEncoder().encodeToString(bytes) : text, encoding);
            entries.add(new SourceFile(path, nativePath, bytes.length, DeterministicSkillTarBuilder.sha256Hex(bytes), encoding));
        }
        Envelope envelope = new Envelope(2, DeterministicSkillTarBuilder.sha256Hex(bundle), parent, bundle.length, entries);
        add(resources, TYPE + "/" + HEADER, json.writeValueAsString(envelope), "utf8");
        if (!client().updateDraft(config.getNamespace(), card(name, files.requireText("SKILL.md"), resources), false,
                "h-agent source " + envelope.revision())) throw conflict("Nacos 草稿保存未完成");
    }

    private String card(String name, String originalMd, Map<String, SkillResource> resources) {
        String mirror = "---\nname: " + name + "\ndescription: Managed by H Agent\n---\n\n"
                + body(originalMd);
        return json.writeValueAsString(Map.of("namespaceId", config.getNamespace(), "name", name,
                "description", "Managed by H Agent", "skillMd", mirror, "resource", resources));
    }

    private static String body(String originalMd) {
        return "H Agent source snapshot. Edit through the application.\n\n" + (originalMd == null ? "" : originalMd);
    }

    private void add(Map<String, SkillResource> resources, String path, String content, String encoding) {
        SkillResource resource = new SkillResource();
        int slash = path.lastIndexOf('/');
        resource.setType(slash < 0 ? "" : path.substring(0, slash));
        resource.setName(slash < 0 ? path : path.substring(slash + 1));
        resource.setContent(content);
        resource.setMetadata(Map.of("encoding", encoding));
        resources.put(resource.getResourceIdentifier(), resource);
    }

    private static String textContent(String path, byte[] bytes) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || !TEXT_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT))) return null;
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException ex) { return null; } // Invalid drafts still round-trip; Validator blocks release.
    }

    private SourceSnapshot decode(Skill skill, String version) {
        try {
            if (skill == null || skill.getResource() == null) throw drift("Nacos 源码快照缺失");
            Map<String, SkillResource> resources = new LinkedHashMap<>();
            for (SkillResource r : skill.getResource().values()) {
                String path = r.getType() == null || r.getType().isBlank() ? r.getName() : r.getType() + "/" + r.getName();
                if (resources.put(path, r) != null) throw drift("Nacos 源码包含重复资源");
            }
            SkillResource header = resources.get(TYPE + "/" + HEADER);
            if (header == null || header.getContent() == null || header.getContent().length() > 256 * 1024) throw drift("Nacos 源码清单缺失或过大");
            Envelope envelope = json.readValue(header.getContent(), Envelope.class);
            if (envelope.schema() != 2 || envelope.size() <= 0 || envelope.size() > MAX_BUNDLE_BYTES
                    || envelope.files() == null || envelope.files().size() > limits.getMaxFiles()
                    || resources.size() != envelope.files().size() + 1) throw drift("Nacos 源码清单或资源集合不一致");
            Map<String, byte[]> original = new LinkedHashMap<>();
            Set<String> seen = new java.util.HashSet<>();
            long total = 0;
            for (SourceFile entry : envelope.files()) {
                String expectedPath = "SKILL.md".equals(entry.path()) ? TYPE + "/original-skill.md" : entry.path();
                if (!expectedPath.equals(entry.resourcePath()) || !seen.add(entry.resourcePath())
                        || entry.size() < 0 || entry.size() > limits.getMaxFileBytes()) throw drift("Nacos 源码路径或大小非法");
                SkillResource resource = resources.get(entry.resourcePath());
                if (resource == null || resource.getContent() == null || resource.getContent().length() > Math.max(1024L, 2 * limits.getMaxFileBytes())) throw drift("Nacos 源码资源缺失或过大");
                byte[] bytes;
                if ("empty".equals(entry.encoding()) && EMPTY_FILE.equals(resource.getContent())) bytes = new byte[0];
                else if ("utf8".equals(entry.encoding())) bytes = resource.getContent().getBytes(StandardCharsets.UTF_8);
                else if ("base64".equals(entry.encoding())) bytes = Base64.getDecoder().decode(resource.getContent());
                else throw drift("Nacos 源码编码非法");
                total += bytes.length;
                if (total > limits.getMaxTotalBytes() || bytes.length != entry.size()
                        || !DeterministicSkillTarBuilder.sha256Hex(bytes).equals(entry.sha256())
                        || original.put(entry.path(), bytes) != null) throw drift("Nacos 源文件校验失败");
            }
            SkillFileSet files = SkillFileSet.of(original);
            byte[] bundle = builder.build(files);
            reader.parse(bundle);
            if (bundle.length != envelope.size() || !fingerprint(files).equals(envelope.revision())) throw drift("Nacos 源码 SHA-256 不符");
            String md = skill.getSkillMd();
            int end = md == null ? -1 : md.indexOf("\n---", 3);
            if (end < 0 || !md.substring(end + 4).strip().equals(body(files.requireText("SKILL.md")).strip())) {
                throw drift("Nacos SKILL.md 镜像被外部修改");
            }
            return new SourceSnapshot(version, envelope.revision(), envelope.parentRevision(), files);
        } catch (SkillPlatformException ex) { throw ex; }
        catch (RuntimeException ex) { throw drift("Nacos 源码快照无法验证"); }
    }

    private String fingerprint(SkillFileSet files) { return DeterministicSkillTarBuilder.sha256Hex(builder.build(files)); }
    private static String status(SkillMeta meta, String version) {
        return meta.getVersions() == null ? null : meta.getVersions().stream()
                .filter(v -> version.equals(v.getVersion())).map(SkillMeta.SkillVersionSummary::getStatus).findFirst().orElse(null);
    }
    private String reviewStatus(SkillMeta meta, String version) {
        if (meta.getVersions() == null) return "";
        String info = meta.getVersions().stream().filter(v -> version.equals(v.getVersion()))
                .map(SkillMeta.SkillVersionSummary::getPublishPipelineInfo).filter(java.util.Objects::nonNull)
                .findFirst().orElse("");
        if (info.isBlank()) return "";
        try {
            var result = json.readTree(info);
            return result.path("historical").asBoolean(false) ? "" : result.path("status").asText("");
        }
        catch (RuntimeException ex) { throw drift("Nacos 审核元信息非法"); }
    }

    private SkillMeta findMeta(String name) throws NacosException {
        try { return client().getSkillMeta(config.getNamespace(), name); }
        catch (NacosException ex) { if (ex.getErrCode() == 404) return null; throw ex; }
    }
    private <T> T call(RemoteCall<T> call) {
        try { return call.run(); }
        catch (NacosException ex) {
            // SDK messages can contain URLs, credentials or content. Never forward them.
            throw SkillPlatformException.of(ex.getErrCode() == 401 || ex.getErrCode() == 403
                    ? SkillPlatformErrorKind.CREDENTIAL_UNAVAILABLE : SkillPlatformErrorKind.SOURCE_UNAVAILABLE);
        }
    }
    private static SkillPlatformException conflict(String message) { return SkillPlatformException.of(SkillPlatformErrorKind.OPERATION_CONFLICT, message); }
    private static SkillPlatformException drift(String message) { return SkillPlatformException.of(SkillPlatformErrorKind.SOURCE_DRIFTED, message); }
    @FunctionalInterface private interface RemoteCall<T> { T run() throws NacosException; }
    private record Envelope(int schema, String revision, String parentRevision, int size, List<SourceFile> files) { }
    private record SourceFile(String path, String resourcePath, int size, String sha256, String encoding) { }
}
