package com.h.otheragents.team.tool.langchain4j;

import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.generation.interfaces.tool.TeamImageGenerationTool;
import com.h.backend.generation.interfaces.tool.TeamVideoGenerationTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;

/** LangChain4j 原生团队生成工具入口：身份取自框架注入的 memoryId，业务委托共享生成工具。 */
public class WorkerGenerationTools {

    private final TeamImageGenerationTool images;
    private final TeamVideoGenerationTool videos;
    private final ChatMemoryIdFactory memoryIds;

    public WorkerGenerationTools(TeamImageGenerationTool images, TeamVideoGenerationTool videos,
                                 ChatMemoryIdFactory memoryIds) {
        this.images = images;
        this.videos = videos;
        this.memoryIds = memoryIds;
    }

    @Tool(name = "generate_image", value = "生成图片并直接发送到当前聊天室，返回 mxc:// 地址可供消息正文引用。"
            + "referenceMxc 传房间图片的 mxc:// 地址可作主体参考（保持人物/物体一致）。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String generateImage(
            @ToolMemoryId String memoryId,
            @P("图片内容描述，细节越具体效果越好") String prompt,
            @P(value = "可选参考图 mxc:// 地址", required = false) String referenceMxc,
            @P(value = "可选宽高比，如 16:9、1:1、9:16", required = false) String aspectRatio) {
        var context = memoryIds.parse(memoryId);
        return images.generate(context.userId(), context.sessionId(), prompt, referenceMxc, aspectRatio);
    }

    @Tool(name = "generate_video", value = "创建 MiniMax-H3 视频生成任务（文生视频或图生视频），提交后立即返回 taskId，"
            + "完成后视频自动发送到当前聊天室。默认6秒、768P；时长4至15秒。"
            + "referencesJson 为 [{\"resourceId\":\"mxc://...\",\"role\":\"first_frame/reference_image\"}]，"
            + "素材必须是房间媒体的 mxc:// 地址；首尾帧与参考素材不能混用。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String generateVideo(
            @ToolMemoryId String memoryId,
            @P("视频内容描述") String prompt,
            @P(value = "可选素材JSON数组", required = false) String referencesJson,
            @P(value = "可选模型，默认MiniMax-H3", required = false) String model,
            @P(value = "可选时长秒数，默认6", required = false) Integer durationSeconds,
            @P(value = "可选分辨率480P/768P/2K，默认768P", required = false) String resolution,
            @P(value = "可选比例，如16:9、9:16、adaptive", required = false) String ratio) {
        var context = memoryIds.parse(memoryId);
        return videos.submit(context.userId(), context.sessionId(), prompt, referencesJson,
                model, durationSeconds, resolution, ratio);
    }

    @Tool(name = "query_video_task", value = "按本地 taskId 查询视频任务状态与结果。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String queryVideoTask(
            @ToolMemoryId String memoryId,
            @P("generate_video 返回的本地任务ID") String taskId) {
        var context = memoryIds.parse(memoryId);
        return videos.query(context.userId(), taskId);
    }
}