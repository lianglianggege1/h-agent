package com.h.backend.generation.interfaces.tool;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.generation.application.service.MusicGenerationService;
import com.h.backend.generation.domain.model.MusicGenerationSpec;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/** Music tools belong to the general assistant, not the domain agents. */
@Component
public class MusicGenerationTool {
    private static final Logger log = LoggerFactory.getLogger(MusicGenerationTool.class);
    private final MusicGenerationService music;
    private final ChatMemoryIdFactory memoryIds;
    private final ChatSessionService sessions;
    private final ChatStreamEventBridge bridge;
    private final ObjectMapper json;

    public MusicGenerationTool(MusicGenerationService music, ChatMemoryIdFactory memoryIds,
            ChatSessionService sessions, ChatStreamEventBridge bridge, ObjectMapper json) {
        this.music = music;
        this.memoryIds = memoryIds;
        this.sessions = sessions;
        this.bridge = bridge;
        this.json = json;
    }

    @Tool(name = "generate_music", searchBehavior = SearchBehavior.ALWAYS_VISIBLE,
            value = "仅在用户明确要求把歌词唱出来、生成歌曲或写歌并演唱时调用。使用 MiniMax music-3.0 自动谱曲、伴奏及演唱，后台完成后在当前聊天提供试听和下载。已有歌词（包括当前对话刚写出的歌词）必须完整传入 lyrics，逐字保留、不改写、不增删，不得因超长擅自截断；仅可补充段落标签。用户只给主题并要求写歌且演唱时省略 lyrics，由 MiniMax 歌词接口先写词。未指定曲风和人声时根据歌词自行选择，不必追问。只要求写歌词时不要调用。每次明确请求只提交一次；提交后立即告知生成中，不等待、不自动重试、不轮询到完成。用户明确要求新版本时才能再次提交；超时或结果待确认不得自动重试。")
    public String generateMusic(@ToolMemoryId String memoryId,
            @P(value = "完整原文歌词，包含换行；有现成歌词时必须传入，禁止改写。最多3500字符（含标签）；仅从主题写歌时省略", required = false) String lyrics,
            @P(value = "曲风、情绪、人声等描述；从主题写歌时必须包含主题。最多2000字符。遵从用户指定，未指定时自行选择", required = false) String prompt,
            @P(value = "可选歌曲标题", required = false) String title) {
        var context = memoryIds.parse(memoryId);
        var result = music.submit(context.userId(), context.sessionId(), new MusicGenerationSpec(prompt, lyrics, title));
        try {
            bridge.publishMessage(memoryId, sessions.getOwnedMessage(context.userId(), context.sessionId(), result.chatMessageId()));
        } catch (RuntimeException ex) {
            // Acceptance is durable. A disconnected UI must not encourage another paid request.
            log.warn("Song task {} accepted; live message unavailable, history will recover it", result.taskId());
        }
        return json.writeValueAsString(Map.of("taskId", result.taskId(), "message",
                "歌曲生成中，可以继续聊天。完成后会自动出现在本对话中，支持试听和下载；请勿重复提交。"));
    }

    @Tool(name = "query_music_task", searchBehavior = SearchBehavior.ALWAYS_VISIBLE,
            value = "用户询问进度时查询当前用户、当前对话的本地歌曲任务。不会重新生成；不要循环查询或等待完成。")
    public String queryMusicTask(@ToolMemoryId String memoryId, String taskId) {
        var context = memoryIds.parse(memoryId);
        return json.writeValueAsString(music.query(context.userId(), context.sessionId(), taskId));
    }
}
