package com.h.otheragents.a2a.domain.model;

public record CreativeWritingRequest(String topic) {

    public CreativeWritingRequest {
        if (topic == null || topic.isBlank()) {
            topic = "一次意外的旅程";
        } else {
            topic = topic.trim();
        }
    }
}
