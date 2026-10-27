package com.h.backend.generation.interfaces.tool;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.generation.application.service.VideoV2Service;
import com.h.backend.generation.domain.model.VideoV2Spec;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Component
public class VideoToolActions {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(VideoToolActions.class);
    private final VideoV2Service videos;
    private final ChatSessionService sessions;
    private final ChatStreamEventBridge bridge;
    private final ObjectMapper json;

    public VideoToolActions(
            VideoV2Service videos,
            ChatSessionService sessions,
            ChatStreamEventBridge bridge,
            ObjectMapper json) {
        this.videos = videos;
        this.sessions = sessions;
        this.bridge = bridge;
        this.json = json;
    }

    public String submit(
            Long user,
            String session,
            String memoryId,
            String prompt,
            String referencesJson,
            String operation,
            String model,
            Integer duration,
            String resolution,
            String ratio) {
        if (!Set.of("generation", "h3_context_ir", "enhance_then_generate").contains(operation))
            throw new IllegalArgumentException(
                    "操作只能为generation、h3_context_ir或enhance_then_generate");
        List<VideoV2Spec.Input> inputs;
        if (referencesJson == null || referencesJson.isBlank()) {
            var current = currentInputs(user, session);
            boolean single =
                    current.size() == 1 && current.getFirst().mimeType().startsWith("image/");
            inputs =
                    current.stream()
                            .map(
                                    r ->
                                            new VideoV2Spec.Input(
                                                    r.id(),
                                                    single
                                                            ? "first_frame"
                                                            : r.mimeType().startsWith("image/")
                                                                    ? "reference_image"
                                                                    : r.mimeType()
                                                                                    .startsWith(
                                                                                            "video/")
                                                                            ? "reference_video"
                                                                            : "reference_audio"))
                            .toList();
        } else {
            inputs = new ArrayList<>();
            var references = json.readTree(referencesJson);
            if (!references.isArray())
                throw new IllegalArgumentException("referencesJson必须是素材数组；纯文本请求请显式传[]");
            for (var node : references)
                inputs.add(
                        new VideoV2Spec.Input(
                                node.path("resourceId").asText(), node.path("role").asText()));
        }
        var result =
                videos.submit(
                        user,
                        session,
                        operation.equals("enhance_then_generate") ? "h3_context_ir" : operation,
                        prompt,
                        model,
                        duration,
                        resolution,
                        ratio,
                        inputs,
                        operation.equals("enhance_then_generate"));
        publish(user, session, memoryId, result.chatMessageId());
        return json.writeValueAsString(
                Map.of(
                        "taskId",
                        result.taskId(),
                        "message",
                        result.providerTaskId() == null
                                ? "提交结果待确认，请勿自动重复提交"
                                : operation.equals("h3_context_ir")
                                        ? "提示词增强任务已提交；只返回提示词，不会自动生成视频"
                                        : "视频任务已提交，完成后自动回填当前聊天"));
    }

    public String regenerate(Long user, String session, String memoryId, String id) {
        var result = videos.regenerate(user, session, id);
        publish(user, session, memoryId, result.chatMessageId());
        return json.writeValueAsString(
                Map.of(
                        "taskId",
                        result.taskId(),
                        "message",
                        result.providerTaskId() == null ? "提交结果待确认，请勿自动重复提交" : "2K再生成任务已提交"));
    }

    public String query(Long user, String id) {
        return json.writeValueAsString(videos.query(user, id));
    }

    public String list(
            Long user, Integer limit, Integer page, String status, String model, String type) {
        return json.writeValueAsString(videos.list(user, limit, page, status, model, type));
    }

    public String remove(Long user, String id, boolean delete) {
        return json.writeValueAsString(videos.remove(user, id, delete));
    }

    public String inputs(Long user, String session) {
        return json.writeValueAsString(
                currentInputs(user, session).stream()
                        .map(
                                r ->
                                        Map.of(
                                                "resourceId",
                                                r.id(),
                                                "mimeType",
                                                r.mimeType(),
                                                "name",
                                                r.fileName()))
                        .toList());
    }

    private List<ChatMessageResourceDto> currentInputs(Long user, String session) {
        var page = sessions.getSessionMessages(user, session, 100, null);
        if (page == null) return List.of();
        var message =
                page.messages().stream()
                        .filter(m -> "user".equals(m.role()))
                        .reduce((a, b) -> b)
                        .orElse(null);
        return message == null || message.resources() == null
                ? List.of()
                : message.resources().stream()
                        .filter(
                                r ->
                                        r.mimeType().startsWith("image/")
                                                || r.mimeType().startsWith("video/")
                                                || r.mimeType().startsWith("audio/"))
                        .toList();
    }

    private void publish(Long user, String session, String memoryId, Long message) {
        if (memoryId != null && message != null) {
            try {
                bridge.publishMessage(memoryId, sessions.getOwnedMessage(user, session, message));
            } catch (RuntimeException ex) {
                // The durable task already exists. A disconnected UI must not turn acceptance into
                // a tool failure that encourages the agent to create another paid task.
                log.warn(
                        "Video task accepted but live message projection failed; history will"
                                + " recover message {}",
                        message);
            }
        }
    }
}
