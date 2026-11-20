package com.h.backend.generation.interfaces.tool;

import com.h.capabilities.generation.interfaces.tool.TeamVideoGenerationTool;
import com.h.capabilities.generation.interfaces.tool.TeamImageGenerationTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/** AgentScope 团队生成工具（Manager），身份取自 RuntimeContext（人类账号 + 房间 scope）。 */
public class AgentScopeTeamGenerationTool {

    private final TeamImageGenerationTool images;
    private final TeamVideoGenerationTool videos;

    public AgentScopeTeamGenerationTool(TeamImageGenerationTool images, TeamVideoGenerationTool videos) {
        this.images = images;
        this.videos = videos;
    }

    @Tool(
            name = "generate_image",
            description = "生成图片并直接发送到当前聊天室，返回 mxc:// 地址可供消息正文引用。"
                    + "referenceMxc 传房间图片的 mxc:// 地址可作主体参考（保持人物/物体一致）。",
            concurrencySafe = false)
    public String generateImage(
            RuntimeContext context,
            @ToolParam(name = "prompt", description = "图片内容描述，细节越具体效果越好", required = true)
                    String prompt,
            @ToolParam(name = "referenceMxc", description = "可选参考图 mxc:// 地址", required = false)
                    String referenceMxc,
            @ToolParam(name = "aspectRatio", description = "可选宽高比，如 16:9、1:1、9:16", required = false)
                    String aspectRatio) {
        requireUser(context);
        return images.generate(
                Long.valueOf(context.getUserId()), context.getSessionId(), prompt, referenceMxc, aspectRatio);
    }

    @Tool(
            name = "generate_video",
            description = "创建 MiniMax-H3 视频生成任务（文生视频或图生视频），提交后立即返回 taskId，"
                    + "完成后视频自动发送到当前聊天室。默认6秒、768P；时长4至15秒。"
                    + "referencesJson 为 [{\"resourceId\":\"mxc://...\",\"role\":\"first_frame/reference_image\"}]，"
                    + "素材必须是房间媒体的 mxc:// 地址；首尾帧与参考素材不能混用。",
            concurrencySafe = false)
    public String generateVideo(
            RuntimeContext context,
            @ToolParam(name = "prompt", description = "视频内容描述", required = true) String prompt,
            @ToolParam(name = "referencesJson", description = "可选素材JSON数组", required = false)
                    String referencesJson,
            @ToolParam(name = "model", description = "可选模型，默认MiniMax-H3", required = false) String model,
            @ToolParam(name = "durationSeconds", description = "可选时长秒数，默认6", required = false)
                    Integer durationSeconds,
            @ToolParam(name = "resolution", description = "可选分辨率480P/768P/2K，默认768P", required = false)
                    String resolution,
            @ToolParam(name = "ratio", description = "可选比例，如16:9、9:16、adaptive", required = false)
                    String ratio) {
        requireUser(context);
        return videos.submit(
                Long.valueOf(context.getUserId()), context.getSessionId(), prompt, referencesJson,
                model, durationSeconds, resolution, ratio);
    }

    @Tool(
            name = "query_video_task",
            description = "按本地 taskId 查询视频任务状态与结果。",
            concurrencySafe = false)
    public String queryVideoTask(
            RuntimeContext context,
            @ToolParam(name = "taskId", description = "generate_video 返回的本地任务ID", required = true)
                    String taskId) {
        requireUser(context);
        return videos.query(Long.valueOf(context.getUserId()), taskId);
    }

    private static void requireUser(RuntimeContext context) {
        if (context == null || context.getUserId() == null) {
            throw new IllegalStateException("缺少用户上下文");
        }
    }
}