package com.h.backend.outbound.infrastructure;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A deliberately small, read-only knowledge surface for customer-facing phone calls. */
@Component
public class PhoneBusinessKnowledgeTool {
    private final String publicKnowledge;

    public PhoneBusinessKnowledgeTool(
            @Value("${outbound.public-business-knowledge:未配置额外公开业务知识}") String publicKnowledge) {
        this.publicKnowledge = publicKnowledge;
    }

    @Tool(
            name = "lookup_business_knowledge",
            description = "查询允许在电话中向客户公开的业务知识。该工具只读，不访问用户私人文件或记忆。",
            concurrencySafe = true
    )
    public String lookup(
            @ToolParam(name = "question", description = "需要核实的业务问题") String question) {
        String normalized = question == null ? "" : question.strip();
        if (normalized.length() > 500) normalized = normalized.substring(0, 500);
        return "问题：%s\n可公开业务知识：%s".formatted(normalized, publicKnowledge);
    }
}
