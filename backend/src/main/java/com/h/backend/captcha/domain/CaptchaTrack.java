package com.h.backend.captcha.domain;

import java.util.List;

/**
 * 官方 Web SDK 收集的渲染尺寸、起止时间和坐标点列。
 */
public record CaptchaTrack(
        Integer backgroundImageWidth,
        Integer backgroundImageHeight,
        Integer templateImageWidth,
        Integer templateImageHeight,
        Long startTimeMillis,
        Long stopTimeMillis,
        List<TrackPoint> trackList) {

    public record TrackPoint(float x, float y, float t, String type) {
    }
}
