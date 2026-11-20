package com.h.otheragents.team.capability;

import com.h.backend.capability.matrix.MatrixArtifactDeliveryAdapter;
import com.h.backend.capability.matrix.MatrixConversationAssetAdapter;
import com.h.backend.capability.matrix.MatrixGenerationProjectionAdapter;
import com.h.backend.capability.matrix.MatrixMemorySources;
import com.h.backend.capability.matrix.MatrixReferenceImageResolver;
import com.h.backend.capability.matrix.MatrixVideoInputResolver;
import com.h.backend.capability.matrix.NoopLiveProjection;
import com.h.backend.capability.matrix.TeamResourceUrls;
import com.h.backend.capability.port.ArtifactDeliveryPort;
import com.h.backend.capability.port.ConversationAssetPort;
import com.h.backend.capability.port.LiveProjectionPort;
import com.h.backend.capability.port.ResourceUrlPort;
import com.h.backend.chat.application.reference.ReferenceImageResolver;
import com.h.backend.chat.infrastructure.config.ImageGenerationProperties;
import com.h.backend.chat.infrastructure.filesystem.AssistantFileProperties;
import com.h.backend.chat.infrastructure.image.MiniMaxImageClient;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.infrastructure.tools.ShellToolProperties;
import com.h.backend.generation.application.port.in.PollDueGenerationTasksUseCase;
import com.h.backend.generation.application.port.out.GenerationChatProjectionPort;
import com.h.backend.generation.application.port.out.VideoInputResolver;
import com.h.backend.generation.application.service.VideoV2Service;
import com.h.backend.generation.infrastructure.config.GenerationProperties;
import com.h.backend.generation.infrastructure.provider.minimax.FfprobeMediaValidator;
import com.h.backend.generation.interfaces.tool.TeamImageGenerationTool;
import com.h.backend.generation.interfaces.tool.TeamVideoGenerationTool;
import com.h.backend.memory.application.MemorySourcePort;
import com.h.backend.memory.application.TurnMessagePort;
import com.h.matrixteam.MatrixClient;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

/**
 * 团队能力装配：把 backend 普通 jar 中的业务核心 Bean 挂进 other-agents。
 * 仅在 MATRIX_TEAM_ENABLED=true 时生效。backend jar 同时含有应用层 Bean
 * （控制器/安全/聊天管线等），不能包扫描，改为显式 @Import 业务 Bean 清单，
 * 与合并前 agent-capabilities 模块内的 @Component 集合一一对应。
 * 业务侧 @Scheduled 对账任务在本服务不启用（无 @EnableScheduling），仍由 backend 单点执行。
 * 接缝端口全部适配到 Matrix：资产=房间媒体历史，交付=上传 mxc 并发媒体消息。
 */
