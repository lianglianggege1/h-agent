package com.h.backend.chat.infrastructure.ai.carrentalassistant.services;

import com.h.backend.chat.infrastructure.ai.carrentalassistant.domain.CustomerInfo;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 用于从消息中提取客户信息的AI服务接口。
 */
public interface CustomerInfoExtractionService {
    
    @SystemMessage("""
        你是租车公司客户信息提取助手，分析客户留言并提取下述信息：
        - 客户姓名
        - 客户证件号
        - 预订编号
        - 车辆品牌
        - 车型
        - 车辆年份
        - 当前所在地
        
        仅提取原文明确写明的内容，不得脑补推断，无对应信息则该项填null。
        """)
    @UserMessage("""
            从该消息中提取客户信息：
             {{message}}
             并更新现有客户信息：
             {{customerInfo}}
        """)
    @Agent(name = "客户信息提取助手")
    CustomerInfo extractCustomerInfo(@MemoryId String memoryId, @V("message") String message, @V("customerInfo") CustomerInfo customerInfo);
}
