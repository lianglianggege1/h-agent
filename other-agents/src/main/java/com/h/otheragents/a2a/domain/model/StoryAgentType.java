package com.h.otheragents.a2a.domain.model;

import java.util.Locale;
import java.util.Set;

public enum StoryAgentType {

    CREATIVE_WRITER,
    AUDIENCE_EDITOR,
    STYLE_EDITOR;

    private static final Set<String> AUDIENCE_HINTS = Set.of(
            "儿童", "孩子", "小学生", "中学生", "青少年", "学生", "成人", "家长", "老人", "读者", "观众", "受众", "用户"
    );

    private static final Set<String> STYLE_HINTS = Set.of(
            "风格", "文风", "赛博", "朋克", "科幻", "童话", "悬疑", "浪漫", "现实主义", "古风", "幽默", "严肃", "诗意", "黑色幽默"
    );

    public static StoryAgentType fromMetadata(String value, int partCount, String secondPart) {
        if (value != null && !value.isBlank()) {
            String normalized = value.trim()
                    .replace('-', '_')
                    .replace(' ', '_')
                    .toUpperCase(Locale.ROOT);
            for (StoryAgentType type : values()) {
                if (type.name().equals(normalized)) {
                    return type;
                }
            }
        }

        if (partCount <= 1) {
            return CREATIVE_WRITER;
        }
        return looksLikeAudience(secondPart) ? AUDIENCE_EDITOR : STYLE_EDITOR;
    }

    private static boolean looksLikeAudience(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String hint : AUDIENCE_HINTS) {
            if (value.contains(hint)) {
                return true;
            }
        }
        for (String hint : STYLE_HINTS) {
            if (value.contains(hint)) {
                return false;
            }
        }
        return false;
    }
}
