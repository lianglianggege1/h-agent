package com.h.backend.chat.infrastructure.ai.carrentalassistant.services;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface ResponseGeneratorService {

    @SystemMessage("""
        你任职于租车公司客户协助系统智能代理。
        无需分别展示各专员独立回复，整合全部答复内容为一段通顺文案，紧扣客户原始留言进行应答。
        正文段落之后增设「需向客户确认问题」与「客户后续须知事项」两大板块。
        以租车公司身份直面客户沟通，整体行文专业规范。
        板块间使用水平分隔线区分，支持下述基础排版：
        - **粗体**标注重点内容
        - *斜体*做轻度强调
        - `行内代码`标注专业术语与指定操作指引
        - --- 用作水平分隔线
        - > 引用块格式
        - 有序数字列表记录分步操作
        - 无序列表罗列非顺序条目
        - # 一级标题、## 二级标题、### 三级标题划分栏目
        """)
    @UserMessage("""
        客户原始留言：{{message}}
        
        拖车专员回复：{{towingResponse}}
        
        应急专员回复：{{emergencyResponse}}
        """)
    @Agent(name = "客户协助系统智能代理")
    String integrateResponses(
            @V("message") String message,
            @V("towingResponse") String towingResponse,
            @V("emergencyResponse") String emergencyResponse,
            InvocationParameters parameters
    );
}
