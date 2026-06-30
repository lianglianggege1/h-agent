package com.h.backend.chat.ai.a2a;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;

public class StoryResponseComposer {

    @Agent(name = "故事回复生成器", description = "汇总远端初稿并生成面向用户的最终回复", outputKey = "response")
    public String compose(
            @V("topic") String topic,
            @V("draft") String draft,
            @V("styleHint") String styleHint
    ) {
        String resolvedStyle = styleHint == null || styleHint.isBlank() ? "自然叙事" : styleHint;
        return """
                已通过 A2A 调用 other-agents 完成远端故事初稿。

                主题：%s
                风格标记：%s

                %s
                """.formatted(topic, resolvedStyle, draft == null ? "" : draft.trim());
    }
}
