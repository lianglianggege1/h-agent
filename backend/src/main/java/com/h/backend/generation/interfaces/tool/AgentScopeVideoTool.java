package com.h.backend.generation.interfaces.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import org.springframework.stereotype.Component;

@Component
public class AgentScopeVideoTool {
    private final VideoToolActions actions;

    public AgentScopeVideoTool(VideoToolActions actions) {
        this.actions = actions;
    }

    @Tool(
            name = "generate_video",
            description =
                    "创建H3文生/图生/多模态视频，或增强视频提示词。默认MiniMax-H3、6秒、768P；仅用户明确指定时换模型。operation为generation、h3_context_ir、enhance_then_generate；h3_context_ir仅增强不生成，只有用户同时明确要求优化并生成时才用enhance_then_generate。referencesJson为[{\"resourceId\":\"ID\",\"role\":\"first_frame/last_frame/reference_image/reference_video/reference_audio\"}]；未传时使用当前消息素材，单图默认首帧、多图默认参考，首尾帧不得混用参考模式。视频4至15秒（Max最短5秒），2K仅H3。静态图片编辑不要用此工具。",
            concurrencySafe = false)
    public String generateVideo(
            RuntimeContext context,
            @ToolParam(name = "prompt", description = "生成描述或要优化的视频提示词", required = true)
                    String prompt,
            @ToolParam(
                            name = "referencesJson",
                            description = "可选素材JSON；仅用当前用户资源ID，不接受任意外部URL",
                            required = false)
                    String referencesJson,
            @ToolParam(
                            name = "operation",
                            description = "generation、h3_context_ir或enhance_then_generate",
                            required = false)
                    String operation,
            @ToolParam(name = "model", description = "可选；默认MiniMax-H3", required = false)
                    String model,
            @ToolParam(name = "durationSeconds", description = "可选；默认6，明确时长优先", required = false)
                    Integer durationSeconds,
            @ToolParam(name = "resolution", description = "可选；默认768P，明确要求2K时传2K", required = false)
                    String resolution,
            @ToolParam(name = "ratio", description = "可选；文生16:9，图生adaptive", required = false)
                    String ratio) {
        if (context == null || context.getUserId() == null)
            throw new IllegalStateException("缺少用户上下文");
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return actions.submit(
                user,
                session,
                null,
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
            description = "查询本应用当前用户的视频/增强提示词任务。用本地taskId，不是供应商ID；已成功IR从prompt读取结果。",
            concurrencySafe = false)
    public String queryVideoTask(
            RuntimeContext context,
            @ToolParam(name = "taskId", description = "本地任务ID", required = true) String taskId) {
        if (context == null || context.getUserId() == null)
            throw new IllegalStateException("缺少用户上下文");
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return actions.query(user, taskId);
    }

    @Tool(
            name = "list_video_tasks",
            description = "列出当前用户在本应用创建的视频任务，不得展示共享供应商账号的其他用户任务。",
            concurrencySafe = false)
    public String listVideoTasks(
            RuntimeContext context,
            @ToolParam(name = "page", description = "页码，从1开始", required = false) Integer page,
            @ToolParam(name = "limit", description = "可选数量1至100，默认20", required = false)
                    Integer limit,
            @ToolParam(
                            name = "status",
                            description = "可选queued/running/succeeded/failed/cancelled",
                            required = false)
                    String status,
            @ToolParam(name = "model", description = "可选模型", required = false) String model,
            @ToolParam(
                            name = "taskType",
                            description = "可选generation/h3_context_ir/regeneration",
                            required = false)
                    String taskType) {
        if (context == null || context.getUserId() == null)
            throw new IllegalStateException("缺少用户上下文");
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return actions.list(user, limit, page, status, model, taskType);
    }

    @Tool(
            name = "cancel_video_task",
            description = "仅当用户明确取消视频任务时调用。只有排队任务可取消，运行中无法取消；停止聊天不代表取消视频。响应action以供应商真实返回为准。",
            concurrencySafe = false)
    public String cancelVideoTask(
            RuntimeContext context,
            @ToolParam(name = "taskId", description = "本地任务ID", required = true) String taskId) {
        if (context == null || context.getUserId() == null)
            throw new IllegalStateException("缺少用户上下文");
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return actions.remove(user, taskId, false);
    }

    @Tool(
            name = "delete_video_task_record",
            description = "仅当用户明确要求删除供应商任务记录时调用；仅成功/失败记录可删除，不删除本地已保存视频。不能用于取消视频。",
            concurrencySafe = false)
    public String deleteVideoTaskRecord(
            RuntimeContext context,
            @ToolParam(name = "taskId", description = "本地任务ID", required = true) String taskId) {
        if (context == null || context.getUserId() == null)
            throw new IllegalStateException("缺少用户上下文");
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return actions.remove(user, taskId, true);
    }

    @Tool(
            name = "regenerate_video2_k",
            description = "将本应用已完成的H3 768P视频重新生成为2K。不是任意视频超分或剪辑；要求本地源任务ID。",
            concurrencySafe = false)
    public String regenerateVideo2K(
            RuntimeContext context,
            @ToolParam(name = "sourceTaskId", description = "本地源视频任务ID", required = true)
                    String sourceTaskId) {
        if (context == null || context.getUserId() == null)
            throw new IllegalStateException("缺少用户上下文");
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return actions.regenerate(user, session, null, sourceTaskId);
    }

    @Tool(
            name = "list_video_inputs",
            description = "列出当前消息的图片、视频、音频资源ID，供生成或提示词增强使用；首尾帧角色必须遵从用户意图。",
            concurrencySafe = false)
    public String listVideoInputs(RuntimeContext context) {
        if (context == null || context.getUserId() == null)
            throw new IllegalStateException("缺少用户上下文");
        Long user = Long.valueOf(context.getUserId());
        String session = context.getSessionId();
        return actions.inputs(user, session);
    }
}
