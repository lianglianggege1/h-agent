package com.h.backend.generation.infrastructure.provider.minimax;

import com.h.capabilities.generation.infrastructure.provider.minimax.FfprobeMediaValidator;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.capabilities.chat.infrastructure.storage.ResourceRange;
import com.h.capabilities.chat.infrastructure.storage.ResourceStorage;
import com.h.capabilities.generation.application.port.out.VideoInputResolver;
import com.h.capabilities.generation.domain.model.VideoV2Spec;

import org.springframework.stereotype.Component;

import java.util.*;

/** Only owned resource bytes can enter provider content. ffprobe validates real stream metadata. */
@Component
public class StoredVideoInputResolver implements VideoInputResolver {
    private final ChatMessageResourceMapper resources;
    private final ResourceStorage storage;
    private final FfprobeMediaValidator validator;

    public StoredVideoInputResolver(
            ChatMessageResourceMapper resources,
            ResourceStorage storage,
            FfprobeMediaValidator validator) {
        this.resources = resources;
        this.storage = storage;
        this.validator = validator;
    }

    public List<Map<String, Object>> resolve(Long user, VideoV2Spec spec) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", spec.prompt()));
        long encodedMediaBytes = 0;
        double videoSeconds = 0, audioSeconds = 0;
        for (var input : spec.inputs()) {
            var resource = resources.selectByResourceId(input.resourceId());
            if (resource == null || !user.equals(resource.getUserId()))
                throw new IllegalArgumentException("引用素材不存在或不属于当前用户");
            String kind =
                    input.role().endsWith("frame") || input.role().equals("reference_image")
                            ? "image"
                            : input.role().equals("reference_audio") ? "audio" : "video";
            byte[] bytes;
            var stored = storage.open(resource.getStorageKey(), ResourceRange.fullRead());
            try (var stream = stored.inputStream()) {
                bytes = stream.readNBytes(50 * 1024 * 1024 + 1);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读取引用素材失败", ex);
            }
            encodedMediaBytes += 4L * ((bytes.length + 2L) / 3L);
            if (encodedMediaBytes > 64L * 1024 * 1024)
                throw new IllegalArgumentException("素材编码后超过64MB请求限制，请减少素材大小");
            var facts = validator.validate(bytes, kind, input.role(), resource.getMimeType());
            if (kind.equals("audio")) audioSeconds += facts.seconds();
            else if (kind.equals("video") && !input.role().equals("base_video"))
                videoSeconds += facts.seconds();
            String mime = Objects.toString(resource.getMimeType(), "").toLowerCase(Locale.ROOT);
            String type = kind + "_url";
            content.add(
                    Map.of(
                            "type",
                            type,
                            "role",
                            input.role(),
                            type,
                            Map.of(
                                    "url",
                                    "data:"
                                            + mime
                                            + ";base64,"
                                            + Base64.getEncoder().encodeToString(bytes))));
        }
        if (videoSeconds > 15 || audioSeconds > 15)
            throw new IllegalArgumentException("参考视频和参考音频各自总时长不能超过15秒");
        return List.copyOf(content);
    }
}