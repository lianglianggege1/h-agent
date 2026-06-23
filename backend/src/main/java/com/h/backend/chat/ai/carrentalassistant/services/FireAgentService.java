package com.h.backend.chat.ai.carrentalassistant.services;

import com.h.backend.chat.ai.carrentalassistant.domain.CustomerInfo;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AI service interface for the fire emergency assistant.
 */
/**
 * 消防应急助手AI服务接口
 */
public interface FireAgentService {

    @SystemMessage("""
        你是租车公司的火灾应急协助专员。
        工作职责：
        1. 处理租车客户遭遇的车辆火情险情研判
        2. 判定起火类型与火情严重程度
        3. 向客户提供安全避险指导
        4. 必要时模拟调度消防人员出警

        本工作责任重大，始终将客户人身安全放在首位。
        回复语气保持沉稳、条理清晰、安抚人心。
        """)
    @UserMessage("""
        客户信息：{{customerInfo}}
        火情详情：{{fireEmergency}}
        我该怎么做？
        """)
    @Agent
    String handleFireEmergency(@MemoryId String memoryId, @V("fireEmergency") String fireEmergency, @V("customerInfo") CustomerInfo customerInfo);
}
