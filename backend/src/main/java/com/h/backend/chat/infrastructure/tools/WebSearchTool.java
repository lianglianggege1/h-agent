package com.h.backend.chat.infrastructure.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchOrganicResult;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

@Component
public class WebSearchTool {

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS_LIMIT = 10;
    private static final String TAVILY_ANSWER_TITLE = "Tavily Search API";
    private static final URI TAVILY_ANSWER_URL = URI.create("https://tavily.com/");

    private final WebSearchEngine webSearchEngine;

    public WebSearchTool() {
        this(createSearchEngine());
    }

    WebSearchTool(WebSearchEngine webSearchEngine) {
        this.webSearchEngine = webSearchEngine;
    }

    @Tool(name = "web_search", value = "搜索互联网以获取最新信息，并返回答案摘要和来源链接。", searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String webSearch(
            @P("要搜索的问题或关键词") String query,
            @P(value = "最多返回的来源数量；0 表示使用默认值 5", required = false, defaultValue = "5") int maxResults
    ) {
        if (webSearchEngine == null) {
            return "Error: Tavily API key is not configured";
        }
        if (query == null || query.isBlank()) {
            return "Error: Search query must not be blank";
        }

        WebSearchRequest request = WebSearchRequest.from(query, normalizeMaxResults(maxResults));
        WebSearchResults results = webSearchEngine.search(request);
        return formatResults(results);
    }

    private static WebSearchEngine createSearchEngine() {
        String apiKey = loadTavilyApiKey(Path.of("").toAbsolutePath().normalize());
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return TavilyWebSearchEngine.builder()
                .apiKey(apiKey)
                .includeAnswer(true)
                .build();
    }

    static String loadTavilyApiKey(Path startingDirectory) {
        return loadTavilyApiKey(startingDirectory, System.getenv("TAVILY_API_KEY"));
    }

    static String loadTavilyApiKey(Path startingDirectory, String environmentApiKey) {
        if (environmentApiKey != null && !environmentApiKey.isBlank()) {
            return environmentApiKey;
        }

        Path normalizedDirectory = startingDirectory.toAbsolutePath().normalize();
        for (Path envPath : envFileCandidates(normalizedDirectory)) {
            String key = loadTavilyApiKeyFromFile(envPath);
            if (key != null && !key.isBlank()) {
                return key;
            }
        }
        return null;
    }

    private static List<Path> envFileCandidates(Path startingDirectory) {
        Path parent = startingDirectory.getParent();
        if (parent == null) {
            return List.of(startingDirectory.resolve(".env"));
        }
        return List.of(startingDirectory.resolve(".env"), parent.resolve(".env"));
    }

    private static String loadTavilyApiKeyFromFile(Path envPath) {
        if (!Files.exists(envPath)) {
            return null;
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(envPath)) {
            properties.load(reader);
            return properties.getProperty("TAVILY_API_KEY");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load .env file", ex);
        }
    }

    private static int normalizeMaxResults(int maxResults) {
        if (maxResults <= 0) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.min(maxResults, MAX_RESULTS_LIMIT);
    }

    private static String formatResults(WebSearchResults results) {
        List<WebSearchOrganicResult> organicResults = results.results();
        WebSearchOrganicResult answer = organicResults.stream()
                .filter(WebSearchTool::isTavilyAnswer)
                .findFirst()
                .orElse(null);
        List<WebSearchOrganicResult> sources = organicResults.stream()
                .filter(result -> !isTavilyAnswer(result))
                .toList();

        StringBuilder output = new StringBuilder();
        if (answer != null && answer.snippet() != null && !answer.snippet().isBlank()) {
            output.append("Answer:\n")
                    .append(answer.snippet().trim())
                    .append("\n\n");
        }

        output.append("Sources:");
        if (sources.isEmpty()) {
            output.append("\nNo search results found.");
            return output.toString();
        }

        for (int i = 0; i < sources.size(); i++) {
            WebSearchOrganicResult source = sources.get(i);
            output.append("\n")
                    .append(i + 1)
                    .append(". ")
                    .append(source.title())
                    .append("\n   URL: ")
                    .append(source.url());
            if (source.snippet() != null && !source.snippet().isBlank()) {
                output.append("\n   Snippet: ")
                        .append(source.snippet().trim());
            }
        }
        return output.toString();
    }

    private static boolean isTavilyAnswer(WebSearchOrganicResult result) {
        return TAVILY_ANSWER_TITLE.equals(result.title()) && TAVILY_ANSWER_URL.equals(result.url());
    }
}
