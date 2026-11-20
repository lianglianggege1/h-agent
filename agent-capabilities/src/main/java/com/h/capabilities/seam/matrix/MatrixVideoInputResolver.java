package com.h.capabilities.seam.matrix;

import com.h.capabilities.generation.application.port.out.VideoInputResolver;
import com.h.capabilities.generation.domain.model.VideoV2Spec;
import com.h.capabilities.generation.infrastructure.provider.minimax.FfprobeMediaValidator;
import com.h.matrixteam.MatrixClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** 团队视频素材解析：mxc:// 下载为字节，嗅探 MIME 后走与产品一致的 H3 规格校验。 */
public class MatrixVideoInputResolver implements VideoInputResolver {

    private final List<MatrixClient> clients;
    private final FfprobeMediaValidator validator;

    public MatrixVideoInputResolver(List<MatrixClient> clients, FfprobeMediaValidator validator) {
        this.clients = clients;
        this.validator = validator;
    }

    @Override
    public List<Map<String, Object>> resolve(Long userId, VideoV2Spec spec) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", spec.prompt()));
        long encodedMediaBytes = 0;
        double videoSeconds = 0, audioSeconds = 0;
        MatrixClient client = MatrixClients.forActor(clients, TeamActorContext.get());
        for (VideoV2Spec.Input input : spec.inputs()) {
            if (!input.resourceId().startsWith("mxc://")) {
                throw new IllegalArgumentException("团队视频素材必须是房间媒体的 mxc:// 地址：" + input.resourceId());
            }
            byte[] bytes = client.download(input.resourceId());
            String mime = MatrixMediaTypes.sniff(bytes);
            if (mime == null) {
                throw new IllegalArgumentException("无法识别素材格式，H3 仅支持常见图片/音视频：" + input.resourceId());
            }
            String kind =
                    input.role().endsWith("frame") || input.role().equals("reference_image")
                            ? "image"
                            : input.role().equals("reference_audio") ? "audio" : "video";
            encodedMediaBytes += 4L * ((bytes.length + 2L) / 3L);
            if (encodedMediaBytes > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("素材编码后超过64MB请求限制，请减少素材大小");
            }
            var facts = validator.validate(bytes, kind, input.role(), mime);
            if (kind.equals("audio")) audioSeconds += facts.seconds();
            else if (kind.equals("video") && !input.role().equals("base_video"))
                videoSeconds += facts.seconds();
            String type = kind + "_url";
            content.add(
                    Map.of(
                            "type", type,
                            "role", input.role(),
                            type, Map.of("url", "data:" + mime + ";base64,"
                                    + Base64.getEncoder().encodeToString(bytes))));
        }
        if (videoSeconds > 15 || audioSeconds > 15) {
            throw new IllegalArgumentException("参考视频和参考音频各自总时长不能超过15秒");
        }
        return List.copyOf(content);
    }
}