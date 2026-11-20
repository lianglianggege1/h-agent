package com.h.backend.team;

import com.h.capabilities.seam.AnchorMessage;
import com.h.capabilities.seam.AssetRef;
import com.h.capabilities.seam.DeliveredArtifact;
import com.h.capabilities.seam.DeliveryResult;
import com.h.capabilities.seam.matrix.MatrixArtifactDeliveryAdapter;
import com.h.capabilities.seam.matrix.MatrixConversationAssetAdapter;
import com.h.capabilities.seam.matrix.MatrixMemorySources;
import com.h.capabilities.seam.matrix.TeamHumanDirectory;
import com.h.capabilities.seam.port.ArtifactDeliveryPort;
import com.h.capabilities.seam.port.ConversationAssetPort;
import com.h.capabilities.seam.port.LiveProjectionPort;
import com.h.capabilities.seam.port.ResourceUrlPort;
import com.h.backend.chat.infrastructure.capability.ChatArtifactDeliveryAdapter;
import com.h.backend.chat.infrastructure.capability.ChatConversationAssetAdapter;
import com.h.backend.chat.infrastructure.capability.ChatLiveProjectionAdapter;
import com.h.backend.chat.infrastructure.capability.ChatResourceUrlAdapter;
import com.h.capabilities.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.capabilities.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.capabilities.memory.application.AgentMemoryContext;
import com.h.capabilities.memory.application.MemorySourcePort;
import com.h.backend.memory.application.MemorySources;
import com.h.matrixteam.MatrixClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 团队 Manager 的能力端口路由：产品聊天与团队房间共享同一套模块工具（单例端口 Bean），
 * 按 scope 形态分流——roomId 以 "!" 开头、资源 ID 为 mxc://、来源引用为 matrix: 前缀时
 * 走 Matrix 适配器，其余走原产品聊天适配器。产品聊天路径行为不变。
 */
