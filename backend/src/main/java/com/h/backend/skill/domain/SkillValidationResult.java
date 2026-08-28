package com.h.backend.skill.domain;

import java.util.List;

public record SkillValidationResult(
        boolean valid,
        List<String> errors,
        List<String> warnings,
        String validatedHeadSha
) {

    public static SkillValidationResult ok(List<String> warnings, String headSha) {
        return new SkillValidationResult(true, List.of(), List.copyOf(warnings), headSha);
    }

    public static SkillValidationResult invalid(List<String> errors, List<String> warnings, String headSha) {
        return new SkillValidationResult(false, List.copyOf(errors), List.copyOf(warnings), headSha);
    }
}
