package com.h.backend.chat.ai;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.MemoryId;
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


}
