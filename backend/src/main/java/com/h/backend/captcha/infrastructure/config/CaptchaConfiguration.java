package com.h.backend.captcha.infrastructure.config;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.cache.impl.LocalCacheStore;
import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.generator.impl.StandardSliderImageCaptchaGenerator;
import cloud.tianai.captcha.resource.ResourceStore;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import cloud.tianai.captcha.resource.common.model.dto.ResourceMap;
import cloud.tianai.captcha.resource.impl.LocalMemoryResourceStore;
import cloud.tianai.captcha.validator.ImageCaptchaValidator;
import cloud.tianai.captcha.validator.impl.BasicCaptchaTrackValidator;
import com.h.backend.captcha.application.CaptchaEngine;
import com.h.backend.captcha.application.CaptchaRateLimiter;
import com.h.backend.captcha.application.CaptchaStateStore;
import com.h.backend.captcha.application.HumanVerification;
import com.h.backend.captcha.application.impl.HumanVerificationImpl;
import com.h.backend.captcha.domain.SubjectFingerprintCalculator;
import com.h.backend.captcha.infrastructure.redis.RedisCaptchaRateLimiter;
import com.h.backend.captcha.infrastructure.redis.RedisCaptchaStateStore;
import com.h.backend.captcha.infrastructure.tianai.TianaiCaptchaAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 验证码模块装配：
 * - 资源 store 固定为进程内只读 classpath 配置（阻止上游自动装配 RedisResourceStore）；
 * - validator 显式使用 BasicCaptchaTrackValidator（基础轨迹拦截）；
 * - 状态存储与限流只装配 Redis 实现，启动断言上游 CacheStore 为 Redis 实现。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(HumanVerificationProperties.class)
public class CaptchaConfiguration {

    /** Resource(type=classpath) 的 data 是纯 classpath 路径，不带 "classpath:" 前缀（上游 ClassPathResourceProvider 直接按 data 查找）。 */
    private static final List<String> SLIDER_BACKGROUNDS = List.of(
            "captcha/background/1.jpg",
            "captcha/background/bg_2.jpg",
            "captcha/background/bg_3.jpg",
            "captcha/background/bg_4.jpg",
            "captcha/background/bg_5.jpg",
            "captcha/background/bg_6.jpg");

    private static final List<String> SLIDER_TEMPLATES = List.of(
            "captcha/template/slider_1",
            "captcha/template/slider_2");

    @Bean
    public ResourceStore captchaResourceStore() {
        LocalMemoryResourceStore store = new LocalMemoryResourceStore();
        for (String background : SLIDER_BACKGROUNDS) {
            store.addResource(CaptchaTypeConstant.SLIDER, new Resource("classpath", background));
        }
        for (String template : SLIDER_TEMPLATES) {
            ResourceMap templateMap = new ResourceMap(null, 4);
            templateMap.put(StandardSliderImageCaptchaGenerator.TEMPLATE_ACTIVE_IMAGE_NAME,
                    new Resource("classpath", template + "/active.png"));
            templateMap.put(StandardSliderImageCaptchaGenerator.TEMPLATE_FIXED_IMAGE_NAME,
                    new Resource("classpath", template + "/fixed.png"));
            store.addTemplate(CaptchaTypeConstant.SLIDER, templateMap);
        }
        return store;
    }

    @Bean
    public ImageCaptchaValidator imageCaptchaValidator() {
        return new BasicCaptchaTrackValidator();
    }

    @Bean
    public CaptchaEngine captchaEngine(ImageCaptchaApplication imageCaptchaApplication) {
        return new TianaiCaptchaAdapter(imageCaptchaApplication);
    }

    @Bean
    public CaptchaStateStore captchaStateStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper,
                                                HumanVerificationProperties properties) {
        return new RedisCaptchaStateStore(stringRedisTemplate, objectMapper, properties.getKeyPrefix());
    }

    @Bean
    public CaptchaRateLimiter captchaRateLimiter(StringRedisTemplate stringRedisTemplate,
                                                  HumanVerificationProperties properties,
                                                  MeterRegistry meterRegistry) {
        return new RedisCaptchaRateLimiter(stringRedisTemplate, properties, meterRegistry);
    }

    @Bean
    public SubjectFingerprintCalculator subjectFingerprintCalculator(HumanVerificationProperties properties) {
        // secret 为空时构造抛异常，应用启动失败
        return new SubjectFingerprintCalculator(properties.getSubjectHmacSecret());
    }

    @Bean
    public HumanVerification humanVerification(CaptchaEngine captchaEngine,
                                               CaptchaStateStore captchaStateStore,
                                               CaptchaRateLimiter captchaRateLimiter,
                                               SubjectFingerprintCalculator fingerprintCalculator,
                                               HumanVerificationProperties properties,
                                               MeterRegistry meterRegistry) {
        return new HumanVerificationImpl(captchaEngine, captchaStateStore, captchaRateLimiter, fingerprintCalculator,
                properties.getChallengeMetadataTtl(), properties.getProofTtl(), meterRegistry);
    }

    /**
     * 生产断言：上游 CacheStore 必须是 Redis 实现，禁止回退进程内 LocalCacheStore。
     */
    @Bean
    public SmartInitializingSingleton captchaCacheStoreGuard(ImageCaptchaApplication imageCaptchaApplication) {
        return () -> {
            if (imageCaptchaApplication.getCacheStore() instanceof LocalCacheStore) {
                throw new IllegalStateException(
                        "captcha CacheStore 装配为 LocalCacheStore，多实例部署下 challenge 无法跨实例消费；"
                                + "请检查 Redis 连接配置（spring.data.redis.*）");
            }
            log.info("[Captcha] 初始化完成 CacheStore={} validator={} resources={} templates={}",
                    imageCaptchaApplication.getCacheStore().getClass().getSimpleName(),
                    BasicCaptchaTrackValidator.class.getSimpleName(),
                    SLIDER_BACKGROUNDS.size(), SLIDER_TEMPLATES.size());
        };
    }
}
