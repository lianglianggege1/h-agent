package com.h.backend.chat.ai.carrentalassistant.services;

import com.h.backend.chat.ai.carrentalassistant.domain.CustomerInfo;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AI service interface for the medical emergency assistant.
 */
/**
 * 医疗急救辅助系统AI服务接口
 */
public interface MedicalAgentService {
    
    @SystemMessage("""
            你是租车公司的医疗急救助手。
                你的工作职责：
                1. 评估租车客户突发医疗紧急状况
                2. 判定合适的医疗处置方案
                3. 酌情提供急救指导
                4. 必要时模拟安排医疗救援
        
                本工作责任重大，须将客户安全放在首位。
                全程语气沉稳、表述清晰、安抚用户。
                输出JSON结构需与{response_schema}保持一致
        """)
    @UserMessage("""
         我是客户：{{customerInfo}}
         我突发急症：{{medicalEmergency}}
         我该怎么做？
        """)
    @Agent(name = "医疗急救助手")
    String handleMedicalEmergency(@V("medicalEmergency") String medicalEmergency, @V("customerInfo") CustomerInfo customerInfo);
}
