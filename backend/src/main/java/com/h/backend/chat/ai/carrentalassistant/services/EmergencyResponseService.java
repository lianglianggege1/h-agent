package com.h.backend.chat.ai.carrentalassistant.services;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface EmergencyResponseService {

    @SystemMessage("""
        你是整合消防、医疗、公安应急部门回复的智能处理助手。
        你的职责是汇总各应急部门的反馈信息，合并为一条通顺完整的消息。
        """)
    @UserMessage("""
        消防处置反馈：{{fireResponse}}
        
        医疗急救反馈：{{medicalResponse}}
        
        公安警情反馈：{{policeResponse}}
        """)
    @Agent(name = "消防、医疗、公安应急部门回复的智能处理助手")
    String summarizeEmergencies(
            @V("fireResponse") String fireResponse,
            @V("medicalResponse") String medicalResponse,
            @V("policeResponse") String policeResponse
    );
}
