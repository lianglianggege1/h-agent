package com.h.backend.captcha;

import com.h.backend.captcha.application.HumanVerification;
import com.h.backend.captcha.domain.CaptchaChallenge;
import com.h.backend.captcha.domain.CaptchaErrors;
import com.h.backend.captcha.domain.CaptchaException;
import com.h.backend.captcha.domain.CaptchaProof;
import com.h.backend.captcha.domain.CaptchaSolution;
import com.h.backend.captcha.domain.ClientContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设计 §13.4 Captcha HTTP contract 测试：官方 code/msg/data 协议、匿名可访问、
 * mismatch/expired 仍返回 HTTP 200、参数错误 400、限流 429、依赖不可用 503。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaptchaControllerTest {

    private static final String ISSUE_BODY = """
            {"purpose":"LOGIN","email":"user@example.com"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HumanVerification humanVerification;

    @Test
    void shouldIssueChallengeAnonymouslyWithOfficialProtocol() throws Exception {
        when(humanVerification.issueChallenge(any(), any(), any())).thenReturn(new CaptchaChallenge(
                "SLIDER_1", "SLIDER", "data:image/jpeg;base64,bg", "data:image/png;base64,tpl",
                600, 360, 110, 360));

        mockMvc.perform(post("/api/captcha/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ISSUE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("OK"))
                .andExpect(jsonPath("$.data.id").value("SLIDER_1"))
                .andExpect(jsonPath("$.data.type").value("SLIDER"))
                .andExpect(jsonPath("$.data.backgroundImage").value("data:image/jpeg;base64,bg"))
                .andExpect(jsonPath("$.data.templateImage").value("data:image/png;base64,tpl"))
                .andExpect(jsonPath("$.data.backgroundImageWidth").value(600))
                .andExpect(jsonPath("$.data.backgroundImageHeight").value(360))
                .andExpect(jsonPath("$.data.templateImageWidth").value(110))
                .andExpect(jsonPath("$.data.templateImageHeight").value(360))
                .andExpect(jsonPath("$.data.data").doesNotExist());
    }

    @Test
    void shouldRejectInvalidPurpose() throws Exception {
        mockMvc.perform(post("/api/captcha/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"RESET_PASSWORD\",\"email\":\"user@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.msg").value("参数错误"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void shouldRejectInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/captcha/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"LOGIN\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void shouldRejectEmptyBody() throws Exception {
        mockMvc.perform(post("/api/captcha/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void shouldReturnProofOnVerificationSuccess() throws Exception {
        when(humanVerification.solveChallenge(any(), any()))
                .thenReturn(new CaptchaProof("opaque-base64url-value", 90));

        mockMvc.perform(post("/api/captcha/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validVerificationBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("OK"))
                .andExpect(jsonPath("$.data.captchaProof").value("opaque-base64url-value"))
                .andExpect(jsonPath("$.data.expiresIn").value(90));
    }

    @Test
    void shouldReturnHttp200WithCode4001OnMismatch() throws Exception {
        when(humanVerification.solveChallenge(any(), any()))
                .thenThrow(new CaptchaException(CaptchaException.Kind.MISMATCH, CaptchaErrors.MSG_TAC_MISMATCH));

        mockMvc.perform(post("/api/captcha/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validVerificationBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.msg").value("验证失败，请重新尝试"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shouldReturnHttp200WithCode4000OnExpiredOrConsumed() throws Exception {
        when(humanVerification.solveChallenge(any(), any()))
                .thenThrow(new CaptchaException(CaptchaException.Kind.EXPIRED, CaptchaErrors.MSG_TAC_EXPIRED));

        mockMvc.perform(post("/api/captcha/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validVerificationBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.msg").value("验证已失效，请重新尝试"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void shouldRejectReversedTrackTimestamps() throws Exception {
        String body = """
                {"id":"SLIDER_1","purpose":"LOGIN","email":"user@example.com",
                 "data":{"bgImageWidth":300,"bgImageHeight":180,"templateImageWidth":55,"templateImageHeight":180,
                 "startTime":2000,"stopTime":1000,
                 "trackList":[{"x":120,"y":600,"t":0,"type":"down"},{"x":238,"y":601,"t":834,"type":"up"}]}}
                """;

        mockMvc.perform(post("/api/captcha/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.msg").value("参数错误"));
    }

    @Test
    void shouldRejectUnknownTrackPointType() throws Exception {
        String body = validVerificationBody().replace("\"down\"", "\"click\"");

        mockMvc.perform(post("/api/captcha/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void shouldRejectMoreThan512TrackPoints() throws Exception {
        String points = java.util.stream.IntStream.range(0, 513)
                .mapToObj(i -> "{\"x\":1,\"y\":1,\"t\":" + i + ",\"type\":\"move\"}")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String body = "{\"id\":\"SLIDER_1\",\"purpose\":\"LOGIN\",\"email\":\"user@example.com\","
                + "\"data\":{\"bgImageWidth\":300,\"bgImageHeight\":180,\"templateImageWidth\":55,"
                + "\"templateImageHeight\":180,\"startTime\":0,\"stopTime\":1000,\"trackList\":[" + points + "]}}";

        mockMvc.perform(post("/api/captcha/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void shouldRejectDurationOver30Seconds() throws Exception {
        String body = validVerificationBody().replace("\"startTime\":1788580000000", "\"startTime\":1788570000000");

        mockMvc.perform(post("/api/captcha/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void shouldReturn429WhenRateLimited() throws Exception {
        when(humanVerification.issueChallenge(any(), any(), any()))
                .thenThrow(new CaptchaException(CaptchaException.Kind.RATE_LIMITED, CaptchaErrors.MSG_RATE_LIMITED));

        mockMvc.perform(issueRequest())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901))
                .andExpect(jsonPath("$.msg").value("操作过于频繁，请稍后再试"));
    }

    @Test
    void shouldReturn503WhenDependencyUnavailable() throws Exception {
        when(humanVerification.issueChallenge(any(), any(), any()))
                .thenThrow(new CaptchaException(CaptchaException.Kind.UNAVAILABLE, CaptchaErrors.MSG_UNAVAILABLE));

        mockMvc.perform(issueRequest())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50301))
                .andExpect(jsonPath("$.msg").value("验证服务暂时不可用，请稍后重试"));
    }

    @Test
    void shouldPassPurposeEmailAndClientContextToApplicationLayer() throws Exception {
        when(humanVerification.issueChallenge(any(), any(), any())).thenReturn(new CaptchaChallenge(
                "SLIDER_1", "SLIDER", "bg", "tpl", 600, 360, 110, 360));

        mockMvc.perform(issueRequest()).andExpect(status().isOk());

        Mockito.verify(humanVerification).issueChallenge(
                eq(com.h.backend.captcha.domain.CaptchaPurpose.LOGIN),
                eq("user@example.com"),
                any(ClientContext.class));
    }

    @Test
    void shouldPassSolutionToApplicationLayerOnVerify() throws Exception {
        when(humanVerification.solveChallenge(any(), any()))
                .thenReturn(new CaptchaProof("opaque", 90));

        mockMvc.perform(post("/api/captcha/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validVerificationBody()))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<CaptchaSolution> captor =
                org.mockito.ArgumentCaptor.forClass(CaptchaSolution.class);
        Mockito.verify(humanVerification).solveChallenge(captor.capture(), any(ClientContext.class));
        CaptchaSolution solution = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("SLIDER_1", solution.challengeId());
        org.junit.jupiter.api.Assertions.assertEquals(com.h.backend.captcha.domain.CaptchaPurpose.LOGIN,
                solution.purpose());
        org.junit.jupiter.api.Assertions.assertEquals(2, solution.track().trackList().size());
    }

    private static MockHttpServletRequestBuilder issueRequest() {
        return post("/api/captcha/challenges")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ISSUE_BODY);
    }

    private static String validVerificationBody() {
        return """
                {"id":"SLIDER_1","purpose":"LOGIN","email":"user@example.com",
                 "data":{"bgImageWidth":300,"bgImageHeight":180,"templateImageWidth":55,"templateImageHeight":180,
                 "startTime":1788580000000,"stopTime":1788580000834,
                 "trackList":[{"x":120,"y":600,"t":0,"type":"down"},{"x":238,"y":601,"t":834,"type":"up"}]}}
                """;
    }
}
