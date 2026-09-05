package com.h.backend.captcha.interfaces.dto;

import com.h.backend.captcha.domain.CaptchaException;
import com.h.backend.captcha.domain.CaptchaPurpose;
import com.h.backend.captcha.domain.CaptchaSolution;
import com.h.backend.captcha.domain.CaptchaTrack;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 官方 Web SDK 协议的请求 DTO。字段级校验用 Bean Validation（与认证 DTO 同一规则），
 * 轨迹深度校验在 {@link TrackData#validate()} 手动执行。
 */
public final class CaptchaRequests {

    private CaptchaRequests() {
    }

    public record IssueChallengeRequest(
            @NotBlank(message = "purpose 不能为空")
            @Pattern(regexp = "LOGIN|REGISTER", message = "purpose 只允许 LOGIN 或 REGISTER")
            String purpose,

            @NotBlank(message = "email 不能为空")
            @Email
            @Size(max = 254)
            String email) {
    }

    public record VerificationRequest(
            @NotBlank(message = "id 不能为空")
            @Size(max = 128)
            String id,

            @NotBlank(message = "purpose 不能为空")
            @Pattern(regexp = "LOGIN|REGISTER", message = "purpose 只允许 LOGIN 或 REGISTER")
            String purpose,

            @NotBlank(message = "email 不能为空")
            @Email
            @Size(max = 254)
            String email,

            @NotNull(message = "data 不能为空")
            @Valid
            TrackData data) {

        public CaptchaSolution toSolution() {
            return new CaptchaSolution(id, CaptchaPurpose.parse(purpose), email, data.toTrack());
        }
    }

    public record TrackData(
            @NotNull Integer bgImageWidth,
            @NotNull Integer bgImageHeight,
            @NotNull Integer templateImageWidth,
            @NotNull Integer templateImageHeight,
            @NotNull Long startTime,
            @NotNull Long stopTime,
            @NotNull @Size(min = 2, max = 512) List<@Valid TrackPoint> trackList) {

        private static final int DIMENSION_MIN = 1;
        private static final int DIMENSION_MAX = 2000;
        private static final long MAX_DURATION_MILLIS = 30_000L;
        private static final Set<String> ALLOWED_POINT_TYPES = Set.of("down", "move", "up");

        public CaptchaTrack toTrack() {
            List<CaptchaTrack.TrackPoint> points = new ArrayList<>(trackList.size());
            for (TrackPoint point : trackList) {
                points.add(new CaptchaTrack.TrackPoint(point.x(), point.y(), point.t(), point.type()));
            }
            return new CaptchaTrack(bgImageWidth, bgImageHeight, templateImageWidth, templateImageHeight,
                    startTime, stopTime, points);
        }

        /** 设计 §7.3 的轨迹约束：尺寸、时长、有限数值、顺序、type allowlist。 */
        public void validate() {
            checkDimension("bgImageWidth", bgImageWidth);
            checkDimension("bgImageHeight", bgImageHeight);
            checkDimension("templateImageWidth", templateImageWidth);
            checkDimension("templateImageHeight", templateImageHeight);

            if (startTime > stopTime) {
                throw paramInvalid("startTime 不能晚于 stopTime");
            }
            if (stopTime - startTime > MAX_DURATION_MILLIS) {
                throw paramInvalid("滑动时长不能超过 30 秒");
            }

            float previousT = -1f;
            for (TrackPoint point : trackList) {
                if (point.x() == null || point.y() == null || point.t() == null || point.type() == null) {
                    throw paramInvalid("trackList 点列缺少 x/y/t/type");
                }
                if (!Float.isFinite(point.x()) || !Float.isFinite(point.y()) || !Float.isFinite(point.t())) {
                    throw paramInvalid("trackList 点列数值必须是有限数值");
                }
                if (point.t() < 0) {
                    throw paramInvalid("trackList 的 t 必须非负");
                }
                if (point.t() < previousT) {
                    throw paramInvalid("trackList 的 t 必须按顺序不递减");
                }
                previousT = point.t();
                if (!ALLOWED_POINT_TYPES.contains(point.type())) {
                    throw paramInvalid("trackList 的 type 只允许 down/move/up");
                }
            }
        }

        private static void checkDimension(String name, Integer value) {
            if (value == null || value < DIMENSION_MIN || value > DIMENSION_MAX) {
                throw paramInvalid(name + " 必须是 1~2000 的整数");
            }
        }

        private static CaptchaException paramInvalid(String message) {
            return new CaptchaException(CaptchaException.Kind.PARAM_INVALID, message);
        }
    }

    public record TrackPoint(Float x, Float y, Float t, String type) {
    }
}
