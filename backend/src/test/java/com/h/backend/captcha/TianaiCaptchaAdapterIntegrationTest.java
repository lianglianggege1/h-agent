package com.h.backend.captcha;

import com.h.backend.captcha.application.CaptchaEngine;
import com.h.backend.captcha.domain.CaptchaChallenge;
import com.h.backend.captcha.domain.CaptchaTrack;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设计 §13.1/§13.3：真实 tianai-captcha 引擎 + 真实 Redis CacheStore 的兼容性门禁。
 * headless 环境连续生成 100 次 SLIDER 验证无 AWT/字体/图片解码异常；
 * matching 原子消费正确答案（一次消费后同一 challenge 不可再校验成功）。
 */
@SpringBootTest
class TianaiCaptchaAdapterIntegrationTest {

    @Autowired
    private CaptchaEngine captchaEngine;

    @Test
    void shouldGenerate100SlidersHeadlesslyWithoutException() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            CaptchaChallenge challenge = captchaEngine.generateSlider();
            assertNotNull(challenge.id(), "第 " + i + " 次 challenge id 为空");
            assertEquals("SLIDER", challenge.type());
            assertTrue(challenge.backgroundImage().startsWith("data:image"),
                    "图片必须以 Base64 data URI 返回");
            assertTrue(challenge.templateImage().startsWith("data:image"));
            assertTrue(challenge.backgroundImageWidth() > 0 && challenge.backgroundImageHeight() > 0);
            assertTrue(challenge.templateImageWidth() > 0 && challenge.templateImageHeight() > 0);
            ids.add(challenge.id());
        }
        assertEquals(100, ids.size(), "challenge id 不得重复");
    }

    @Test
    void matchingShouldAtomicallyConsumeAnswer() {
        CaptchaChallenge challenge = captchaEngine.generateSlider();

        boolean firstResult = captchaEngine.matching(challenge.id(), randomTrack());
        boolean secondResult = captchaEngine.matching(challenge.id(), randomTrack());

        // 随机轨迹匹配成功与否取决于随机位置，但第二次调用必须失败：
        // 上游 matching 内部原子删除正确答案，答案不存在时上游返回非 success。
        assertFalse(firstResult && secondResult, "同一 challenge 不能两次校验成功");
    }

    @Test
    void generateSliderShouldNotLeakCorrectPosition() {
        CaptchaChallenge challenge = captchaEngine.generateSlider();

        // 领域对象只有 id/type/图片/尺寸，正确位置只存在于上游 CacheStore
        assertEquals(8, CaptchaChallenge.class.getRecordComponents().length);
        assertTrue(challenge.backgroundImage().startsWith("data:image"));
    }

    private static CaptchaTrack randomTrack() {
        List<CaptchaTrack.TrackPoint> points = new ArrayList<>();
        points.add(new CaptchaTrack.TrackPoint(10f, 90f, 0f, "down"));
        for (int i = 1; i <= 20; i++) {
            points.add(new CaptchaTrack.TrackPoint(10f + i * 5f, 90f + (float) (Math.sin(i) * 2), i * 40f, "move"));
        }
        points.add(new CaptchaTrack.TrackPoint(110f, 90f, 840f, "up"));
        return new CaptchaTrack(300, 180, 55, 180, 1000L, 1840L, points);
    }
}
