package com.h.backend.chat.infrastructure.tools;

import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchInformationResult;
import dev.langchain4j.web.search.WebSearchOrganicResult;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldFormatTavilyAnswerAndSources() {
        FakeWebSearchEngine searchEngine = new FakeWebSearchEngine(WebSearchResults.from(
                WebSearchInformationResult.from(2L),
                List.of(
                        WebSearchOrganicResult.from(
                                "Tavily Search API",
                                URI.create("https://tavily.com/"),
                                "LangChain4j is a Java framework for LLM applications.",
                                null
                        ),
                        WebSearchOrganicResult.from(
                                "LangChain4j Docs",
                                URI.create("https://docs.langchain4j.dev/"),
                                "Documentation for building Java AI services.",
                                null
                        )
                )
        ));
        WebSearchTool tool = new WebSearchTool(searchEngine);

        String result = tool.webSearch("What is LangChain4j?", 2);

        assertEquals("What is LangChain4j?", searchEngine.request.searchTerms());
        assertEquals(2, searchEngine.request.maxResults());
        assertTrue(result.contains("Answer:\nLangChain4j is a Java framework for LLM applications."));
        assertTrue(result.contains("Sources:"));
        assertTrue(result.contains("1. LangChain4j Docs"));
        assertTrue(result.contains("URL: https://docs.langchain4j.dev/"));
        assertTrue(result.contains("Snippet: Documentation for building Java AI services."));
    }

    @Test
    void shouldReturnConfigurationErrorWhenSearchEngineIsMissing() {
        WebSearchTool tool = new WebSearchTool(null);

        String result = tool.webSearch("latest LangChain4j release", 5);

        assertEquals("Error: Tavily API key is not configured", result);
    }

    @Test
    void shouldLoadTavilyApiKeyFromParentEnvFileWhenStartedInBackendDirectory() throws Exception {
        Path backendDir = Files.createDirectory(tempDir.resolve("backend"));
        Files.writeString(tempDir.resolve(".env"), "TAVILY_API_KEY=parent-key\n");

        String apiKey = WebSearchTool.loadTavilyApiKey(backendDir, null);

        assertEquals("parent-key", apiKey);
    }

    private static class FakeWebSearchEngine implements WebSearchEngine {
        private final WebSearchResults results;
        private WebSearchRequest request;

        private FakeWebSearchEngine(WebSearchResults results) {
            this.results = results;
        }

        @Override
        public WebSearchResults search(WebSearchRequest webSearchRequest) {
            this.request = webSearchRequest;
            return results;
        }
    }
}
