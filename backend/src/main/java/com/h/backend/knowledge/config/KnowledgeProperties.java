package com.h.backend.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeProperties {

    private final Retriever retriever = new Retriever();
    private final Split split = new Split();
    private final Upload upload = new Upload();

    public Retriever getRetriever() { return retriever; }
    public Split getSplit() { return split; }
    public Upload getUpload() { return upload; }

    public static class Retriever {
        private int maxResults = 4;
        private double minScore = 0.6;
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public double getMinScore() { return minScore; }
        public void setMinScore(double minScore) { this.minScore = minScore; }
    }

    public static class Split {
        private int chunkSize = 300;
        private int chunkOverlap = 30;
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    }

    public static class Upload {
        private List<String> allowedTypes =
                List.of("md", "markdown", "txt", "doc", "docx", "xls", "xlsx");
        public List<String> getAllowedTypes() { return allowedTypes; }
        public void setAllowedTypes(List<String> allowedTypes) { this.allowedTypes = allowedTypes; }
    }
}
