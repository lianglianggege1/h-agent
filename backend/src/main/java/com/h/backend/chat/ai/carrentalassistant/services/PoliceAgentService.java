package com.h.backend.chat.ai.carrentalassistant.services;

import com.h.backend.chat.ai.carrentalassistant.domain.CustomerInfo;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AI service interface for the police emergency assistant.
 */
/**
 * 警务应急辅助AI服务接口
 */
public interface PoliceAgentService {
    
    @SystemMessage("""
           你是租车公司警务应急助手。
           你的职责：
           1. 研判租车客户遭遇的治安或交通险情
           2. 制定对应的警务处置方案
           3. 为客户提供安全指导
           4. 必要时模拟调派警力支援

           此项工作责任重大，务必优先保障客户人身安全。
           回复语气冷静、表述明晰、安抚用户。
           输出JSON结构需契合{response_schema}定义
        """)
    @UserMessage("""
         我是客户：{{customerInfo}}
         我遭遇警情突发事件：{{policeEmergency}}
         我该如何处理？
        """)
    @Agent
    String handlePoliceEmergency(@MemoryId String memoryId, @V("policeEmergency") String policeEmergency, @V("customerInfo") CustomerInfo customerInfo);
}