@Configuration
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
@Import({
        com.h.backend.chat.application.FileDeliveryService.class,
        com.h.backend.chat.application.ResourceContentPolicy.class,
        com.h.backend.chat.application.WebSearchService.class,
        com.h.backend.chat.domain.memory.ChatMemoryIdFactory.class,
        com.h.backend.chat.infrastructure.content.ResourceContentInspector.class,
        com.h.backend.chat.infrastructure.filesystem.AssistantFileStorage.class,
        com.h.backend.chat.infrastructure.image.MiniMaxHttpImageClient.class,
        com.h.backend.chat.infrastructure.storage.ResourceStorageConfiguration.class,
        com.h.backend.chat.infrastructure.storage.TransactionalResourceWriteCoordinator.class,
        com.h.backend.chat.infrastructure.tools.ShellExecutionService.class,
        com.h.backend.document.application.DocumentAssets.class,
        com.h.backend.document.application.DocumentWorkflow.class,
        com.h.backend.document.infrastructure.config.DocumentProperties.class,
        com.h.backend.document.infrastructure.generation.DocumentGenerator.class,
        com.h.backend.document.infrastructure.parsing.LocalDocumentParser.class,
        com.h.backend.document.infrastructure.persistence.DocumentRepository.class,
        com.h.backend.document.infrastructure.provider.ModelBestDocumentClient.class,
        com.h.backend.generation.application.service.GenerationTaskSubmissionCoordinator.class,
        com.h.backend.generation.application.service.MaterializeGeneratedArtifactService.class,
        com.h.backend.generation.application.service.PollDueGenerationTasksService.class,
        com.h.backend.generation.application.service.SubmitImageToVideoService.class,
        com.h.backend.generation.application.service.SubmitTextToVideoService.class,
        com.h.backend.generation.application.service.VideoV2Service.class,
        com.h.backend.generation.domain.service.ImageToVideoSourceValidator.class,
        com.h.backend.generation.infrastructure.persistence.GenerationTaskRepositoryImpl.class,
        com.h.backend.generation.infrastructure.provider.minimax.FfprobeMediaValidator.class,
        com.h.backend.generation.infrastructure.provider.minimax.MiniMaxV2Client.class,
        com.h.backend.generation.infrastructure.provider.minimax.MiniMaxVideoClient.class,
        com.h.backend.generation.infrastructure.scheduling.SpringScheduledGenerationJob.class,
        com.h.backend.generation.infrastructure.storage.ResourceStorageGeneratedArtifactAdapter.class,
        com.h.backend.knowledge.application.KnowledgeTextIndexer.class,
        com.h.backend.knowledge.application.impl.KnowledgeDocumentServiceImpl.class,
        com.h.backend.knowledge.application.impl.KnowledgeIngestServiceImpl.class,
        com.h.backend.knowledge.infrastructure.UserKnowledgeTools.class,
        com.h.backend.knowledge.infrastructure.config.EmbeddingConfig.class,
        com.h.backend.knowledge.infrastructure.config.KnowledgeProperties.class,
        com.h.backend.knowledge.infrastructure.persistence.KnowledgeToolRepository.class,
        com.h.backend.memory.domain.AgentMemoryPolicyCatalog.class,
        com.h.backend.memory.infrastructure.AgentMemoryService.class,
        com.h.backend.memory.infrastructure.AgentMemoryStore.class,
        com.h.backend.memory.infrastructure.MemoryCaptureWorker.class,
        com.h.backend.memory.infrastructure.config.LongTermMemoryConfig.class,
        com.h.backend.memory.infrastructure.langchain4j.ConversationContextAugmentorFactory.class,
        com.h.backend.skill.application.SkillCatalogService.class,
        com.h.backend.skill.application.SkillRuntimeService.class,
        com.h.backend.skill.infrastructure.config.SkillPlatformConfig.class,
        com.h.backend.skill.infrastructure.gitee.GiteeRestSkillRepository.class,
        com.h.backend.skill.infrastructure.nacos.NacosSkillSourceRepository.class,
        com.h.backend.skill.infrastructure.nacos.SkillSourceMigrationRunner.class
})
@MapperScan({
        "com.h.backend.document.infrastructure.persistence.mapper",
        "com.h.backend.skill.infrastructure.persistence.mapper",
        "com.h.backend.memory.infrastructure.persistence.mapper",
        "com.h.backend.knowledge.infrastructure.persistence.mapper",
        "com.h.backend.generation.infrastructure.persistence.mapper"
})
@EnableConfigurationProperties({
        AssistantFileProperties.class, ShellToolProperties.class,
        ImageGenerationProperties.class, GenerationProperties.class
})
public class TeamCapabilityConfig {

    @Bean
    public VideoInputResolver teamVideoInputResolver(
            List<MatrixClient> matrixClients, FfprobeMediaValidator validator) {
        return new MatrixVideoInputResolver(matrixClients, validator);
    }

    @Bean
    public GenerationChatProjectionPort teamGenerationProjectionPort(
            List<MatrixClient> matrixClients, ResourceStorage storage) {
        return new MatrixGenerationProjectionAdapter(matrixClients, storage);
    }

    @Bean
    public ReferenceImageResolver teamReferenceImageResolver(List<MatrixClient> matrixClients) {
        return new MatrixReferenceImageResolver(matrixClients);
    }