@Configuration
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class TeamCapabilityRoutingConfig {

    static boolean isTeamScope(String scopeId) {
        return scopeId != null && scopeId.startsWith("!");
    }

    @Bean
    public MatrixConversationAssetAdapter teamMatrixAssets(
            MatrixClient teamMatrixClient,
            TeamHumanDirectory directory) {
        return new MatrixConversationAssetAdapter(List.of(teamMatrixClient), directory);
    }

    @Bean
    public MatrixArtifactDeliveryAdapter teamMatrixDelivery(
            ResourceWriteCoordinator coordinator, MatrixClient teamMatrixClient) {
        return new MatrixArtifactDeliveryAdapter(coordinator, List.of(teamMatrixClient));
    }

    @Bean
    public MatrixMemorySources teamMatrixMemorySources(MatrixClient teamMatrixClient) {
        return new MatrixMemorySources(List.of(teamMatrixClient));
    }

    @Bean
    @Primary
    public ConversationAssetPort routingConversationAssetPort(
            ChatConversationAssetAdapter chat, MatrixConversationAssetAdapter matrix) {
        return new ConversationAssetPort() {
            @Override
            public void requireScope(long userId, String scopeId) {
                if (isTeamScope(scopeId)) {
                    matrix.requireScope(userId, scopeId);
                } else {
                    chat.requireScope(userId, scopeId);
                }
            }

            @Override
            public AssetRef requireAsset(long userId, String scopeId, String resourceId) {
                return isTeamScope(scopeId)
                        ? matrix.requireAsset(userId, scopeId, resourceId)
                        : chat.requireAsset(userId, scopeId, resourceId);
            }

            @Override
            public byte[] readAsset(long userId, String scopeId, String resourceId, long maxBytes) {
                return isTeamScope(scopeId)
                        ? matrix.readAsset(userId, scopeId, resourceId, maxBytes)
                        : chat.readAsset(userId, scopeId, resourceId, maxBytes);
            }

            @Override
            public List<Map<String, Object>> inputs(long userId, String scopeId) {
                return isTeamScope(scopeId) ? matrix.inputs(userId, scopeId) : chat.inputs(userId, scopeId);
            }

            @Override
            public AnchorMessage latestUserMessage(long userId, String scopeId) {
                return isTeamScope(scopeId)
                        ? matrix.latestUserMessage(userId, scopeId)
                        : chat.latestUserMessage(userId, scopeId);
            }

            @Override
            public AnchorMessage messageById(long messageId) {
                // Matrix 锚点为 eventId 哈希（极大值），产品消息为数据库自增 ID；先查团队缓存再落产品。
                AnchorMessage anchor = matrix.messageById(messageId);
                return anchor != null ? anchor : chat.messageById(messageId);
            }
        };
    }

    @Bean
    @Primary
    public ArtifactDeliveryPort routingArtifactDeliveryPort(
            ChatArtifactDeliveryAdapter chat, MatrixArtifactDeliveryAdapter matrix) {
        return new ArtifactDeliveryPort() {
            @Override
            public DeliveryResult deliver(long userId, String scopeId, ResourceSaveCommand command,
                                          String resourceType, String fileName, String mimeType,
                                          String text, Consumer<DeliveredArtifact> onAttached) {
                return isTeamScope(scopeId)
                        ? matrix.deliver(userId, scopeId, command, resourceType, fileName, mimeType, text, onAttached)
                        : chat.deliver(userId, scopeId, command, resourceType, fileName, mimeType, text, onAttached);
            }
        };
    }

    @Bean
    @Primary
    public ResourceUrlPort routingResourceUrlPort(ChatResourceUrlAdapter chat) {
        return new ResourceUrlPort() {
            @Override
            public String view(String resourceId) {
                return resourceId != null && resourceId.startsWith("mxc://") ? resourceId : chat.view(resourceId);
            }

            @Override
            public String download(String resourceId) {
                return resourceId != null && resourceId.startsWith("mxc://") ? resourceId : chat.download(resourceId);
            }
        };
    }

    @Bean
    @Primary
    public LiveProjectionPort routingLiveProjectionPort(ChatLiveProjectionAdapter chat) {
        // 团队交付句柄恒为 null（Matrix 消息即投影）；产品句柄非空走 SSE。
        return (memoryId, projectionHandle) -> {
            if (projectionHandle != null) {
                chat.publish(memoryId, projectionHandle);
            }
        };
    }

    @Bean
    @Primary
    public MemorySourcePort routingMemorySourcePort(MemorySources chat, MatrixMemorySources matrix) {
        return new MemorySourcePort() {
            @Override
            public Map<String, Object> search(AgentMemoryContext ctx, String query) {
                return isTeamScope(ctx.sessionId()) ? matrix.search(ctx, query) : chat.search(ctx, query);
            }

            @Override
            public Map<String, Object> read(Long user, String ref, int offset) {
                return ref != null && ref.startsWith("matrix:")
                        ? matrix.read(user, ref, offset)
                        : chat.read(user, ref, offset);
            }

            @Override
            public List<String> validated(AgentMemoryContext ctx, List<String> supplied) {
                return isTeamScope(ctx.sessionId()) ? matrix.validated(ctx, supplied) : chat.validated(ctx, supplied);
            }
        };
    }

    @Bean
    public com.h.capabilities.seam.matrix.MatrixVideoInputResolver teamMatrixVideoInputs(
            MatrixClient teamMatrixClient,
            com.h.capabilities.generation.infrastructure.provider.minimax.FfprobeMediaValidator validator) {
        return new com.h.capabilities.seam.matrix.MatrixVideoInputResolver(List.of(teamMatrixClient), validator);
    }

    @Bean
    public com.h.capabilities.seam.matrix.MatrixGenerationProjectionAdapter teamMatrixGenerationProjection(
            MatrixClient teamMatrixClient,
            com.h.capabilities.chat.infrastructure.storage.ResourceStorage storage) {
        return new com.h.capabilities.seam.matrix.MatrixGenerationProjectionAdapter(List.of(teamMatrixClient), storage);
    }

    @Bean
    public com.h.capabilities.seam.matrix.MatrixReferenceImageResolver teamMatrixReferenceImages(
            MatrixClient teamMatrixClient) {
        return new com.h.capabilities.seam.matrix.MatrixReferenceImageResolver(List.of(teamMatrixClient));
    }

    @Bean
    public com.h.capabilities.generation.interfaces.tool.TeamImageGenerationTool teamImageGenerationTool(
            com.h.capabilities.chat.infrastructure.image.MiniMaxImageClient imageClient,
            com.h.capabilities.chat.infrastructure.config.ImageGenerationProperties imageProperties,
            MatrixClient teamMatrixClient) {
        return new com.h.capabilities.generation.interfaces.tool.TeamImageGenerationTool(
                imageClient, imageProperties, List.of(teamMatrixClient));
    }

    @Bean
    public com.h.capabilities.generation.interfaces.tool.TeamVideoGenerationTool teamVideoGenerationTool(
            com.h.capabilities.generation.application.service.VideoV2Service videos,
            tools.jackson.databind.ObjectMapper json) {
        return new com.h.capabilities.generation.interfaces.tool.TeamVideoGenerationTool(videos, json);
    }

    @Bean
    @Primary
    public com.h.capabilities.generation.application.port.out.VideoInputResolver routingVideoInputResolver(
            com.h.backend.generation.infrastructure.provider.minimax.StoredVideoInputResolver stored,
            com.h.capabilities.seam.matrix.MatrixVideoInputResolver matrix) {
        // 团队素材为 mxc:// 房间媒体；产品素材为数据库资源 ID。
        return (userId, spec) ->
                spec.inputs().stream().anyMatch(input -> input.resourceId().startsWith("mxc://"))
                        ? matrix.resolve(userId, spec)
                        : stored.resolve(userId, spec);
    }

    @Bean
    @Primary
    public com.h.capabilities.generation.application.port.out.GenerationChatProjectionPort routingGenerationProjection(
            com.h.backend.generation.infrastructure.projection.ChatGenerationProjectionAdapter chat,
            com.h.capabilities.seam.matrix.MatrixGenerationProjectionAdapter matrix) {
        return new com.h.capabilities.generation.application.port.out.GenerationChatProjectionPort() {
            @Override
            public Long createPendingMessage(com.h.capabilities.generation.domain.model.GenerationTask task) {
                return isTeamScope(task.sessionId())
                        ? matrix.createPendingMessage(task)
                        : chat.createPendingMessage(task);
            }

            @Override
            public void updateMessage(com.h.capabilities.generation.domain.model.GenerationTask task) {
                if (isTeamScope(task.sessionId())) {
                    matrix.updateMessage(task);
                } else {
                    chat.updateMessage(task);
                }
            }
        };
    }

    @Bean
    @Primary
    public com.h.capabilities.chat.application.reference.ReferenceImageResolver routingReferenceImageResolver(
            com.h.backend.chat.application.impl.ChatReferenceImageResolver chat,
            com.h.capabilities.seam.matrix.MatrixReferenceImageResolver matrix) {
        return (userId, resourceId) ->
                resourceId != null && resourceId.startsWith("mxc://")
                        ? matrix.resolve(userId, resourceId)
                        : chat.resolve(userId, resourceId);
    }
}