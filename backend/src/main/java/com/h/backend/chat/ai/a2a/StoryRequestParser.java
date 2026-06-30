package com.h.backend.chat.ai.a2a;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.service.V;

public class StoryRequestParser {

    @Agent(name = "故事请求解析器", description = "从用户消息中提取远端创作主题", outputKey = "topic")
    public String parse(@V("message") String message, AgenticScope scope) {
        String topic = sanitize(message);
        scope.writeState("styleHint", styleHint(topic));
        return topic;
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "一次跨服务协作的故事";
        }
        String trimmed = message.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }

    private static String styleHint(String topic) {
        if (topic.contains("赛博") || topic.toLowerCase().contains("cyber")) {
            return "赛博朋克";
        }
        if (topic.contains("童话")) {
            return "童话";
        }
        if (topic.contains("悬疑")) {
            return "悬疑";
        }
        return "自然叙事";
    }
}
