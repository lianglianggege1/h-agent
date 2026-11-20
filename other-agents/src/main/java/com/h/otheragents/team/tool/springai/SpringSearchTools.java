package com.h.otheragents.team.tool.springai;

import com.h.backend.chat.application.WebSearchService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Spring AI 原生联网搜索工具入口：业务委托共享 WebSearchService。 */
public class SpringSearchTools {

    private final WebSearchService search;

    public SpringSearchTools(WebSearchService search) {
        this.search = search;
    }

    @Tool(name = "web_search", description = "搜索互联网以获取最新信息，并返回答案摘要和来源链接。")
    public String webSearch(
            @ToolParam(description = "要搜索的问题或关键词") String query,
            @ToolParam(required = false, description = "最多返回的来源数量；0 表示使用默认值 5") Integer maxResults) {
        return search.search(query, maxResults == null ? 0 : maxResults);
    }
}