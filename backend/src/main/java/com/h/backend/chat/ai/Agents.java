package com.h.backend.chat.ai;

import com.h.backend.chat.ai.carrentalassistant.domain.StoryInfo;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.agent.ErrorContext;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.agent.MissingArgumentException;
import dev.langchain4j.agentic.declarative.*;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isBlank;

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


    public interface StyleReviewLoopAgent {

        @LoopAgent(
                name = "故事审核",
                description = "审核并评分给定故事以确保其与指定风格一致",
                outputKey = "story",
                maxIterations = 5,
                subAgents = {StyleScorer.class, StyleEditor.class})
        String reviewAndScore(@V("story") String story);

        @ExitCondition
        static boolean exit(@V("score") double score) {
            return score >= 0.8;
        }
    }


    public interface StoryCreator {

        @SequenceAgent(
                name = "故事创作",
                description = "根据主题、风格和受众创作故事",
                outputKey = "story",
                subAgents = {CreativeWriter.class, AudienceEditor.class, StyleEditor.class})
        String write(@V("topic") String topic, @V("style") String style, @V("audience") String audience);

        @ErrorHandler
        static ErrorRecoveryResult errorHandler(ErrorContext errorContext) {
            if (errorContext.agentName().equals("generateStory")
                    && errorContext.exception() instanceof MissingArgumentException mEx
                    && mEx.argumentName().equals("topic")) {
                return ErrorRecoveryResult.retry();
            }
            return ErrorRecoveryResult.throwException();
        }
    }

    public interface StoryCreatorWithReview {

        @SequenceAgent(
                name = "审核后的故事创作",
                description = "根据主题、风格和受众创作故事并进行审核",
                outputKey = "story",
                subAgents = {StoryCreator.class, StyleReviewLoopAgent.class})
        String write(@V("topic") String topic, @V("style") String style, @V("audience") String audience);

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
        static StoryInfo map(@V("storyInfo") StoryInfo storyInfo, AgenticScope scope) {
            if (storyInfo != null) {
                scope.writeState("topic", storyInfo.getTopic());
                scope.writeState("style", storyInfo.getStyle());
                scope.writeState("audience", storyInfo.getAudience());
            }
            return storyInfo;
        }
    }

    public interface StoryInfoClarifier {

        @HumanInTheLoop(
                name = "故事信息补全追问",
                description = "向用户追问缺失的故事创作信息",
                outputKey = "response"
        )
        static String clarify(@V("storyInfo") StoryInfo storyInfo) {
            return storyInfoClarification(storyInfo);
        }
    }

    public interface StoryCreationFlow {

        @SequenceAgent(
                name = "故事创作流程",
                description = "映射故事信息并执行故事创作审核",
                outputKey = "story",
                subAgents = {
                        StoryInfoMapper.class,
                        StoryCreatorWithReview.class
                }
        )
        String create(@V("storyInfo") StoryInfo storyInfo);
    }


    public interface StoryInfoGate {

        @ConditionalAgent(
                name = "故事信息完整性网关",
                description = "故事信息完整则进入创作流程，否则向用户追问缺失信息",
                outputKey = "response",
                subAgents = {
                        StoryInfoClarifier.class,
                        StoryCreationFlow.class
                }
        )
        String route(@V("storyInfo") StoryInfo storyInfo);

        @ActivationCondition(
                value = StoryInfoClarifier.class,
                description = "故事创作信息不完整"
        )
        static boolean needsClarification(@V("storyInfo") StoryInfo storyInfo) {
            return !hasCompleteStoryInfo(storyInfo);
        }

        @ActivationCondition(
                value = StoryCreationFlow.class,
                description = "故事创作信息完整"
        )
        static boolean readyToCreate(@V("storyInfo") StoryInfo storyInfo) {
            return hasCompleteStoryInfo(storyInfo);
        }
    }


    public interface StoryChatAgent {

        @SequenceAgent(
                name = "故事创作代理",
                description = "根据主题、风格和受众创作故事并进行审核",
                outputKey = "response",
                subAgents = {StoryInfoAgent.class, StoryInfoGate.class})
        ResultWithAgenticScope<String> chat(
                @MemoryId String memoryId,
                @V("message") String message
        );
    }

    private static boolean hasCompleteStoryInfo(StoryInfo storyInfo) {
        return storyInfo != null
                && !isBlank(storyInfo.getTopic())
                && !isBlank(storyInfo.getStyle())
                && !isBlank(storyInfo.getAudience());
    }

    private static String storyInfoClarification(StoryInfo storyInfo) {
        List<String> missingFields = new ArrayList<>();

        if (storyInfo == null || isBlank(storyInfo.getTopic())) {
            missingFields.add("故事主题");
        }
        if (storyInfo == null || isBlank(storyInfo.getStyle())) {
            missingFields.add("故事风格");
        }
        if (storyInfo == null || isBlank(storyInfo.getAudience())) {
            missingFields.add("目标受众");
        }

        return "为了继续创作故事，请补充：" + String.join("、", missingFields) + "。";
    }

}
