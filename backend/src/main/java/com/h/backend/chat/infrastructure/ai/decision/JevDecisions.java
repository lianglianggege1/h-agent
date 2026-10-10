package com.h.backend.chat.infrastructure.ai.decision;

import com.h.backend.chat.infrastructure.ai.Agents.RequestCategory;
import dev.langchain4j.service.V;
import dev.langchain4j.service.decision.Choice;
import dev.langchain4j.service.decision.Decide;
import dev.langchain4j.service.decision.Scale;
import dev.langchain4j.model.output.structured.Description;

public interface JevDecisions {

    @Decide("根据 `request` 的主要诉求，应交给哪个领域的专家？没有匹配领域时选择 UNKNOWN。")
    Choice<RequestCategory> classify(@V("request") String request);

    @Decide("`story` 的语言、语气和叙事方式与 `style` 所指定文风的整体匹配程度如何？只评价文风匹配，不评价主题或个人喜好。")
    Scale<StyleMatchLevel> scoreStyle(@V("story") String story, @V("style") String style);

    // Declaration order is the scale: lowest to highest.
    enum StyleMatchLevel {
        @Description("故事的语言、语气和叙事方式与指定文风无关或明显相反")
        NOT_MATCHED,
        @Description("故事偶有指定文风的特征，但大部分语言和叙事方式不匹配")
        WEAK,
        @Description("故事有明显的指定文风特征，但仍有较多段落或表达不匹配")
        PARTIAL,
        @Description("故事大部分语言、语气和叙事方式符合指定文风，只有少量不一致")
        MOSTLY,
        @Description("故事从头到尾持续体现指定文风，语言、语气和叙事方式一致匹配")
        FULL
    }
}
