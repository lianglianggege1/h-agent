package com.h.backend.captcha;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.common.AnyMap;
import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import cloud.tianai.captcha.validator.impl.BasicCaptchaTrackValidator;
import cloud.tianai.captcha.validator.impl.SimpleImageCaptchaValidator;
import com.h.backend.captcha.application.CaptchaEngine;
import com.h.backend.captcha.domain.CaptchaTrack;
import com.h.backend.captcha.infrastructure.tianai.TianaiCaptchaAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TianaiCaptchaAdapterTest {

    @Test
    void matchingShouldAcceptOfficialSdkAbsoluteCoordinatesWhenDisplacementMatches() {
        ImageCaptchaApplication application = mock(ImageCaptchaApplication.class);
        when(application.matching(anyString(), any(ImageCaptchaTrack.class))).thenAnswer(invocation -> {
            ImageCaptchaTrack submittedTrack = invocation.getArgument(1);
            AnyMap correctAnswer = AnyMap.of(new HashMap<>());
            correctAnswer.put(SimpleImageCaptchaValidator.PERCENTAGE_KEY, 0.31666667f);
            correctAnswer.put(SimpleImageCaptchaValidator.TYPE_KEY, CaptchaTypeConstant.SLIDER);
            return new BasicCaptchaTrackValidator().valid(submittedTrack, correctAnswer);
        });

        CaptchaEngine engine = new TianaiCaptchaAdapter(application);

        assertTrue(engine.matching("SLIDER_captured", officialSdkTrack()));
    }

    /**
     * tianai-captcha Web SDK 1.5.5 记录 pageX/pageY。该轨迹来自浏览器请求的最小等价样本：
     * 首点 (432, 572)，末点 (527, 571)，相对水平位移 95px，背景宽度 300px。
     */
    private static CaptchaTrack officialSdkTrack() {
        List<CaptchaTrack.TrackPoint> points = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            float progress = i / 11f;
            String type = i == 0 ? "down" : (i == 11 ? "up" : "move");
            points.add(new CaptchaTrack.TrackPoint(
                    432f + 95f * progress,
                    572f - progress,
                    2117f * progress,
                    type));
        }
        return new CaptchaTrack(300, 180, 55, 180,
                1_788_597_719_019L, 1_788_597_721_136L, points);
    }
}
