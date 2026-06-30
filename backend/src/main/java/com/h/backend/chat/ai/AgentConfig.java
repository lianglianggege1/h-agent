package com.h.backend.chat.ai;

import com.h.backend.chat.ai.carrentalassistant.domain.CustomerInfo;
import com.h.backend.chat.ai.carrentalassistant.domain.Emergencies;
import com.h.backend.chat.ai.carrentalassistant.domain.StoryInfo;
import com.h.backend.chat.ai.carrentalassistant.services.*;
import com.h.backend.chat.agent.AgentStepListener;
import com.h.backend.chat.memory.ChatMemoryIdFactory;
import com.h.backend.chat.memory.RedisChatMemoryStore;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.agentic.workflow.HumanInTheLoop;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

// agent 配置
@Configuration
public class AgentConfig {

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    ChatModel chatModel;

    @Resource
    private AgentStepListener agentStepListener;

    @Resource
    private ChatMemoryIdFactory chatMemoryIdFactory;

    @Bean
    public ExportAssistant exportAssistant() {
        Agents.CategoryRouter categoryRouter = AgenticServices.agentBuilder(Agents.CategoryRouter.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .outputKey("category")
                .build();

        Agents.MedicalExpert medicalExpert = AgenticServices.agentBuilder(Agents.MedicalExpert.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .listener(agentStepListener)
                .outputKey("response")
                .build();

        Agents.LegalExpert legalExpert = AgenticServices.agentBuilder(Agents.LegalExpert.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .listener(agentStepListener)
                .outputKey("response")
                .build();

        Agents.TechnicalExpert technicalExpert = AgenticServices.agentBuilder(Agents.TechnicalExpert.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .listener(agentStepListener)
                .outputKey("response")
                .build();

        UntypedAgent expertsAgent = AgenticServices.conditionalBuilder()
                .name("router")
                .outputKey("response")
                .listener(agentStepListener)
                .subAgents(
                        "Medical request",
                        scope -> scope.readState("category", Agents.RequestCategory.UNKNOWN) == Agents.RequestCategory.MEDICAL,
                        medicalExpert)
                .subAgents(
                        "Technical request",
                        scope -> scope.readState("category", Agents.RequestCategory.UNKNOWN) == Agents.RequestCategory.TECHNICAL,
                        technicalExpert)
                .subAgents(
                        "Legal request",
                        scope -> scope.readState("category", Agents.RequestCategory.UNKNOWN) == Agents.RequestCategory.LEGAL,
                        legalExpert)
                .build();

        return AgenticServices.sequenceBuilder(
                        ExportAssistant.class)
                .subAgents(categoryRouter, expertsAgent)
                .listener(agentStepListener)
                .outputKey("response")
                .build();

    }


    @Bean
    public CarRentalAssistant createAssistant() {
        CustomerInfoExtractionService customerInfoExtraction = AgenticServices.agentBuilder(
                        CustomerInfoExtractionService.class
                ).chatModel(chatModel)
                .listener(agentStepListener)
                .chatMemoryProvider(scopedMemoryProvider("customer-info-extractor"))
                .outputKey("customerInfo")
                .build();

        TowingAgentService towingAgentService = AgenticServices.agentBuilder(TowingAgentService.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .outputKey("towingResponse")
                .build();

        ResponseGeneratorService responseGeneratorService = AgenticServices.agentBuilder(ResponseGeneratorService.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .outputKey("response")
                .build();

        HumanInTheLoop customerInfoClarifier = AgenticServices.humanInTheLoopBuilder()
                .description("向用户追问缺失的租车救援客户信息")
                .listener(agentStepListener)
                .outputKey("response")
                .responseProvider(scope -> customerInfoClarification((CustomerInfo) scope.readState("customerInfo")))
                .build();

        UntypedAgent businessFlow = AgenticServices.sequenceBuilder()
                .listener(agentStepListener)
                .subAgents(
                        towingAgentService,
                        emergencyService(chatModel, redisChatMemoryStore, agentStepListener),
                        responseGeneratorService
                )
                .outputKey("response")
                .build();

        UntypedAgent customerInfoGate = AgenticServices.conditionalBuilder()
                .listener(agentStepListener)
                .subAgents(
                        "customer info is incomplete",
                        agenticScope -> !hasCompleteCustomerInfo(agenticScope),
                        customerInfoClarifier
                )
                .subAgents("customer info is complete", AgentConfig::hasCompleteCustomerInfo, businessFlow)
                .outputKey("response")
                .build();

        return AgenticServices.sequenceBuilder(CarRentalAssistant.class)
                .listener(agentStepListener)
                .beforeCall(agenticScope -> {
                    if (agenticScope.readState("customerInfo") == null) {
                        agenticScope.writeState("customerInfo", new CustomerInfo());
                    }
                })
                .subAgents(customerInfoExtraction, customerInfoGate)
                .outputKey("response")
                .build();
    }

    public static String customerInfoClarification(CustomerInfo customerInfo) {
        List<String> missingFields = missingCustomerInfoFields(customerInfo);
        return "为了继续处理租车救援请求，请补充：" + String.join("、", missingFields) + "。";
    }

    private static boolean hasCompleteCustomerInfo(AgenticScope agenticScope) {
        CustomerInfo customerInfo = (CustomerInfo) agenticScope.readState("customerInfo");
        return customerInfo != null && customerInfo.isComplete();
    }

    private static List<String> missingCustomerInfoFields(CustomerInfo customerInfo) {
        List<String> fields = new ArrayList<>();
        if (customerInfo == null || isBlank(customerInfo.getName())) {
            fields.add("客户姓名");
        }
        if (customerInfo == null || (isBlank(customerInfo.getBookingReference()) && isBlank(customerInfo.getCustomerId()))) {
            fields.add("预订参考号或客户编号");
        }
        if (customerInfo == null || isBlank(customerInfo.getCarMake())) {
            fields.add("车辆品牌");
        }
        if (customerInfo == null || isBlank(customerInfo.getCarModel())) {
            fields.add("车辆型号");
        }
        if (customerInfo == null || isBlank(customerInfo.getLocation())) {
            fields.add("当前位置");
        }
        return fields;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ChatMemoryProvider scopedMemoryProvider(String scopeKey) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(chatMemoryIdFactory.scopedMemoryId(String.valueOf(memoryId), scopeKey))
                .maxMessages(10)
                .alwaysKeepSystemMessageFirst(true)
                .chatMemoryStore(redisChatMemoryStore)
                .build();
    }

    private static UntypedAgent emergencyService(ChatModel chatModel, RedisChatMemoryStore redisChatMemoryStore, AgentStepListener agentStepListener) {
        EmergencyExtractorService emergencyExtractor = AgenticServices.agentBuilder(EmergencyExtractorService.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .outputKey("emergencies")
                .build();

        EmergencyResponseService emergencyResponseService = AgenticServices.agentBuilder(EmergencyResponseService.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .outputKey("emergencyResponse")
                .build();

        FireAgentService fireAgent = AgenticServices.agentBuilder(FireAgentService.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .outputKey("fireResponse")
                .build();
        MedicalAgentService medicalAgent = AgenticServices.agentBuilder(MedicalAgentService.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .outputKey("medicalResponse")
                .build();
        PoliceAgentService policeAgent = AgenticServices.agentBuilder(PoliceAgentService.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .outputKey("policeResponse")
                .build();

        UntypedAgent emergencyExperts = AgenticServices.conditionalBuilder()
                .listener(agentStepListener)
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
                .listener(agentStepListener)
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

    @Bean
    public Agents.StoryChatAgent storyChat() {
        Agents.StoryInfoAgent storyInfoAgent = AgenticServices.agentBuilder(Agents.StoryInfoAgent.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .chatMemoryProvider(scopedMemoryProvider("story-info-extractor"))
                .outputKey("storyInfo")
                .build();

        Agents.CreativeWriter creativeWriter = AgenticServices.agentBuilder(Agents.CreativeWriter.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .outputKey("story")
                .build();

        Agents.AudienceEditor audienceEditor = AgenticServices.agentBuilder(Agents.AudienceEditor.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .outputKey("story")
                .build();

        Agents.StyleEditor styleEditor = AgenticServices.agentBuilder(Agents.StyleEditor.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .outputKey("story")
                .build();

        Agents.StyleScorer styleScorer = AgenticServices.agentBuilder(Agents.StyleScorer.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .outputKey("score")
                .build();

        UntypedAgent storyCreator = AgenticServices.sequenceBuilder()
                .name("故事创作")
                .description("根据主题、风格和受众创作故事")
                .listener(agentStepListener)
                .subAgents(creativeWriter, audienceEditor)
                .outputKey("story")
                .build();

        UntypedAgent styleReviewLoop = AgenticServices.loopBuilder()
                .name("故事审核")
                .description("审核并评分给定故事以确保其与指定风格一致")
                .listener(agentStepListener)
                .subAgents(styleEditor, styleScorer)
                .maxIterations(5)
                .exitCondition(scope -> {
                    Double score = (Double) scope.readState("score", 0.0);
                    return score >= 0.8;
                })
                .outputKey("story")
                .build();

        UntypedAgent storyCreatorWithReview = AgenticServices.sequenceBuilder()
                .name("审核后的故事创作")
                .description("根据主题、风格和受众创作故事并进行审核")
                .listener(agentStepListener)
                .subAgents(storyCreator, styleReviewLoop)
                .outputKey("story")
                .build();

        HumanInTheLoop storyInfoClarifier = AgenticServices.humanInTheLoopBuilder()
                .description("向用户追问缺失的故事创作信息")
                .listener(agentStepListener)
                .outputKey("response")
                .responseProvider(scope -> storyInfoClarification((StoryInfo) scope.readState("storyInfo")))
                .build();

        UntypedAgent storyCreationFlow = AgenticServices.sequenceBuilder()
                .name("故事创作流程")
                .description("映射故事信息并执行故事创作审核")
                .listener(agentStepListener)
                .subAgents(Agents.StoryInfoMapper.class, storyCreatorWithReview)
                .output(scope -> scope.readState("story"))
                .outputKey("response")
                .build();

        UntypedAgent storyInfoGate = AgenticServices.conditionalBuilder()
                .name("故事信息完整性网关")
                .description("故事信息完整则进入创作流程，否则向用户追问缺失信息")
                .listener(agentStepListener)
                .subAgents(
                        "故事创作信息不完整",
                        scope -> !hasCompleteStoryInfo(scope),
                        storyInfoClarifier
                )
                .subAgents(
                        "故事创作信息完整",
                        AgentConfig::hasCompleteStoryInfo,
                        storyCreationFlow
                )
                .outputKey("response")
                .build();

        return AgenticServices.sequenceBuilder(Agents.StoryChatAgent.class)
                .name("故事创作代理")
                .description("根据主题、风格和受众创作故事并进行审核")
                .listener(agentStepListener)
                .subAgents(storyInfoAgent, storyInfoGate)
                .output(scope -> scope.readState("response"))
                .outputKey("response")
                .build();
    }

    private static boolean hasCompleteStoryInfo(AgenticScope scope) {
        return hasCompleteStoryInfo((StoryInfo) scope.readState("storyInfo"));
    }

    private static boolean hasCompleteStoryInfo(StoryInfo storyInfo) {
        return storyInfo != null
                && !isBlank(storyInfo.getTopic())
                && !isBlank(storyInfo.getStyle())
                && !isBlank(storyInfo.getAudience());
    }

    private static String storyInfoClarification(StoryInfo storyInfo) {
        List<String> missingFields = new ArrayList<>();
        if (storyInfo == null || isBlank(storyInfo.getTopic())) {
            missingFields.add("故事主题");
        }
        if (storyInfo == null || isBlank(storyInfo.getStyle())) {
            missingFields.add("故事风格");
        }
        if (storyInfo == null || isBlank(storyInfo.getAudience())) {
            missingFields.add("目标受众");
        }
        return "为了继续创作故事，请补充：" + String.join("、", missingFields) + "。";
    }

    @Bean
    public Agents.BankerAgent bankerAgent() {
        Agents.BankTool bankTool = new Agents.BankTool();
        Agents.WithdrawAgent withdrawAgent = AgenticServices.agentBuilder(Agents.WithdrawAgent.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .tools(bankTool)
                .outputKey("balance")
                .build();

        Agents.CreditAgent creditAgent = AgenticServices.agentBuilder(Agents.CreditAgent.class)
                .chatModel(chatModel)
                .listener(agentStepListener)
                .tools(bankTool)
                .outputKey("balance")
                .build();

        SupervisorAgent bankSupervisor = AgenticServices.supervisorBuilder()
                .name("银行柜员")
                .description("负责执行用户账户美元(USD)存入或支取操作")
                .listener(agentStepListener)
                .chatModel(chatModel)
                .responseStrategy(SupervisorResponseStrategy.SUMMARY)
                .chatMemoryProvider(scopedMemoryProvider("banker-agent"))
                .subAgents(withdrawAgent, creditAgent)
                .outputKey("balance")
                .build();

        return AgenticServices.sequenceBuilder(Agents.BankerAgent.class)
                .name("银行代理")
                .description("负责执行用户账户美元(USD)存入或支取操作")
                .listener(agentStepListener)
                .subAgents(bankSupervisor)
                .outputKey("balance")
                .build();

    }

}
