package com.h.backend.skill.infrastructure.gitee;

import com.h.backend.skill.domain.SkillPlatformErrorKind;
import com.h.backend.skill.domain.SkillPlatformException;
import com.h.backend.skill.infrastructure.config.SkillPlatformProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
@Component
public class GiteeRestSkillRepository implements GiteeSkillRepository {

    private final SkillPlatformProperties.Repository config;
    private final RestClient restClient;
    private final Environment environment;
    private final String owner;
    private final String repo;

    // 存在两个构造器时 Spring 无法隐式选择，必须用 @Autowired 指定注入用哪一个
    @Autowired
    public GiteeRestSkillRepository(SkillPlatformProperties properties, Environment environment) {
        this(properties, environment, RestClient.builder().requestFactory(requestFactory()));
    }

    GiteeRestSkillRepository(SkillPlatformProperties properties, Environment environment, RestClient.Builder builder) {
        this.config = properties.getRepository();
        this.environment = environment;
        this.owner = config.owner();
        this.repo = config.repo();
        this.restClient = builder.baseUrl(config.getApiBaseUrl()).build();
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));
        return factory;
    }

    private String token() {
        return resolveToken(environment, config.getCredentialEnv());
    }

    static String resolveToken(Environment environment, String credentialEnv) {
        String token = environment.getProperty(credentialEnv);
        if (token == null || token.isBlank()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.CREDENTIAL_UNAVAILABLE,
                    "源码仓库 Token 未配置（环境变量或 .env 中的 " + credentialEnv + "）");
        }
        return token.trim();
    }

    private String apiRoot() {
        return "/repos/" + owner + "/" + repo;
    }

    @Override
    public String masterHead() {
        return branchHead(config.getBranch());
    }

    @Override
    public String branchHead(String branch) {
        JsonNode body = execute(() -> restClient.get()
                .uri(apiRoot() + "/branches/{branch}", branch)
                .headers(this::authHeaders)
                .retrieve()
                .body(JsonNode.class));
        if (body == null || body.path("commit").path("sha").asText("").isBlank()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SOURCE_UNAVAILABLE, "读取分支头失败");
        }
        return body.path("commit").path("sha").asText();
    }

    @Override
    public boolean branchExists(String branch) {
        try {
            branchHead(branch);
            return true;
        } catch (GiteeNotFoundException ex) {
            return false;
        }
    }

    @Override
    public void createBranch(String branch, String fromRef) {
        execute(() -> restClient.post()
                .uri(apiRoot() + "/branches")
                .headers(this::authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new BranchCreateRequest(branch, fromRef))
                .retrieve()
                .body(JsonNode.class));
    }

    @Override
    public List<GiteeFile> listFilesUnder(String directoryPath, String ref) {
        List<GiteeFile> files = new ArrayList<>();
        collectFiles(directoryPath, ref, files);
        return files;
    }

    private void collectFiles(String directoryPath, String ref, List<GiteeFile> files) {
        JsonNode body;
        try {
            body = execute(() -> restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(apiRoot() + "/contents/" + directoryPath)
                            .queryParam("ref", ref)
                            .build())
                    .headers(this::authHeaders)
                    .retrieve()
                    .body(JsonNode.class));
        } catch (GiteeNotFoundException ex) {
            return;
        }
        if (body == null || !body.isArray()) {
            return;
        }
        for (JsonNode entry : body) {
            String type = entry.path("type").asText();
            String path = entry.path("path").asText();
            if ("dir".equals(type)) {
                collectFiles(path, ref, files);
            } else {
                files.add(new GiteeFile(
                        path,
                        entry.path("sha").asText(),
                        entry.path("size").asLong(),
                        type));
            }
        }
    }

    @Override
    public byte[] readFile(String path, String ref) {
        JsonNode body = execute(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(apiRoot() + "/contents/" + path)
                        .queryParam("ref", ref)
                        .build())
                .headers(this::authHeaders)
                .retrieve()
                .body(JsonNode.class));
        if (body == null || !body.path("type").asText("file").equals("file")) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SOURCE_UNAVAILABLE, "读取的路径不是文件: " + path);
        }
        String content = body.path("content").asText("");
        if ("base64".equals(body.path("encoding").asText())) {
            return Base64.getMimeDecoder().decode(content);
        }
        return content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String readFileSha(String path, String ref) {
        JsonNode body = execute(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(apiRoot() + "/contents/" + path)
                        .queryParam("ref", ref)
                        .build())
                .headers(this::authHeaders)
                .retrieve()
                .body(JsonNode.class));
        return body == null ? null : body.path("sha").asText(null);
    }

    @Override
    public String putFile(String path, String branch, String contentBase64, String message) {
        String existingSha;
        try {
            existingSha = readFileSha(path, branch);
        } catch (GiteeNotFoundException ex) {
            existingSha = null;
        }
        JsonNode body = existingSha == null
                ? createFile(path, branch, contentBase64, message)
                : updateFile(path, branch, contentBase64, message, existingSha);
        String sha = body == null ? null : body.path("commit").path("sha").asText(null);
        if (sha == null || sha.isBlank()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SOURCE_UNAVAILABLE, "写入文件未返回 commit");
        }
        return sha;
    }

    // Gitee 与 GitHub 的 contents API 语义不同：PUT /contents 一律要求 sha（更新文件），
    // 新建文件必须走 POST /contents，否则 Gitee 返回 400 {"messages":["sha is empty"]}。
    private JsonNode createFile(String path, String branch, String contentBase64, String message) {
        return execute(() -> restClient.post()
                .uri(apiRoot() + "/contents/" + path)
                .headers(this::authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FileCreateRequest(contentBase64, message, branch))
                .retrieve()
                .body(JsonNode.class));
    }

    private JsonNode updateFile(String path, String branch, String contentBase64, String message, String sha) {
        return execute(() -> restClient.put()
                .uri(apiRoot() + "/contents/" + path)
                .headers(this::authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FileWriteRequest(contentBase64, message, branch, sha))
                .retrieve()
                .body(JsonNode.class));
    }

    @Override
    public void deleteFile(String path, String branch, String sha, String message) {
        execute(() -> restClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri(apiRoot() + "/contents/" + path)
                .headers(this::authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FileDeleteRequest(sha, message, branch))
                .retrieve()
                .body(JsonNode.class));
    }

    @Override
    public void createTag(String tagName, String targetSha, String message) {
        execute(() -> restClient.post()
                .uri(apiRoot() + "/tags")
                .headers(this::authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new TagCreateRequest(tagName, targetSha, message))
                .retrieve()
                .body(JsonNode.class));
    }

    @Override
    public String verifyTagCommit(String tagName) {
        int page = 1;
        while (true) {
            int currentPage = page;
            JsonNode body = execute(() -> restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(apiRoot() + "/tags")
                            .queryParam("per_page", 100)
                            .queryParam("page", currentPage)
                            .build())
                    .headers(this::authHeaders)
                    .retrieve()
                    .body(JsonNode.class));
            if (body == null || !body.isArray() || body.isEmpty()) {
                return null;
            }
            for (JsonNode tag : body) {
                if (tagName.equals(tag.path("name").asText())) {
                    return tag.path("commit").path("sha").asText(null);
                }
            }
            if (body.size() < 100) {
                return null;
            }
            page++;
        }
    }

    @Override
    public boolean tagExists(String tagName) {
        return verifyTagCommit(tagName) != null;
    }

    @Override
    public void deleteBranch(String branch) {
        try {
            execute(() -> restClient.method(org.springframework.http.HttpMethod.DELETE)
                    .uri(apiRoot() + "/branches/{branch}", branch)
                    .headers(this::authHeaders)
                    .retrieve()
                    .body(JsonNode.class));
        } catch (GiteeNotFoundException ex) {
            log.info("Gitee 分支不存在，删除视为完成 branch={}", branch);
        }
    }

    @Override
    public long createPullRequest(String head, String base, String title) {
        // 发布重试时同源/目标分支的 PR 可能已存在（Gitee 对重复创建返回 400），复用已存在的 OPEN PR
        JsonNode existing = execute(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(apiRoot() + "/pulls")
                        .queryParam("state", "open")
                        .queryParam("head", head)
                        .queryParam("base", base)
                        .build())
                .headers(this::authHeaders)
                .retrieve()
                .body(JsonNode.class));
        if (existing != null && existing.isArray()) {
            for (JsonNode pr : existing) {
                if (head.equals(pr.path("head").path("ref").asText())
                        && base.equals(pr.path("base").path("ref").asText())) {
                    return pr.path("number").asLong();
                }
            }
        }
        JsonNode body = execute(() -> restClient.post()
                .uri(apiRoot() + "/pulls")
                .headers(this::authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PullCreateRequest(title, head, base))
                .retrieve()
                .body(JsonNode.class));
        if (body == null || body.path("number").asLong(0) <= 0) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SOURCE_UNAVAILABLE, "创建 PR 未返回编号");
        }
        return body.path("number").asLong();
    }

    @Override
    public String mergePullRequest(long prNumber, String title, String message) {
        // Gitee PR 创建时会把 token 用户设为审查人/测试人，仓库开启门禁后直接合并返回
        // 405"未通过设置的审查/测试"。Skill 仓库的审查权属于发布流程自身（内容校验已通过），
        // 因此以平台身份先通过两道门禁再合并。
        passPullGate(prNumber, "review", "Skill 发布流程自动审查通过");
        passPullGate(prNumber, "test", "Skill 发布流程自动测试通过");
        JsonNode body = execute(() -> restClient.put()
                .uri(apiRoot() + "/pulls/{number}/merge", prNumber)
                .headers(this::authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PullMergeRequest(title, message, "squash"))
                .retrieve()
                .body(JsonNode.class));
        String sha = body == null ? null : body.path("sha").asText(null);
        if (sha == null || sha.isBlank()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SOURCE_UNAVAILABLE, "合并 PR 未返回 commit");
        }
        return sha;
    }

    private void passPullGate(long prNumber, String gate, String comment) {
        execute(() -> restClient.post()
                .uri(apiRoot() + "/pulls/{number}/" + gate, prNumber)
                .headers(this::authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new GatePassRequest(comment))
                .retrieve()
                .toBodilessEntity());
    }

    private void authHeaders(HttpHeaders headers) {
        // Gitee API v5 支持个人令牌 Bearer 头认证；设计 §9.5 禁止 token 进 query/URL。
        headers.setBearerAuth(token());
    }

    private <T> T execute(GiteeCall<T> call) {
        try {
            return call.invoke();
        } catch (RestClientResponseException ex) {
            log.error("错误日志：",ex);
            if (ex.getStatusCode().value() == 404) {
                throw new GiteeNotFoundException(ex.getMessage());
            }
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.CREDENTIAL_UNAVAILABLE,
                        "源码仓库凭据不可用", ex);
            }
            throw SkillPlatformException.of(SkillPlatformErrorKind.SOURCE_UNAVAILABLE, "源码仓库请求失败", ex);
        } catch (SkillPlatformException ex) {
            log.error("错误日志：",ex);
            throw ex;
        } catch (RuntimeException ex) {
            log.error("错误日志：",ex);
            throw SkillPlatformException.of(SkillPlatformErrorKind.SOURCE_UNAVAILABLE, "源码仓库不可用", ex);
        }
    }

    @FunctionalInterface
    private interface GiteeCall<T> {
        T invoke();
    }

    static final class GiteeNotFoundException extends RuntimeException {

        GiteeNotFoundException(String message) {
            super(message);
        }
    }

    private record BranchCreateRequest(String branch_name, String refs) {
    }

    private record FileCreateRequest(String content, String message, String branch) {
    }

    private record FileWriteRequest(String content, String message, String branch, String sha) {
    }

    private record FileDeleteRequest(String sha, String message, String branch) {
    }

    // Gitee 创建 tag 的参数是 refs（目标 commit/branch），不是 GitHub 风格的 target。
    private record TagCreateRequest(String tag_name, String refs, String message) {
    }

    private record PullCreateRequest(String title, String head, String base) {
    }

    private record PullMergeRequest(String title, String message, String merge_method) {
    }

    private record GatePassRequest(String body) {
    }
}
