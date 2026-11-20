package com.h.otheragents.team.tool.langchain4j;

import com.h.capabilities.chat.application.WebSearchService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;

/** LangChain4j 原生联网搜索工具入口：业务委托共享 WebSearchService。 */
public class WorkerSearchTools {

    private final WebSearchService search;

    public WorkerSearchTools(WebSearchService search) {
        this.search = search;
    }

    @Tool(name = "web_search", value = "搜索互联网以获取最新信息，并返回答案摘要和来源链接。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String webSearch(
            @P("要搜索的问题或关键词") String query,
            @P(value = "最多返回的来源数量；0 表示使用默认值 5", required = false, defaultValue = "5") int maxResults) {
        return search.search(query, maxResults);
    }
}