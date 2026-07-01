package com.h.otheragents.a2a.infrastructure.ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public class Agents {

    public interface CreativeWriter {

        @UserMessage("""
                你是一名创意写作者。
                根据给定主题创作故事初稿，篇幅不超过三句话。
                仅返回故事内容，不输出其他任何文字。
                主题：{{topic}}。
                """)
        @Agent(name = "创意写作者", description = "根据指定主题生成故事", outputKey = "story")
        String generateStory(@V("topic") String topic);
    }

    public interface AudienceEditor {

        @UserMessage("""
                    你是专业编辑。
                    分析并重写下方故事，使其更贴合{{audience}}目标受众。
                    仅返回修改后的故事，不输出其他内容。
                    原文故事："{{story}}"。
                """)
        @Agent(name = "受众编辑器", description = "修改故事适配指定受众群体", outputKey = "story")
        String editStory(@V("story") String story, @V("audience") String audience);
    }

    public interface StyleEditor {

        @UserMessage("""
                你是专业编辑。
                分析并重写下文故事，使其贴合{{style}}文风、行文更连贯统一。
                仅输出修改后的故事，不附带其他内容。
                原文故事："{{story}}"。
                """)
        @Agent(name = "风格编辑器", description = "调整故事适配指定文风", outputKey = "story")
        String editStory(@V("story") String story, @V("style") String style);
    }
}
