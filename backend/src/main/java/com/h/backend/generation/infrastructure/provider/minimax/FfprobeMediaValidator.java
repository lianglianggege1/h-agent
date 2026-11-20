package com.h.backend.generation.infrastructure.provider.minimax;

import com.h.backend.generation.infrastructure.config.GenerationProperties;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** H3 素材规格校验（格式/大小/尺寸/编码/时长），产品与团队输入解析共用。 */
@Component
public class FfprobeMediaValidator {

    public record Facts(double seconds, boolean hasAudio) {
    }

    private final GenerationProperties properties;
    private final ObjectMapper json;

    public FfprobeMediaValidator(GenerationProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public Facts validate(byte[] bytes, String kind, String role, String mimeType) {
        String mime = Objects.toString(mimeType, "").toLowerCase(Locale.ROOT);
        Set<String> formats =
                switch (kind) {
                    case "image" ->
                            Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif");
                    case "audio" -> Set.of("audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3");
                    default -> Set.of("video/mp4", "video/quicktime");
                };
        if (!formats.contains(mime))
            throw new IllegalArgumentException("素材用途与格式不匹配，或格式不受H3支持：" + role);
        int limit = (kind.equals("image") ? 30 : kind.equals("audio") ? 15 : 50) * 1024 * 1024;
        if (bytes.length > limit) throw new IllegalArgumentException("引用素材超过H3文件大小限制");
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
            if (width < 256 || height < 256 || width > 5760 || height > 5760 || ratio < 0.4 || ratio > 2.5)
                throw new IllegalArgumentException("素材宽高须为256至5760且比例为0.4至2.5");
            if (kind.equals("video")) {
                double fps = fraction(video.path("r_frame_rate").asText("0"));
                if (!Set.of("h264", "hevc").contains(video.path("codec_name").asText())
                        || !Double.isFinite(fps)
                        || fps < 23.976
                        || fps > 60)
                    throw new IllegalArgumentException("参考视频需H.264/H.265编码且帧率为23.976至60");
                if (role.equals("base_video")) {
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
        double seconds = 0;
        if (!kind.equals("image")) {
            seconds = probe.path("format").path("duration").asDouble(-1);
            if (!role.equals("base_video") && (seconds < 2 || seconds > 15))
                throw new IllegalArgumentException("音视频素材单段时长需为2至15秒");
            if (kind.equals("audio") && !hasAudio) throw new IllegalArgumentException("素材没有音频流");
        }
        return new Facts(seconds, hasAudio);
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