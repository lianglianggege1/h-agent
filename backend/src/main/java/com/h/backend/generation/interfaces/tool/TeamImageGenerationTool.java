package com.h.backend.generation.interfaces.tool;

import com.h.backend.capability.matrix.MatrixClients;
import com.h.backend.capability.matrix.MatrixMediaTypes;
import com.h.backend.capability.matrix.TeamActorContext;
import com.h.backend.chat.infrastructure.config.ImageGenerationProperties;
import com.h.backend.chat.infrastructure.image.MiniMaxImageClient;
import com.h.backend.chat.infrastructure.image.MiniMaxImageGenerationRequest;
import com.h.backend.chat.infrastructure.image.MiniMaxImageGenerationResult;
import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamProtocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 团队图片生成：同步调用 MiniMax 图片接口，成果逐张上传 mxc 并以 m.image 发进房间。
 * 参考图只接受房间媒体 mxc:// 地址（subject_reference 主体参考）。
 */
public class TeamImageGenerationTool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final tools.jackson.databind.ObjectMapper RESULT = new tools.jackson.databind.ObjectMapper();
    private static final int MAX_REFERENCE_BYTES = 10 * 1024 * 1024;

    private final MiniMaxImageClient images;
    private final ImageGenerationProperties properties;
    private final List<MatrixClient> clients;

    public TeamImageGenerationTool(
            MiniMaxImageClient images,
            ImageGenerationProperties properties,
            List<MatrixClient> clients) {
        this.images = images;
        this.properties = properties;
        this.clients = clients;
    }

    public String generate(
            Long userId, String roomId, String prompt, String referenceMxc, String aspectRatio) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("图片提示词不能为空");
        }
        requireRoom(roomId);
        ImageGenerationProperties.MiniMax minimax = properties.minimaxOrDefault();
        MatrixClient client = MatrixClients.forActor(clients, TeamActorContext.get());
        MiniMaxImageGenerationRequest.SubjectReference reference = null;
        if (referenceMxc != null && !referenceMxc.isBlank()) {
            if (!referenceMxc.startsWith("mxc://")) {
                throw new IllegalArgumentException("参考图必须是房间媒体的 mxc:// 地址");
            }
            byte[] bytes = client.download(referenceMxc);
            String mime = MatrixMediaTypes.sniff(bytes);
            if (mime == null || !mime.startsWith("image/")) {
                throw new IllegalArgumentException("参考素材必须是图片：" + referenceMxc);
            }
            if (bytes.length > MAX_REFERENCE_BYTES) {
                throw new IllegalArgumentException("参考图片超过10MB限制");
            }
            reference = new MiniMaxImageGenerationRequest.SubjectReference(
                    "character", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
        }
        MiniMaxImageGenerationResult result = images.generate(
                new MiniMaxImageGenerationRequest(
                        minimax.model(),
                        prompt.trim(),
                        aspectRatio == null || aspectRatio.isBlank() ? minimax.aspectRatio() : aspectRatio,
                        "url",
                        minimax.n(),
                        minimax.promptOptimizer(),
                        reference));
        List<Map<String, Object>> delivered = new ArrayList<>();
        for (MiniMaxImageGenerationResult.GeneratedImage image : result.images()) {
            byte[] bytes = image.imageBytes();
            String mime = image.mimeType() == null ? MatrixMediaTypes.sniff(bytes) : image.mimeType();
            if (mime == null) mime = "image/jpeg";
            String fileName = "image-" + UUID.randomUUID().toString().substring(0, 8)
                    + MatrixMediaTypes.extension(mime);
            String mxc = client.upload(fileName, mime, bytes);
            ObjectNode info = JSON.createObjectNode();
            info.put("mimetype", mime);
            info.put("size", bytes.length);
            if (image.width() != null) info.put("w", image.width());
            if (image.height() != null) info.put("h", image.height());
            client.sendMedia(roomId, TeamProtocol.MSG_IMAGE, mxc, fileName, info, null, null, null);
            delivered.add(Map.of("url", mxc, "fileName", fileName));
        }
        return RESULT.writeValueAsString(Map.of(
                "message", "已生成" + delivered.size() + "张图片并发送到聊天室",
                "images", delivered));
    }

    static void requireRoom(String roomId) {
        if (roomId == null || !roomId.startsWith("!")) {
            throw new IllegalStateException("缺少团队房间上下文");
        }
    }
}