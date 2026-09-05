package com.h.backend.captcha.infrastructure.tianai;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.common.response.ApiResponse;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import com.h.backend.captcha.application.CaptchaEngine;
import com.h.backend.captcha.domain.CaptchaChallenge;
import com.h.backend.captcha.domain.CaptchaErrors;
import com.h.backend.captcha.domain.CaptchaException;
import com.h.backend.captcha.domain.CaptchaTrack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * tianai-captcha Adapter：所有上游调用与结果翻译都封装在此。
 * 只允许 SLIDER，调用者无法注入资源、模板、类型或容差。
 */
@Slf4j
@RequiredArgsConstructor
public class TianaiCaptchaAdapter implements CaptchaEngine {

    private final ImageCaptchaApplication application;

    @Override
    public CaptchaChallenge generateSlider() {
        try {
            ApiResponse<ImageCaptchaVO> response = application.generateCaptcha(CaptchaTypeConstant.SLIDER);
            if (!response.isSuccess() || response.getData() == null) {
                log.error("[Captcha] 上游生成失败 code={} msg={}", response.getCode(), response.getMsg());
                throw new CaptchaException(CaptchaException.Kind.UNAVAILABLE, CaptchaErrors.MSG_UNAVAILABLE);
            }
            ImageCaptchaVO vo = response.getData();
            return new CaptchaChallenge(vo.getId(), vo.getType(), vo.getBackgroundImage(), vo.getTemplateImage(),
                    vo.getBackgroundImageWidth(), vo.getBackgroundImageHeight(),
                    vo.getTemplateImageWidth(), vo.getTemplateImageHeight());
        } catch (CaptchaException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("[Captcha] 上游生成异常 type={}", e.getClass().getSimpleName(), e);
            throw new CaptchaException(CaptchaException.Kind.UNAVAILABLE, CaptchaErrors.MSG_UNAVAILABLE, e);
        }
    }

    @Override
    public boolean matching(String challengeId, CaptchaTrack track) {
        ImageCaptchaTrack imageCaptchaTrack = new ImageCaptchaTrack();
        imageCaptchaTrack.setBgImageWidth(track.backgroundImageWidth());
        imageCaptchaTrack.setBgImageHeight(track.backgroundImageHeight());
        imageCaptchaTrack.setTemplateImageWidth(track.templateImageWidth());
        imageCaptchaTrack.setTemplateImageHeight(track.templateImageHeight());
        imageCaptchaTrack.setStartTime(track.startTimeMillis());
        imageCaptchaTrack.setStopTime(track.stopTimeMillis());
        List<CaptchaTrack.TrackPoint> points = track.trackList();
        List<ImageCaptchaTrack.Track> trackList = new ArrayList<>(points.size());
        float originX = points.isEmpty() ? 0f : points.getFirst().x();
        float originY = points.isEmpty() ? 0f : points.getFirst().y();
        for (CaptchaTrack.TrackPoint point : points) {
            // Web SDK 1.5.5 记录 pageX/pageY 绝对坐标，但 BasicCaptchaTrackValidator
            // 要求轨迹从 (0, 0) 附近开始。Adapter 在协议边界转换为相对坐标。
            trackList.add(new ImageCaptchaTrack.Track(
                    point.x() - originX,
                    point.y() - originY,
                    point.t(),
                    point.type()));
        }
        imageCaptchaTrack.setTrackList(trackList);
        try {
            // matching 内部原子 GET+DEL 上游正确答案；轨迹启发式失败返回非 success
            ApiResponse<?> response = application.matching(challengeId, imageCaptchaTrack);
            return response.isSuccess();
        } catch (IllegalArgumentException e) {
            // BasicCaptchaTrackValidator.checkParam 参数不合法
            throw new CaptchaException(CaptchaException.Kind.PARAM_INVALID, CaptchaErrors.MSG_PARAM_INVALID, e);
        } catch (RuntimeException e) {
            log.error("[Captcha] 上游 matching 异常 type={}", e.getClass().getSimpleName(), e);
            throw new CaptchaException(CaptchaException.Kind.UNAVAILABLE, CaptchaErrors.MSG_UNAVAILABLE, e);
        }
    }
}
