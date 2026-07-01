package com.h.otheragents.a2a.domain.model;

public record StoryEditRequest(String story, String instruction) {

    public StoryEditRequest {
        if (story == null || story.isBlank()) {
            throw new IllegalArgumentException("story must not be blank");
        }
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction must not be blank");
        }
        story = story.trim();
        instruction = instruction.trim();
    }
}
