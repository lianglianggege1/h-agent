package com.h.capabilities.generation.interfaces.tool;

import com.h.capabilities.generation.application.service.VideoV2Service;
import com.h.capabilities.generation.domain.model.VideoV2Spec;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 团队视频生成：提交 H3 任务后立即返回 taskId，成果由本服务轮询 job
 * 经 Matrix 投影自动发进房间（owner/actor 隔离，见 generation_tasks）。
 */
public class TeamVideoGenerationTool {

    private final VideoV2Service videos;
    private final ObjectMapper json;

    public TeamVideoGenerationTool(VideoV2Service videos, ObjectMapper json) {
        this.videos = videos;
        this.json = json;
    }

    public String submit(
            Long userId,
            String roomId,
            String prompt,
            String referencesJson,
            String model,
            Integer durationSeconds,
            String resolution,
            String ratio) {
        TeamImageGenerationTool.requireRoom(roomId);
        List<VideoV2Spec.Input> inputs = new ArrayList<>();
        if (referencesJson != null && !referencesJson.isBlank()) {
            var references = json.readTree(referencesJson);
            if (!references.isArray()) {
                throw new IllegalArgumentException("referencesJson必须是素材数组；纯文生视频请省略或传[]");
            }
            for (var node : references) {
                inputs.add(new VideoV2Spec.Input(
                        node.path("resourceId").asText(), node.path("role").asText()));
            }
        }
        var result = videos.submit(
                userId, roomId, "generation", prompt, model, durationSeconds, resolution, ratio, inputs);
        return json.writeValueAsString(Map.of(
                "taskId", result.taskId(),
                "message", result.providerTaskId() == null
                        ? "提交结果待确认，请勿自动重复提交"
                        : "视频任务已提交，完成后自动发送到聊天室；可用 query_video_task 查询进度"));
    }

    public String query(Long userId, String taskId) {
        return json.writeValueAsString(videos.query(userId, taskId));
    }
}