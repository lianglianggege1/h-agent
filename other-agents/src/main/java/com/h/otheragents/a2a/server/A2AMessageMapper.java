package com.h.otheragents.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public class A2AMessageMapper {

    public List<String> textParts(JsonNode message) {
        List<String> texts = new ArrayList<>();
        JsonNode parts = message.path("parts");
        if (!parts.isArray()) {
            return texts;
        }
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                texts.add(text);
            }
        }
        return texts;
    }
}
