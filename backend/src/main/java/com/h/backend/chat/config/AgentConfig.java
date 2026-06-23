package com.h.backend.chat.config;

import com.h.backend.chat.ai.carrentalassistant.domain.CustomerInfo;
import com.h.backend.chat.ai.carrentalassistant.domain.Emergencies;
import com.h.backend.chat.ai.carrentalassistant.services.*;
import com.h.backend.chat.memory.RedisChatMemoryStore;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// agent 配置
@Configuration
public class AgentConfig {

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    ChatModel chatModel;

    @Bean
    public CarRentalAssistant createAssistant() {
        CustomerInfoExtractionService customerInfoExtraction = AgenticServices.agentBuilder(
                        CustomerInfoExtractionService.class
                ).chatModel(chatModel)
                // 记忆模块提供者
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .outputKey("customerInfo")
                .build();

        TowingAgentService towingAgentService = AgenticServices.agentBuilder(TowingAgentService.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .outputKey("towingResponse")
                .build();

        ResponseGeneratorService responseGeneratorService = AgenticServices.agentBuilder(ResponseGeneratorService.class)
                .chatModel(chatModel)
                // 记忆模块提供者
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .outputKey("response")
                .build();
        return AgenticServices.sequenceBuilder(CarRentalAssistant.class).beforeCall(agenticScope -> {
                    if (agenticScope.readState("customerInfo") == null) {
                        agenticScope.writeState("customerInfo", new CustomerInfo());
                    }
                })
                .subAgents(customerInfoExtraction, towingAgentService, emergencyService(chatModel, redisChatMemoryStore), responseGeneratorService)
                .outputKey("response")
                .build();
    }

    private static UntypedAgent emergencyService(ChatModel chatModel, RedisChatMemoryStore redisChatMemoryStore) {
        EmergencyExtractorService emergencyExtractor = AgenticServices.agentBuilder(EmergencyExtractorService.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .outputKey("emergencies")
                .build();

        EmergencyResponseService emergencyResponseService = AgenticServices.agentBuilder(EmergencyResponseService.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .outputKey("emergencyResponse")
                .build();

        FireAgentService fireAgent = AgenticServices.agentBuilder(FireAgentService.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .outputKey("fireResponse")
                .build();
        MedicalAgentService medicalAgent = AgenticServices.agentBuilder(MedicalAgentService.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .outputKey("medicalResponse")
                .build();
        PoliceAgentService policeAgent = AgenticServices.agentBuilder(PoliceAgentService.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .outputKey("policeResponse")
                .build();

        UntypedAgent emergencyExperts = AgenticServices.conditionalBuilder()
                .beforeCall(agenticScope -> {
                    Emergencies emergencies = (Emergencies) agenticScope.readState("emergencies");
                    writeEmergency(agenticScope, emergencies.getFire(), "fire");
                    writeEmergency(agenticScope, emergencies.getMedical(), "medical");
                    writeEmergency(agenticScope, emergencies.getPolice(), "police");
                })
                .subAgents(agenticScope -> agenticScope.hasState("fireEmergency"), fireAgent)
                .subAgents(agenticScope -> agenticScope.hasState("medicalEmergency"), medicalAgent)
                .subAgents(agenticScope -> agenticScope.hasState("policeEmergency"), policeAgent)
                .build();

        return AgenticServices.sequenceBuilder()
                .subAgents(emergencyExtractor, emergencyExperts, emergencyResponseService)
                .outputKey("emergencyResponse")
                .build();
    }

    private static void writeEmergency(AgenticScope agenticScope, String emergency, String type) {
        if (emergency == null || emergency.isBlank()) {
            agenticScope.writeState(type + "Emergency", null);
            agenticScope.writeState(type + "Response", "");
        } else {
            agenticScope.writeState(type + "Emergency", emergency);
        }
    }


}
