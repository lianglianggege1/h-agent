package com.h.backend.captcha.domain;

/**
 * proof 可用于的业务动作，第一版仅有 LOGIN、REGISTER。
 */
public enum CaptchaPurpose {
    LOGIN,
    REGISTER;

    public static CaptchaPurpose parse(String value) {
        if (value == null) {
            return null;
        }
        for (CaptchaPurpose purpose : values()) {
            if (purpose.name().equals(value)) {
                return purpose;
            }
        }
        return null;
    }
}
