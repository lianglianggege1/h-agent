package com.h.backend.auth;

import com.h.backend.captcha.application.CaptchaEngine;
import com.h.backend.captcha.application.CaptchaRateLimiter;
import com.h.backend.captcha.application.HumanVerification;
import com.h.backend.captcha.domain.CaptchaChallenge;
import com.h.backend.captcha.domain.CaptchaErrors;
import com.h.backend.captcha.domain.CaptchaException;
import com.h.backend.captcha.domain.CaptchaPurpose;
import com.h.backend.captcha.domain.CaptchaSolution;
import com.h.backend.captcha.domain.CaptchaTrack;
import com.h.backend.captcha.domain.ClientContext;
import com.h.backend.auth.interfaces.dto.LoginRequest;
import com.h.backend.auth.interfaces.dto.RegisterRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设计 §13.5 auth 模块与 Controller 集成测试：proof 前置门禁、一次性消费、
 * 用途/邮箱绑定、错误码语义（40003/40002/40101）与 429/503 映射。
 * 引擎与限流均为 mock（一次性不变量已由 HumanVerificationRedisIntegrationTest 在真实 Redis 下验证）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthCaptchaIntegrationTest {

    private static final String PASSWORD = "Password123";
    private static final ClientContext CLIENT = ClientContext.of("127.0.0.1");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HumanVerification humanVerification;

    @MockitoBean
    private CaptchaEngine captchaEngine;

    @MockitoBean
    private CaptchaRateLimiter captchaRateLimiter;

    @BeforeEach
    void stubEngine() {
        when(captchaEngine.generateSlider()).thenAnswer(inv -> new CaptchaChallenge(
                "SLIDER_FAKE_" + System.nanoTime(), "SLIDER", "data:image/jpeg;base64,bg",
                "data:image/png;base64,tpl", 600, 360, 110, 360));
        when(captchaEngine.matching(any(), any())).thenReturn(true);
    }

    @Test
    void registerWithoutProofShouldFailAndNotCreateUser() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, PASSWORD, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.message").value(CaptchaErrors.MSG_PROOF_INVALID));

        // 用户未被创建：补 proof 后注册仍成功
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, PASSWORD, proofFor(CaptchaPurpose.REGISTER, email)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void loginWithoutProofShouldFailWith40003() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.message").value(CaptchaErrors.MSG_PROOF_INVALID));
    }

    @Test
    void registerWithValidProofShouldCreateUserWithDefaultRole() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, PASSWORD, proofFor(CaptchaPurpose.REGISTER, email)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void loginWithValidProofShouldSucceedAndSetCookie() throws Exception {
        String email = uniqueEmail();
        register(email);

        Cookie cookie = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, PASSWORD, proofFor(CaptchaPurpose.LOGIN, email)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn().getResponse().getCookie("h_agent_access_token");

        org.junit.jupiter.api.Assertions.assertNotNull(cookie);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.email").value(email));
    }

    @Test
    void crossPurposeProofShouldFail() throws Exception {
        String email = uniqueEmail();

        String loginProof = proofFor(CaptchaPurpose.LOGIN, email);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, PASSWORD, loginProof))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40003));

        register(email);
        String registerProof = proofFor(CaptchaPurpose.REGISTER, email);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, PASSWORD, registerProof))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40003));
    }

    @Test
    void proofBoundToDifferentEmailShouldFail() throws Exception {
        String email = uniqueEmail();
        register(email);
        String proof = proofFor(CaptchaPurpose.LOGIN, "other_" + email);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD, proof))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40003));
    }

    @Test
    void tamperedAndExpiredProofShouldFailUniformly() throws Exception {
        String email = uniqueEmail();
        register(email);

        for (String proof : new String[]{"tampered", "   ", ""}) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD, proof))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40003))
                    .andExpect(jsonPath("$.message").value(CaptchaErrors.MSG_PROOF_INVALID));
        }
    }

    @Test
    void proofReplayShouldFailOnSecondUse() throws Exception {
        String email = uniqueEmail();
        register(email);
        String proof = proofFor(CaptchaPurpose.LOGIN, email);
        String body = objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD, proof));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40003));
    }

    @Test
    void wrongPasswordShouldConsumeProof() throws Exception {
        String email = uniqueEmail();
        register(email);
        String proof = proofFor(CaptchaPurpose.LOGIN, email);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "WrongPassword123", proof))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD, proof))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40003));
    }

    @Test
    void duplicateEmailShouldConsumeProof() throws Exception {
        String email = uniqueEmail();
        register(email);
        String proof = proofFor(CaptchaPurpose.REGISTER, email);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, PASSWORD, proof))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, PASSWORD, proof))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40003));
    }

    @Test
    void rateLimitedAuthShouldReturn429WithProjectProtocol() throws Exception {
        doThrow(new CaptchaException(CaptchaException.Kind.RATE_LIMITED, CaptchaErrors.MSG_RATE_LIMITED))
                .when(captchaRateLimiter).checkAuthIpAllowed(any(), any());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(uniqueEmail(), PASSWORD, null))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901))
                .andExpect(jsonPath("$.message").value(CaptchaErrors.MSG_RATE_LIMITED));

        Mockito.verify(captchaEngine, Mockito.never()).generateSlider();
    }

    @Test
    void unavailableProofStoreShouldReturn503WithProjectProtocol() throws Exception {
        doThrow(new CaptchaException(CaptchaException.Kind.UNAVAILABLE, CaptchaErrors.MSG_UNAVAILABLE))
                .when(captchaRateLimiter).checkAuthAllowed(any(), any());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(uniqueEmail(), PASSWORD, "x"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50301))
                .andExpect(jsonPath("$.message").value(CaptchaErrors.MSG_UNAVAILABLE));
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, PASSWORD, proofFor(CaptchaPurpose.REGISTER, email)))))
                .andExpect(status().isOk());
    }

    private String proofFor(CaptchaPurpose purpose, String email) {
        CaptchaChallenge challenge = humanVerification.issueChallenge(purpose, email, CLIENT);
        return humanVerification.solveChallenge(new CaptchaSolution(
                challenge.id(), purpose, email, track()), CLIENT).rawProof();
    }

    private static CaptchaTrack track() {
        return new CaptchaTrack(300, 180, 55, 180, 1000L, 1834L,
                List.of(new CaptchaTrack.TrackPoint(10f, 10f, 0f, "down"),
                        new CaptchaTrack.TrackPoint(120f, 11f, 834f, "up")));
    }

    private static String uniqueEmail() {
        return "captcha_" + System.nanoTime() + "@example.com";
    }
}
