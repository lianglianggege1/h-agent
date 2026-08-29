package com.h.backend.skill.infrastructure.gitee;

import com.h.backend.skill.domain.SkillPlatformException;
import com.h.backend.skill.infrastructure.config.SkillPlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GiteeRestSkillRepositoryTest {

    private static final String API_BASE = "http://gitee.test/api/v5";
    private static final String CONTENTS_URL = API_BASE + "/repos/owner/repo/contents/skills/demo/SKILL.md";

    @Test
    void resolvesCredentialFromSpringEnvironmentSoDotenvImportsAreSupported() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("GITEE_TOKEN", "  dotenv-token  ");

        assertThat(GiteeRestSkillRepository.resolveToken(environment, "GITEE_TOKEN"))
                .isEqualTo("dotenv-token");
    }

    @Test
    void reportsMissingCredentialWithoutLeakingValues() {
        MockEnvironment environment = new MockEnvironment();

        assertThatThrownBy(() -> GiteeRestSkillRepository.resolveToken(environment, "GITEE_TOKEN"))
                .isInstanceOf(SkillPlatformException.class)
                .hasMessageContaining("GITEE_TOKEN")
                .hasMessageContaining(".env");
    }

    @Test
    void putFileCreatesViaPostWithoutShaWhenFileAbsent() {
        Fixture fixture = newFixture();

        fixture.server.expect(requestTo(CONTENTS_URL + "?ref=branch-x"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{}").contentType(MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(CONTENTS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content").value("aGVsbG8="))
                .andExpect(jsonPath("$.message").value("init SKILL.md"))
                .andExpect(jsonPath("$.branch").value("branch-x"))
                .andExpect(jsonPath("$.sha").doesNotExist())
                .andRespond(withSuccess("{\"commit\":{\"sha\":\"abc123\"}}", MediaType.APPLICATION_JSON));

        assertThat(fixture.repository.putFile("skills/demo/SKILL.md", "branch-x", "aGVsbG8=", "init SKILL.md"))
                .isEqualTo("abc123");
        fixture.server.verify();
    }

    @Test
    void putFileUpdatesViaPutWithExistingShaWhenFilePresent() {
        Fixture fixture = newFixture();

        fixture.server.expect(requestTo(CONTENTS_URL + "?ref=branch-x"))
                .andRespond(withSuccess("{\"type\":\"file\",\"sha\":\"blob-sha-1\"}", MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(CONTENTS_URL))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.content").value("aGVsbG8="))
                .andExpect(jsonPath("$.message").value("init SKILL.md"))
                .andExpect(jsonPath("$.branch").value("branch-x"))
                .andExpect(jsonPath("$.sha").value("blob-sha-1"))
                .andRespond(withSuccess("{\"commit\":{\"sha\":\"def456\"}}", MediaType.APPLICATION_JSON));

        assertThat(fixture.repository.putFile("skills/demo/SKILL.md", "branch-x", "aGVsbG8=", "init SKILL.md"))
                .isEqualTo("def456");
        fixture.server.verify();
    }

    private record Fixture(GiteeRestSkillRepository repository, MockRestServiceServer server) {
    }

    private Fixture newFixture() {
        SkillPlatformProperties properties = new SkillPlatformProperties();
        properties.getRepository().setCloneUrl("https://gitee.com/owner/repo.git");
        properties.getRepository().setApiBaseUrl(API_BASE);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MockEnvironment environment = new MockEnvironment().withProperty("GITEE_TOKEN", "token");
        return new Fixture(new GiteeRestSkillRepository(properties, environment, builder), server);
    }
}
