package com.h.backend.memory.infrastructure.langchain4j;

import com.h.backend.memory.application.LongTermMemoryRuntime;
import com.h.backend.memory.domain.AgentMemoryPolicy;
import com.h.backend.memory.domain.AgentMemoryPolicyCatalog;
import com.h.backend.memory.domain.MemoryInvocationContext;
import com.h.backend.memory.domain.MemoryRecallCommand;
import com.h.backend.memory.domain.MemoryRecallResult;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆的 LangChain4j Adapter。从 Query.metadata().invocationParameters()
 * 读取服务端可信身份；缺少身份时返回空结果并记录安全告警，禁止降级成
 * 无 owner 过滤的 Mem0 搜索。
 */
public class LongTermMemoryContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryContentRetriever.class);

    private final LongTermMemoryRuntime runtime;
    private final AgentMemoryPolicyCatalog policyCatalog;
    /** 构建时绑定的稳定逻辑 Agent ID；叶子 Agent 召回时用于替换身份中的 agent 字段。 */
    private final String boundLogicalAgentId;

    public LongTermMemoryContentRetriever(LongTermMemoryRuntime runtime,
                                          AgentMemoryPolicyCatalog policyCatalog,
                                          String boundLogicalAgentId) {
        this.runtime = runtime;
        this.policyCatalog = policyCatalog;
        this.boundLogicalAgentId = boundLogicalAgentId;
    }

    @Override
    public List<Content> retrieve(Query query) {
        MemoryInvocationContext context = readContext(query);
        if (context == null) {
            log.warn("Long-term memory recall skipped: trusted invocation context is missing agentId={}",
                    boundLogicalAgentId);
            return List.of();
        }
        MemoryInvocationContext scoped = context.withLogicalAgentId(boundLogicalAgentId);
        AgentMemoryPolicy policy = policyCatalog.policyOf(boundLogicalAgentId);
        if (!policy.recallEnabled()) {
            return List.of();
        }
        MemoryRecallResult result = runtime.recall(new MemoryRecallCommand(
                scoped, policy.recallScopes(), query.text()));
        List<Content> contents = new ArrayList<>();
        for (MemoryRecallResult.MemoryItem item : result.items()) {
            dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata()
                    .put("source", "long-term-memory")
                    .put("scope", item.scopeKind().name());
            if (item.remoteMemoryId() != null) {
                metadata.put("memoryId", item.remoteMemoryId());
            }
            contents.add(Content.from(dev.langchain4j.data.segment.TextSegment.from(item.text(), metadata)));
        }
        return contents;
    }

    private MemoryInvocationContext readContext(Query query) {
        if (query == null || query.metadata() == null) {
            return null;
        }
        return MemoryInvocationContext.from(query.metadata().invocationParameters());
    }
}
