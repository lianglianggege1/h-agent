package com.h.backend.chat.ai.carrentalassistant.services;

import com.h.backend.chat.ai.carrentalassistant.domain.CustomerInfo;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AI service interface for the towing assistant.
 */
public interface TowingAgentService {

    @SystemMessage("""
        你是租车公司拖车协助专员。
        工作职责：
        1. 判断客户是否需要拖车服务，无需拖车则直接返回"No towing required"
        2. 采集车辆及车况相关必要信息
        3. 确认拖车类型（平板拖车或普通拖车）
        4. 评估拖车现场的安全状况
        5. 模拟指派拖车前往客户所在地

        全程保持专业友善的沟通语气，输出JSON格式遵照{response_schema}规范
        """)
    @UserMessage("""
       我是客户：{{customerInfo}}
       客户留言：{{message}}
       """)
    @Agent
    String processTowingRequest(@MemoryId String memoryId, @V("message") String message, @V("customerInfo") CustomerInfo customerInfo);
}
