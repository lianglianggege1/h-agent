package com.h.backend.skill.domain;

public class SkillPlatformException extends RuntimeException {

    private final SkillPlatformErrorKind kind;

    public SkillPlatformException(SkillPlatformErrorKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public SkillPlatformException(SkillPlatformErrorKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static SkillPlatformException of(SkillPlatformErrorKind kind) {
        return new SkillPlatformException(kind, kind.defaultMessage());
    }

    public static SkillPlatformException of(SkillPlatformErrorKind kind, String message) {
        return new SkillPlatformException(kind, message);
    }

    public static SkillPlatformException of(SkillPlatformErrorKind kind, String message, Throwable cause) {
        return new SkillPlatformException(kind, message, cause);
    }

    public SkillPlatformErrorKind kind() {
        return kind;
    }
}
