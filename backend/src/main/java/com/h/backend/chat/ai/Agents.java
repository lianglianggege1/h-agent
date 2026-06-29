package com.h.backend.chat.ai;

import com.h.backend.chat.ai.carrentalassistant.domain.StoryInfo;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public class Agents {

    public enum RequestCategory {
        //  法律类、医疗类、技术类、未知类
        LEGAL, MEDICAL, TECHNICAL, UNKNOWN
    }

    public interface CategoryRouter {

        @UserMessage("""
                分析下述用户请求并将其归类为'legal'(法律类)、'medical'(医疗类)或'technical'(技术类)。
                若请求不属于以上任一类别，则归类为'unknown'(未知类)。
                仅返回上述单词之一，不得附带其他内容。
                用户请求内容为：'{{request}}'。
                """)
        @Agent(description = "对用户请求进行分类", outputKey = "category")
        RequestCategory classify(@V("request") String request);
    }


    // --------------------------------------------

    /**
     * 重要的是分清你我他 历史你 历史我 历史他 当前你 当前我，当前的会话只有你我
     * 这个涉及到记忆管理，因为要在记忆里面分清楚才能正确回复
     */
    // ---------------------------------------------


    public interface MedicalExpert {

        @UserMessage("""
                你是一名医疗专业专家。
                从医学角度分析下方用户请求，并给出最优解答。
                用户请求内容：{{request}}。
                """)
        @Tool("医疗专家")
        @Agent(name = "医疗专家", description = "医疗专家", outputKey = "response")
        String medical(@MemoryId String memoryId, @V("request") String request);

    }

    public interface LegalExpert {

        @UserMessage("""
                你是一名法律专业专家。
                从法律角度分析下方用户请求，并给出最优解答。
                用户请求内容：{{request}}。
                """)
        @Tool("法律专家")
        @Agent(name = "法律专家", description = "法律专家", outputKey = "response")
        String legal(@MemoryId String memoryId, @V("request") String request);
    }

    public interface TechnicalExpert {

        @UserMessage("""
                    你是一名技术专家。
                    从技术层面分析下述用户请求，并给出最优解答。
                    用户请求内容：{{request}}。
                """)
        @Tool("技术专家")
        @Agent(name = "技术专家", description = "技术专家", outputKey = "response")
        String technical(@MemoryId String memoryId, @V("request") String request);
    }


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


    public interface StyleScorer {

        @UserMessage("""
                你是专业评审。
                根据故事与指定风格「{{style}}」的匹配程度给出0.0至1.0之间的评分。
                仅返回分数，不输出其他任何内容。
                故事原文："{{story}}"
                """)
        @Agent(name = "风格评分器", description = "依据故事与指定风格的契合度进行打分", outputKey = "score")
        double scoreStyle(@V("story") String story, @V("style") String style);
    }


    public interface StoryInfoAgent {
        @SystemMessage("""
                你是提取创作故事所需的相关信息助手，分析用户聊天信息并提取一下信息
                - 故事创造主题
                - 故事创造风格
                - 故事创造受众
                
                仅提取原文明确写明的内容，不得脑补推断，无对应信息则该项填null
                """)
        @UserMessage("""
                {{message}}
                """)
        @Agent(name = "故事信息提取器", description = "从客户聊天信息中提取创作故事所需的相关信息", outputKey = "storyInfo")
        StoryInfo extractStoryInfo(@MemoryId String memoryId, @V("message") String message);
    }

    public static class StoryInfoMapper {

        @Agent(name = "故事信息映射器", outputKey = "storyInfo")
        public static StoryInfo map(@V("storyInfo") StoryInfo storyInfo, AgenticScope scope) {
            if (storyInfo != null) {
                scope.writeState("topic", storyInfo.getTopic());
                scope.writeState("style", storyInfo.getStyle());
                scope.writeState("audience", storyInfo.getAudience());
            }
            return storyInfo;
        }
    }

    public interface StoryChatAgent {

        ResultWithAgenticScope<String> chat(
                @MemoryId String memoryId,
                @V("message") String message
        );
    }

}