    @Bean
    public TeamImageGenerationTool teamImageGenerationTool(
            MiniMaxImageClient imageClient,
            ImageGenerationProperties imageProperties,
            List<MatrixClient> matrixClients) {
        return new TeamImageGenerationTool(imageClient, imageProperties, matrixClients);
    }

    @Bean
    public TeamVideoGenerationTool teamVideoGenerationTool(
            VideoV2Service videos, tools.jackson.databind.ObjectMapper json) {
        return new TeamVideoGenerationTool(videos, json);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public TeamGenerationJob teamGenerationJob(
            PollDueGenerationTasksUseCase pollDueTasks, GenerationProperties properties) {
        return new TeamGenerationJob(pollDueTasks, properties);
    }

    @Bean
    public ConversationAssetPort teamConversationAssetPort(
            List<MatrixClient> matrixClients,
            @Value("${MATRIX_HUMAN_USER_ID:17}") long humanUserId,
            @Value("${MATRIX_HUMAN_USERNAME:huajiang}") String humanMatrixUsername) {
        return new MatrixConversationAssetAdapter(matrixClients, humanUserId, humanMatrixUsername);
    }

    @Bean
    public ArtifactDeliveryPort teamArtifactDeliveryPort(
            ResourceWriteCoordinator coordinator, List<MatrixClient> matrixClients) {
        return new MatrixArtifactDeliveryAdapter(coordinator, matrixClients);
    }

    @Bean
    public ResourceUrlPort teamResourceUrlPort() {
        return new TeamResourceUrls();
    }

    @Bean
    public LiveProjectionPort teamLiveProjectionPort() {
        return new NoopLiveProjection();
    }

    @Bean
    public MemorySourcePort teamMemorySourcePort(List<MatrixClient> matrixClients) {
        return new MatrixMemorySources(matrixClients);
    }

    /**
     * outbox 回读端口仅被 MemoryCaptureWorker 使用；本服务无 @EnableScheduling，
     * worker 不会运行，团队执行也不写 outbox（由 backend 的 SuccessfulTurnCommitter 负责），
     * 此 no-op 只满足装配。
     */
    @Bean
    public TurnMessagePort teamTurnMessagePort() {
        return (agentRunId, userMessageId, assistantMessageId) -> new TurnMessagePort.TurnTexts(null, null);
    }

    @Bean
    public com.h.matrixteam.TeamRunTracker teamRunTracker() {
        return new com.h.matrixteam.TeamRunTracker();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public TeamDocumentJob teamDocumentJob(
            com.h.backend.document.infrastructure.persistence.DocumentRepository documentRepository,
            com.h.backend.document.application.DocumentWorkflow documentWorkflow,
            com.h.backend.document.infrastructure.config.DocumentProperties documentProperties,
            com.h.matrixteam.TeamRunTracker teamRunTracker,
            @org.springframework.beans.factory.annotation.Qualifier("workerHandler")
            com.h.matrixteam.TeamRoomBridge.Handler workerHandler,
            @org.springframework.beans.factory.annotation.Qualifier("springWorkerHandler")
            com.h.matrixteam.TeamRoomBridge.Handler springWorkerHandler,
            MatrixClient workerMatrixClient,
            MatrixClient springWorkerMatrixClient,
            @Value("${MATRIX_HUMAN_USERNAME:huajiang}") String humanUsername) {
        String domain = domainOf(workerMatrixClient.userId());
        return new TeamDocumentJob(
                documentRepository, documentWorkflow, documentProperties,
                new com.h.backend.capability.matrix.TeamDocumentContinuation(
                        documentRepository, documentWorkflow, teamRunTracker),
                workerHandler, springWorkerHandler,
                springWorkerMatrixClient.userId(), "@" + humanUsername + ":" + domain);
    }

    private static String domainOf(String userId) {
        int colon = userId.indexOf(':');
        return colon < 0 ? "matrix.local" : userId.substring(colon + 1);
    }
}