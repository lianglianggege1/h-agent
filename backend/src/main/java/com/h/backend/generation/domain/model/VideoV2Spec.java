package com.h.backend.generation.domain.model;

import java.util.List;
import java.util.Set;

/** Durable semantic input: resource identities and the final prompt, never embedded media bytes. */
public record VideoV2Spec(
        String taskType,
        String prompt,
        String model,
        int durationSeconds,
        String resolution,
        String ratio,
        List<Input> inputs,
        String sourceTaskId,
        boolean generateAfterEnhancement)
        implements VideoGenerationSpec {
    public VideoV2Spec(
            String type,
            String prompt,
            String model,
            int duration,
            String resolution,
            String ratio,
            List<Input> inputs,
            String sourceTaskId) {
        this(type, prompt, model, duration, resolution, ratio, inputs, sourceTaskId, false);
    }

    public record Input(String resourceId, String role) {
        public Input {
            if (resourceId == null
                    || resourceId.isBlank()
                    || role == null
                    || !Set.of(
                                    "first_frame",
                                    "last_frame",
                                    "reference_image",
                                    "reference_video",
                                    "reference_audio",
                                    "base_video")
                            .contains(role)) throw new IllegalArgumentException("素材必须指定资源ID及合法用途");
        }
    }

    public VideoV2Spec {
        if (generateAfterEnhancement && !"h3_context_ir".equals(taskType))
            throw new IllegalArgumentException("只有提示词增强任务可以配置后续生成");
        if (!Set.of("generation", "h3_context_ir", "regeneration").contains(taskType))
            throw new IllegalArgumentException("未知视频任务类型");
        if (prompt == null
                || prompt.isBlank()
                || prompt.length() > ("regeneration".equals(taskType) ? 40000 : 7000))
            throw new IllegalArgumentException("提示词为空或超过接口字符限制");
        if (!Set.of("MiniMax-H3", "MiniMax-H3-Highspeed", "MiniMax-H3-Lite", "MiniMax-H3-Max")
                .contains(model)) throw new IllegalArgumentException("不支持的视频模型");
        if (durationSeconds < ("MiniMax-H3-Max".equals(model) ? 5 : 4) || durationSeconds > 15)
            throw new IllegalArgumentException("该模型不支持请求的时长，请明确选择4至15秒（Max最短5秒）");
        if (!"generation".equals(taskType) && !"MiniMax-H3".equals(model))
            throw new IllegalArgumentException("增强提示词和再生成仅支持MiniMax-H3");
        Set<String> resolutions =
                "MiniMax-H3".equals(model)
                        ? Set.of("768P", "2K")
                        : "MiniMax-H3-Highspeed".equals(model)
                                ? Set.of("768P")
                                : Set.of("480P", "768P");
        if (!resolutions.contains(resolution))
            throw new IllegalArgumentException("模型不支持该分辨率，不会自动降级");
        if (!Set.of("adaptive", "16:9", "9:16", "1:1", "4:3", "3:4", "21:9").contains(ratio))
            throw new IllegalArgumentException("不支持的视频比例");
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        boolean frames = inputs.stream().anyMatch(i -> i.role().endsWith("frame"));
        boolean refs = inputs.stream().anyMatch(i -> i.role().startsWith("reference_"));
        if (frames && refs) throw new IllegalArgumentException("首尾帧与参考素材不能混用，请用户选择模式");
        for (String role :
                List.of(
                        "first_frame",
                        "last_frame",
                        "reference_image",
                        "reference_video",
                        "reference_audio",
                        "base_video")) {
            int max =
                    role.equals("reference_image")
                            ? 9
                            : role.equals("reference_video") || role.equals("reference_audio")
                                    ? 3
                                    : 1;
            if (inputs.stream().filter(i -> i.role().equals(role)).count() > max)
                throw new IllegalArgumentException("超过素材数量限制：" + role);
        }
        if (frames) ratio = "adaptive";
        if (!frames
                && (inputs.isEmpty() || "MiniMax-H3-Lite".equals(model))
                && "adaptive".equals(ratio))
            throw new IllegalArgumentException("文生视频和Lite参考模式需要明确比例");
        if (!"regeneration".equals(taskType)
                && inputs.stream().anyMatch(i -> i.role().equals("base_video")))
            throw new IllegalArgumentException("base_video仅用于再生成");
        if ("regeneration".equals(taskType)
                && (!"2K".equals(resolution)
                        || ((sourceTaskId != null)
                                == inputs.stream().anyMatch(i -> i.role().equals("base_video")))))
            throw new IllegalArgumentException("再生成必须指定source_task_id或一个base_video，且目标为2K");
    }

    public static VideoV2Spec defaults(
            String type,
            String prompt,
            String model,
            Integer seconds,
            String resolution,
            String ratio,
            List<Input> inputs) {
        List<Input> safe = inputs == null ? List.of() : inputs;
        String chosen = model == null || model.isBlank() ? "MiniMax-H3" : model;
        return new VideoV2Spec(
                type,
                prompt,
                chosen,
                seconds == null ? 6 : seconds,
                resolution == null || resolution.isBlank() ? "768P" : resolution,
                ratio == null || ratio.isBlank()
                        ? (safe.isEmpty() || chosen.equals("MiniMax-H3-Lite") ? "16:9" : "adaptive")
                        : ratio,
                safe,
                null);
    }

    public GenerationType generationType() {
        return GenerationType.VIDEO_V2;
    }
}
