package com.h.backend.chat.application.agui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;

/** Product display contract for tool inputs. Unlisted tools and fields stay inside the runtime. */
final class DisplayableToolArguments {
    static final int MAX_INPUT = 16_384;
    private static final int MAX_VALUE = 1_024;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Map<String, List<String>> VISIBLE_FIELDS = Map.of(
            "web_search", List.of("query", "maxResults"),
            "knowledge_search", List.of("query"),
            "read_file", List.of("path", "offset", "limit"));

    private DisplayableToolArguments() {}

    static boolean supports(String toolName) {
        return toolName != null && VISIBLE_FIELDS.containsKey(toolName);
    }

    static Map<String, Object> select(String toolName, String rawArguments) {
        if (!supports(toolName) || rawArguments.isBlank() || rawArguments.length() > MAX_INPUT) {
            return Map.of();
        }
        try {
            JsonNode parsed = JSON.readTree(rawArguments);
            if (!parsed.isObject()) return Map.of();
            Map<String, Object> displayable = new LinkedHashMap<>();
            for (String field : VISIBLE_FIELDS.get(toolName)) {
                JsonNode value = parsed.path(field);
                if (value.isTextual() && value.textValue().length() <= MAX_VALUE) {
                    displayable.put(field, value.textValue());
                } else if (value.isNumber()) {
                    displayable.put(field, value.numberValue());
                }
            }
            return Collections.unmodifiableMap(displayable);
        } catch (JsonProcessingException malformedArguments) {
            return Map.of();
        }
    }

    static Map<String, Object> selectPartial(String toolName, String rawArguments) {
        var fields = VISIBLE_FIELDS.get(toolName);
        if (fields == null || rawArguments.isBlank() || rawArguments.length() > MAX_INPUT) return Map.of();
        Map<String, Object> displayable = new LinkedHashMap<>();
        try (var parser = JSON.createParser(rawArguments)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return Map.of();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken token = parser.nextToken();
                if (token == null) break;
                if (fields.contains(field)) {
                    if (token == JsonToken.VALUE_STRING) {
                        String value = parser.getText();
                        if (value.length() <= MAX_VALUE) displayable.put(field, value);
                    } else if (token.isNumeric()) {
                        displayable.put(field, parser.getNumberValue());
                    }
                }
                parser.skipChildren();
            }
        } catch (IOException waitingForMore) {
            // Keep complete, allowed fields that preceded the unfinished token.
        }
        return Collections.unmodifiableMap(displayable);
    }

    static String toJson(Map<String, Object> arguments) {
        return JSON.valueToTree(arguments).toString();
    }
}
