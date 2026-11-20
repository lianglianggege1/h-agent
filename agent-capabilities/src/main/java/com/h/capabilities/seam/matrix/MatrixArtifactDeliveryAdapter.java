package com.h.capabilities.seam.matrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.h.capabilities.seam.DeliveredArtifact;
import com.h.capabilities.seam.DeliveryResult;
import com.h.capabilities.seam.port.ArtifactDeliveryPort;
import com.h.capabilities.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.capabilities.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

/**
 * 团队交付端口：MinIO 持久化（Coordinator 事务 + 补偿语义不变）后上传 Matrix 媒体并发送到房间。
 * team 场景 resourceId 约定为 mxc:// 地址（历史/素材端口同约定），投影句柄为 null（Matrix 消息即投影）。
 */
public class MatrixArtifactDeliveryAdapter implements ArtifactDeliveryPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ResourceWriteCoordinator coordinator;
    private final List<MatrixClient> clients;

    public MatrixArtifactDeliveryAdapter(ResourceWriteCoordinator coordinator, List<MatrixClient> clients) {
        this.coordinator = coordinator;
        this.clients = clients;
    }

    @Override
    public DeliveryResult deliver(
            long userId,
            String scopeId,
            ResourceSaveCommand command,
            String resourceType,
            String fileName,
            String mimeType,
            String text,
            Consumer<DeliveredArtifact> onAttached
    ) {
        byte[] bytes = materialize(command);
        ResourceSaveCommand byteCommand = new ResourceSaveCommand(
                resourceType, bytes, mimeType, command.extension(), command.width(), command.height());
        DeliveredArtifact[] artifact = new DeliveredArtifact[1];
        coordinator.saveAndAttach(byteCommand, stored -> {
            String mxc = uploadAndSend(scopeId, resourceType, fileName, mimeType, bytes);
            artifact[0] = new DeliveredArtifact(
                    mxc, resourceType, mxc, mxc, fileName, mimeType,
                    stored.fileSize(), stored.width(), stored.height(),
                    stored.storageType(), stored.storageKey());
            onAttached.accept(artifact[0]);
            return artifact[0];
        });
        return new DeliveryResult(artifact[0], null);
    }

    private String uploadAndSend(String roomId, String resourceType, String fileName, String mimeType, byte[] bytes) {
        ObjectNode info = JSON.createObjectNode();
        info.put("mimetype", mimeType);
        info.put("size", bytes.length);
        String msgtype = msgtypeFor(resourceType);
        IllegalStateException last = null;
        for (MatrixClient client : orderedClients()) {
            try {
                String mxc = client.upload(fileName, mimeType, bytes);
                client.sendMedia(roomId, msgtype, mxc, fileName, info, null, null, null);
                return mxc;
            } catch (IllegalStateException failure) {
                last = failure;
            }
        }
        throw last == null ? new IllegalStateException("没有可用的 Matrix 客户端") : last;
    }

    /** 本轮执行者账号优先（成果归属实际 agent），其余客户端按房间成员资格兜底。 */
    private List<MatrixClient> orderedClients() {
        String actor = TeamActorContext.get();
        if (actor == null) {
            return clients;
        }
        return clients.stream()
                .sorted(java.util.Comparator.comparing(client -> !actor.equals(client.userId())))
                .toList();
    }

    private static byte[] materialize(ResourceSaveCommand command) {
        if (command.content() != null) {
            return command.content();
        }
        long limit = command.effectiveMaxBytes();
        try (InputStream stream = command.openContentStream()) {
            byte[] bytes = stream.readNBytes((int) Math.min(limit, Integer.MAX_VALUE - 1));
            if (bytes.length >= limit) {
                throw new IllegalArgumentException("文件超出大小限制");
            }
            return bytes;
        } catch (IOException failure) {
            throw new IllegalStateException("读取交付文件失败", failure);
        }
    }

    private static String msgtypeFor(String resourceType) {
        return switch (resourceType == null ? "" : resourceType) {
            case "IMAGE" -> TeamProtocol.MSG_IMAGE;
            case "AUDIO" -> TeamProtocol.MSG_AUDIO;
            case "VIDEO" -> TeamProtocol.MSG_VIDEO;
            default -> TeamProtocol.MSG_FILE;
        };
    }
}