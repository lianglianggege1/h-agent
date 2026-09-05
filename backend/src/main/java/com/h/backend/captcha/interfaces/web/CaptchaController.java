package com.h.backend.captcha.interfaces.web;

import com.h.backend.captcha.application.HumanVerification;
import com.h.backend.captcha.domain.CaptchaChallenge;
import com.h.backend.captcha.domain.CaptchaErrors;
import com.h.backend.captcha.domain.CaptchaException;
import com.h.backend.captcha.domain.CaptchaProof;
import com.h.backend.captcha.domain.CaptchaPurpose;
import com.h.backend.captcha.domain.ClientContext;
import com.h.backend.captcha.interfaces.dto.CaptchaRequests;
import com.h.backend.captcha.interfaces.dto.CaptchaResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 官方 Web SDK 直接调用的验证码端点，使用上游 code/msg/data 协议。
 * 项目协议（code/message/data）的翻译不进入本类；本类抛出的 CaptchaException
 * 由 AuthServiceImpl 翻译为项目 BusinessException。
 */
@Slf4j
@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    private static final long MAX_BODY_BYTES = 64 * 1024L;

    private final HumanVerification humanVerification;

    @PostMapping("/challenges")
    public ResponseEntity<CaptchaResponses.TacApiResponse<CaptchaResponses.ChallengeData>> issue(
            HttpServletRequest request,
            @Valid @RequestBody CaptchaRequests.IssueChallengeRequest body) {
        checkBodySize(request);
        CaptchaPurpose purpose = CaptchaPurpose.parse(body.purpose());
        CaptchaChallenge challenge =
                humanVerification.issueChallenge(purpose, body.email(), ClientContext.of(request.getRemoteAddr()));
        CaptchaResponses.ChallengeData data = new CaptchaResponses.ChallengeData(
                challenge.id(), challenge.type(), challenge.backgroundImage(), challenge.templateImage(),
                challenge.backgroundImageWidth(), challenge.backgroundImageHeight(),
                challenge.templateImageWidth(), challenge.templateImageHeight(), null);
        return ResponseEntity.ok(CaptchaResponses.TacApiResponse.ok(data));
    }

    @PostMapping("/verifications")
    public ResponseEntity<CaptchaResponses.TacApiResponse<CaptchaResponses.ProofData>> verify(
            HttpServletRequest request,
            @Valid @RequestBody CaptchaRequests.VerificationRequest body) {
        checkBodySize(request);
        body.data().validate();
        CaptchaProof proof = humanVerification.solveChallenge(body.toSolution(),
                ClientContext.of(request.getRemoteAddr()));
        return ResponseEntity.ok(CaptchaResponses.TacApiResponse.ok(
                new CaptchaResponses.ProofData(proof.rawProof(), proof.expiresInSeconds())));
    }

    private static void checkBodySize(HttpServletRequest request) {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            throw new CaptchaException(CaptchaException.Kind.PARAM_INVALID, CaptchaErrors.MSG_PARAM_INVALID);
        }
    }

    /** 仅作用于本 Controller 的协议翻译。 */
    @ExceptionHandler(CaptchaException.class)
    public ResponseEntity<CaptchaResponses.TacApiResponse<Void>> handleCaptchaException(CaptchaException ex) {
        return switch (ex.getKind()) {
            // 官方 SDK 期待 200 + code，用于展示失败并自动重新加载
            case MISMATCH -> ResponseEntity.ok(
                    CaptchaResponses.TacApiResponse.error(CaptchaErrors.TAC_CODE_MISMATCH,
                            CaptchaErrors.MSG_TAC_MISMATCH));
            case EXPIRED -> ResponseEntity.ok(
                    CaptchaResponses.TacApiResponse.error(CaptchaErrors.TAC_CODE_EXPIRED,
                            CaptchaErrors.MSG_TAC_EXPIRED));
            case PARAM_INVALID, PROOF_INVALID -> ResponseEntity.badRequest().body(
                    CaptchaResponses.TacApiResponse.error(CaptchaErrors.PARAM_INVALID,
                            CaptchaErrors.MSG_PARAM_INVALID));
            case RATE_LIMITED -> ResponseEntity.status(429).body(
                    CaptchaResponses.TacApiResponse.error(CaptchaErrors.RATE_LIMITED,
                            CaptchaErrors.MSG_RATE_LIMITED));
            case UNAVAILABLE -> ResponseEntity.status(503).body(
                    CaptchaResponses.TacApiResponse.error(CaptchaErrors.UNAVAILABLE,
                            CaptchaErrors.MSG_UNAVAILABLE));
        };
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<CaptchaResponses.TacApiResponse<Void>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().body(
                CaptchaResponses.TacApiResponse.error(CaptchaErrors.PARAM_INVALID, CaptchaErrors.MSG_PARAM_INVALID));
    }
}
