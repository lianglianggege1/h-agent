package com.h.backend.generation.infrastructure.provider.minimax;

import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.infrastructure.storage.ResourceRange;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import com.h.backend.generation.application.port.out.VideoInputResolver;
import com.h.backend.generation.domain.model.VideoV2Spec;
import com.h.backend.generation.infrastructure.config.GenerationProperties;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Only owned resource bytes can enter provider content. ffprobe validates real stream metadata. */
@Component
public class StoredVideoInputResolver implements VideoInputResolver {
    private final ChatMessageResourceMapper resources;
    private final ResourceStorage storage;
    private final GenerationProperties properties;
    private final ObjectMapper json;

    public StoredVideoInputResolver(
            ChatMessageResourceMapper resources,
            ResourceStorage storage,
            GenerationProperties properties,
            ObjectMapper json) {
        this.resources = resources;
        this.storage = storage;
        this.properties = properties;
        this.json = json;
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
            String mime = Objects.toString(resource.getMimeType(), "").toLowerCase(Locale.ROOT);
            Set<String> formats =
                    switch (kind) {
                        case "image" ->
                                Set.of(
                                        "image/jpeg",
                                        "image/png",
                                        "image/webp",
                                        "image/heic",
                                        "image/heif");
                        case "audio" ->
                                Set.of("audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3");
                        default -> Set.of("video/mp4", "video/quicktime");
                    };
            if (!formats.contains(mime))
                throw new IllegalArgumentException("素材用途与格式不匹配，或格式不受H3支持：" + input.role());
            int limit = (kind.equals("image") ? 30 : kind.equals("audio") ? 15 : 50) * 1024 * 1024;
            byte[] bytes;
            var stored = storage.open(resource.getStorageKey(), ResourceRange.fullRead());
            try (var stream = stored.inputStream()) {
                if (stored.totalSize() > limit)
                    throw new IllegalArgumentException("引用素材超过H3文件大小限制");
                bytes = stream.readNBytes(limit + 1);
                if (bytes.length > limit) throw new IllegalArgumentException("引用素材超过H3文件大小限制");
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读取引用素材失败", ex);
            }
            encodedMediaBytes += 4L * ((bytes.length + 2L) / 3L);
            if (encodedMediaBytes > 64L * 1024 * 1024)
                throw new IllegalArgumentException("素材编码后超过64MB请求限制，请减少素材大小");
            JsonNode probe = probe(bytes);
            JsonNode video = null;
            boolean hasAudio = false;
            for (JsonNode stream : probe.path("streams")) {
                String codec = stream.path("codec_name").asText();
                if (stream.path("codec_type").asText().equals("video")) video = stream;
                if (stream.path("codec_type").asText().equals("audio")) {
                    hasAudio = true;
                    if (kind.equals("video") && !Set.of("aac", "mp3").contains(codec))
                        throw new IllegalArgumentException("参考视频音轨仅支持AAC或MP3");
                }
            }
            if (!kind.equals("audio")) {
                if (video == null) throw new IllegalArgumentException("无法识别图片/视频尺寸");
                int width = video.path("width").asInt(), height = video.path("height").asInt();
                double ratio = height == 0 ? 0 : (double) width / height;
                if (width < 256
                        || height < 256
                        || width > 5760
                        || height > 5760
                        || ratio < 0.4
                        || ratio > 2.5)
                    throw new IllegalArgumentException("素材宽高须为256至5760且比例为0.4至2.5");
                if (kind.equals("video")) {
                    double fps = fraction(video.path("r_frame_rate").asText("0"));
                    if (!Set.of("h264", "hevc").contains(video.path("codec_name").asText())
                            || !Double.isFinite(fps)
                            || fps < 23.976
                            || fps > 60)
                        throw new IllegalArgumentException("参考视频需H.264/H.265编码且帧率为23.976至60");
                    if (input.role().equals("base_video")) {
                        int frames = video.path("nb_frames").asInt();
                        long pixels = (long) width * height;
                        if (!hasAudio
                                || Math.abs(fps - 24) > 0.001
                                || width % 32 != 0
                                || height % 32 != 0
                                || pixels < 589824
                                || pixels > 1032192
                                || frames < 107
                                || frames > 362
                                || (frames - 107) % 17 != 0)
                            throw new IllegalArgumentException(
                                    "源视频不符合H3 768P再生成规格：需含音轨、24fps及文档规定的尺寸和帧数");
                    }
                }
            }
            if (!kind.equals("image")) {
                double seconds = probe.path("format").path("duration").asDouble(-1);
                if (!input.role().equals("base_video") && (seconds < 2 || seconds > 15))
                    throw new IllegalArgumentException("音视频素材单段时长需为2至15秒");
                if (kind.equals("audio")) {
                    if (!hasAudio) throw new IllegalArgumentException("素材没有音频流");
                    audioSeconds += seconds;
                } else if (!input.role().equals("base_video")) videoSeconds += seconds;
            }
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

    private JsonNode probe(byte[] bytes) {
        Path file = null, output = null;
        Process process = null;
        try {
            file = Files.createTempFile("h-agent-video-input-", ".media");
            output = Files.createTempFile("h-agent-media-probe-", ".json");
            Files.write(file, bytes);
            process =
                    new ProcessBuilder(
                                    properties.getMinimax().getFfprobePath(),
                                    "-v",
                                    "error",
                                    "-show_entries",
                                    "stream=codec_type,codec_name,width,height,r_frame_rate,nb_frames:format=duration",
                                    "-of",
                                    "json",
                                    file.toString())
                            .redirectOutput(output.toFile())
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IllegalArgumentException("无法校验媒体素材，请检查文件格式");
            return json.readTree(Files.readString(output));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("素材校验已中断", ex);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(
                    "媒体校验不可用，请安装ffprobe或配置generation.minimax.ffprobe-path", ex);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            try {
                if (file != null) Files.deleteIfExists(file);
                if (output != null) Files.deleteIfExists(output);
            } catch (java.io.IOException ignored) {
            }
        }
    }

    private static double fraction(String value) {
        String[] parts = value.split("/");
        return parts.length == 2
                ? Double.parseDouble(parts[0]) / Double.parseDouble(parts[1])
                : Double.parseDouble(value);
    }
}
