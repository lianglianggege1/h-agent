package com.h.otheragents.team.tool.springai;

import com.h.capabilities.generation.interfaces.tool.TeamImageGenerationTool;
import com.h.capabilities.generation.interfaces.tool.TeamVideoGenerationTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Spring AI 原生团队生成工具入口：身份取自 ToolContext，业务委托共享生成工具。 */
public class SpringGenerationTools {

    private final TeamImageGenerationTool images;
    private final TeamVideoGenerationTool videos;

    public SpringGenerationTools(TeamImageGenerationTool images, TeamVideoGenerationTool videos) {
        this.images = images;
        this.videos = videos;
    }

    @Tool(name = "generate_image", description = "生成图片并直接发送到当前聊天室，返回 mxc:// 地址可供消息正文引用。"
            + "referenceMxc 传房间图片的 mxc:// 地址可作主体参考（保持人物/物体一致）。")
    public String generateImage(
            ToolContext toolContext,
            @ToolParam(description = "图片内容描述，细节越具体效果越好") String prompt,
            @ToolParam(required = false, description = "可选参考图 mxc:// 地址") String referenceMxc,
            @ToolParam(required = false, description = "可选宽高比，如 16:9、1:1、9:16") String aspectRatio) {
        SpringToolContext ctx = SpringToolContext.from(toolContext);
        return images.generate(ctx.userId(), ctx.roomId(), prompt, referenceMxc, aspectRatio);
    }

    @Tool(name = "generate_video", description = "创建 MiniMax-H3 视频生成任务（文生视频或图生视频），提交后立即返回 taskId，"
            + "完成后视频自动发送到当前聊天室。默认6秒、768P；时长4至15秒。"
            + "referencesJson 为 [{\"resourceId\":\"mxc://...\",\"role\":\"first_frame/reference_image\"}]，"
            + "素材必须是房间媒体的 mxc:// 地址；首尾帧与参考素材不能混用。")
    public String generateVideo(
            ToolContext toolContext,
            @ToolParam(description = "视频内容描述") String prompt,
            @ToolParam(required = false, description = "可选素材JSON数组") String referencesJson,
            @ToolParam(required = false, description = "可选模型，默认MiniMax-H3") String model,
            @ToolParam(required = false, description = "可选时长秒数，默认6") Integer durationSeconds,
            @ToolParam(required = false, description = "可选分辨率480P/768P/2K，默认768P") String resolution,
            @ToolParam(required = false, description = "可选比例，如16:9、9:16、adaptive") String ratio) {
        SpringToolContext ctx = SpringToolContext.from(toolContext);
        return videos.submit(ctx.userId(), ctx.roomId(), prompt, referencesJson,
                model, durationSeconds, resolution, ratio);
    }

    @Tool(name = "query_video_task", description = "按本地 taskId 查询视频任务状态与结果。")
    public String queryVideoTask(
            ToolContext toolContext,
            @ToolParam(description = "generate_video 返回的本地任务ID") String taskId) {
        SpringToolContext ctx = SpringToolContext.from(toolContext);
        return videos.query(ctx.userId(), taskId);
    }
}