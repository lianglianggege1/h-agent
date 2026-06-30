package com.h.backend.chat.ai.a2a;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;

public interface RemoteCreativeWriter {

    @Agent(name = "远端创意写作者", description = "通过 A2A 调用 other-agents 生成故事初稿", outputKey = "draft")
    String generateStory(@V("topic") String topic);
}
