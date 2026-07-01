package com.h.otheragents.a2a.domain.model;

public record CreativeWritingDraft(String content) {

    public CreativeWritingDraft {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
