package com.h.backend.chat.ai.carrentalassistant.services;

import com.h.backend.chat.ai.carrentalassistant.domain.CustomerInfo;
import com.h.backend.chat.ai.carrentalassistant.domain.Emergencies;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 消防应急助手的AI服务接口
 */
public interface EmergencyExtractorService {

    @SystemMessage("""
        你是一名紧急事件处置人员。
        你的工作职责：
        1. 分析用户消息，识别紧急险情
        2. 判定险情类型：公安警情、医疗急救、火灾火警，单条消息可包含多种险情
        3. 提取险情相关信息，填入应急事件对象的对应字段
        4. 某类险情未发生时，对应字段置空
        """)
    @UserMessage("""
        我是求助用户：{{customerInfo}}
        我的留言内容：{{message}}
        """)
    @Agent
    Emergencies extractEmergencies(@V("message") String message, @V("customerInfo") CustomerInfo customerInfo);
}
