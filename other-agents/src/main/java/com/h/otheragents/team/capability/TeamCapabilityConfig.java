package com.h.otheragents.team.capability;

import com.h.capabilities.seam.matrix.MatrixArtifactDeliveryAdapter;
import com.h.capabilities.seam.matrix.MatrixConversationAssetAdapter;
import com.h.capabilities.seam.matrix.MatrixGenerationProjectionAdapter;
import com.h.capabilities.seam.matrix.MatrixMemorySources;
import com.h.capabilities.seam.matrix.MatrixReferenceImageResolver;
import com.h.capabilities.seam.matrix.MatrixVideoInputResolver;
import com.h.capabilities.seam.matrix.NoopLiveProjection;
import com.h.capabilities.seam.matrix.TeamResourceUrls;
import com.h.capabilities.seam.matrix.TeamHumanDirectory;
import com.h.capabilities.seam.port.ArtifactDeliveryPort;
import com.h.capabilities.seam.port.ConversationAssetPort;
import com.h.capabilities.seam.port.LiveProjectionPort;
import com.h.capabilities.seam.port.ResourceUrlPort;
import com.h.capabilities.chat.application.reference.ReferenceImageResolver;
import com.h.capabilities.chat.infrastructure.config.ImageGenerationProperties;
import com.h.capabilities.chat.infrastructure.filesystem.AssistantFileProperties;
import com.h.capabilities.chat.infrastructure.image.MiniMaxImageClient;
import com.h.capabilities.chat.infrastructure.storage.ResourceStorage;
import com.h.capabilities.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.capabilities.chat.infrastructure.tools.ShellToolProperties;
import com.h.capabilities.generation.application.port.in.PollDueGenerationTasksUseCase;
import com.h.capabilities.generation.application.port.out.GenerationChatProjectionPort;
import com.h.capabilities.generation.application.port.out.VideoInputResolver;
import com.h.capabilities.generation.application.service.VideoV2Service;
import com.h.capabilities.generation.infrastructure.config.GenerationProperties;
import com.h.capabilities.generation.infrastructure.provider.minimax.FfprobeMediaValidator;
import com.h.capabilities.generation.interfaces.tool.TeamImageGenerationTool;
import com.h.capabilities.generation.interfaces.tool.TeamVideoGenerationTool;
import com.h.capabilities.memory.application.MemorySourcePort;
import com.h.capabilities.memory.application.TurnMessagePort;
import com.h.matrixteam.MatrixClient;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 团队能力装配：把 agent-capabilities 共享模块（com.h.capabilities.* 包名）挂进 other-agents。
 * 仅在 MATRIX_TEAM_ENABLED=true 时生效；other-agents 不依赖 backend jar，
 * 因此 ComponentScan 只会扫到模块内的 Bean。模块的 @Scheduled 对账任务在本服务
 * 不启用（无 @EnableScheduling），仍由 backend 单点执行。
 * 接缝端口全部适配到 Matrix：资产=房间媒体历史，交付=上传 mxc 并发媒体消息。
 */
@Configuration
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
@ComponentScan("com.h.capabilities")
@MapperScan({
        "com.h.capabilities.document.infrastructure.persistence.mapper",
        "com.h.capabilities.skill.infrastructure.persistence.mapper",
        "com.h.capabilities.memory.infrastructure.persistence.mapper",
        "com.h.capabilities.knowledge.infrastructure.persistence.mapper",
        "com.h.capabilities.generation.infrastructure.persistence.mapper"
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
            TeamHumanDirectory directory) {
        return new MatrixConversationAssetAdapter(matrixClients, directory);
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
            com.h.capabilities.document.infrastructure.persistence.DocumentRepository documentRepository,
            com.h.capabilities.document.application.DocumentWorkflow documentWorkflow,
            com.h.capabilities.document.infrastructure.config.DocumentProperties documentProperties,
            com.h.matrixteam.TeamRunTracker teamRunTracker,
            @org.springframework.beans.factory.annotation.Qualifier("workerHandler")
            com.h.matrixteam.TeamRoomBridge.Handler workerHandler,
            @org.springframework.beans.factory.annotation.Qualifier("springWorkerHandler")
            com.h.matrixteam.TeamRoomBridge.Handler springWorkerHandler,
            MatrixClient springWorkerMatrixClient) {
        return new TeamDocumentJob(
                documentRepository, documentWorkflow, documentProperties,
                new com.h.capabilities.seam.matrix.TeamDocumentContinuation(
                        documentRepository, documentWorkflow, teamRunTracker),
                workerHandler, springWorkerHandler,
                springWorkerMatrixClient.userId());
    }
}