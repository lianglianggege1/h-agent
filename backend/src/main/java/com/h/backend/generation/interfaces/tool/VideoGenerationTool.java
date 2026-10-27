package com.h.backend.generation.interfaces.tool;

import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;

import org.springframework.stereotype.Component;

@Component
public class VideoGenerationTool {
    private final VideoToolActions actions;
    private final ChatMemoryIdFactory memoryIds;

    public VideoGenerationTool(VideoToolActions actions, ChatMemoryIdFactory memoryIds) {
        this.actions = actions;
        this.memoryIds = memoryIds;
    }

    @Tool(
            name = "generate_video",
            value =
                    "创建H3文生/图生/多模态视频，或增强视频提示词。默认MiniMax-H3、6秒、768P；仅用户明确指定时换模型。operation为generation、h3_context_ir、enhance_then_generate；h3_context_ir仅增强不生成，只有用户同时明确要求优化并生成时才用enhance_then_generate。referencesJson为[{\"resourceId\":\"ID\",\"role\":\"first_frame/last_frame/reference_image/reference_video/reference_audio\"}]；未传时使用当前消息素材，单图默认首帧、多图默认参考，首尾帧不得混用参考模式。视频4至15秒（Max最短5秒），2K仅H3。静态图片编辑不要用此工具。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String generateVideo(
            @ToolMemoryId String memoryId,
            String prompt,
            @P(value = "可选；遵循工具说明的默认值，不擅自更改用户明确参数", required = false) String referencesJson,
            @P(value = "可选；遵循工具说明的默认值，不擅自更改用户明确参数", required = false) String operation,
            @P(value = "可选；遵循工具说明的默认值，不擅自更改用户明确参数", required = false) String model,
            @P(value = "可选；遵循工具说明的默认值，不擅自更改用户明确参数", required = false) Integer durationSeconds,
            @P(value = "可选；遵循工具说明的默认值，不擅自更改用户明确参数", required = false) String resolution,
            @P(value = "可选；遵循工具说明的默认值，不擅自更改用户明确参数", required = false) String ratio) {
        var context = memoryIds.parse(memoryId);
        return actions.submit(
                context.userId(),
                context.sessionId(),
                memoryId,
                prompt,
                referencesJson,
                operation == null ? "generation" : operation,
                model,
                durationSeconds,
                resolution,
                ratio);
    }

    @Tool(
            name = "query_video_task",
            value = "查询本应用当前用户的视频/增强提示词任务。用本地taskId，不是供应商ID；已成功IR从prompt读取结果。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String queryVideoTask(@ToolMemoryId String memoryId, String taskId) {
        var context = memoryIds.parse(memoryId);
        return actions.query(context.userId(), taskId);
    }

    @Tool(
            name = "list_video_tasks",
            value = "列出当前用户在本应用创建的视频任务，不得展示共享供应商账号的其他用户任务。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String listVideoTasks(
            @ToolMemoryId String memoryId,
            @P(value = "页码，从1开始", required = false) Integer page,
            @P(value = "可选；遵循工具说明的默认值，不擅自更改用户明确参数", required = false) Integer limit,
            @P(value = "可选；遵循工具说明的默认值，不擅自更改用户明确参数", required = false) String status,
            @P(value = "可选；遵循工具说明的默认值，不擅自更改用户明确参数", required = false) String model,
            @P(value = "可选；遵循工具说明的默认值，不擅自更改用户明确参数", required = false) String taskType) {
        var context = memoryIds.parse(memoryId);
        return actions.list(context.userId(), limit, page, status, model, taskType);
    }

    @Tool(
            name = "cancel_video_task",
            value = "仅当用户明确取消视频任务时调用。只有排队任务可取消，运行中无法取消；停止聊天不代表取消视频。响应action以供应商真实返回为准。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String cancelVideoTask(@ToolMemoryId String memoryId, String taskId) {
        var context = memoryIds.parse(memoryId);
        return actions.remove(context.userId(), taskId, false);
    }

    @Tool(
            name = "delete_video_task_record",
            value = "仅当用户明确要求删除供应商任务记录时调用；仅成功/失败记录可删除，不删除本地已保存视频。不能用于取消视频。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String deleteVideoTaskRecord(@ToolMemoryId String memoryId, String taskId) {
        var context = memoryIds.parse(memoryId);
        return actions.remove(context.userId(), taskId, true);
    }

    @Tool(
            name = "regenerate_video2_k",
            value = "将本应用已完成的H3 768P视频重新生成为2K。不是任意视频超分或剪辑；要求本地源任务ID。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String regenerateVideo2K(@ToolMemoryId String memoryId, String sourceTaskId) {
        var context = memoryIds.parse(memoryId);
        return actions.regenerate(context.userId(), context.sessionId(), memoryId, sourceTaskId);
    }

    @Tool(
            name = "list_video_inputs",
            value = "列出当前消息的图片、视频、音频资源ID，供生成或提示词增强使用；首尾帧角色必须遵从用户意图。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String listVideoInputs(@ToolMemoryId String memoryId) {
        var context = memoryIds.parse(memoryId);
        return actions.inputs(context.userId(), context.sessionId());
    }
}
