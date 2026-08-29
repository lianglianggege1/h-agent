package com.h.agent.observability.langchain4j;

import com.h.agent.observability.semantic.SemanticBlock;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import com.h.agent.observability.semantic.ToolCallBlock;
import com.h.agent.observability.semantic.ToolResultBlock;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

final class Lc4jSemantic {

    private Lc4jSemantic() {
    }

    static SemanticContent fromMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        List<SemanticMessage> semanticMessages = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            SemanticMessage semantic = fromMessage(message);
            if (semantic != null) {
                semanticMessages.add(semantic);
            }
        }
        if (semanticMessages.isEmpty()) {
            return null;
        }
        return SemanticContent.ofMessages(semanticMessages);
    }

    static SemanticContent fromAiMessage(AiMessage message) {
        if (message == null) {
            return null;
        }
        List<SemanticBlock> blocks = new ArrayList<>();
        if (message.text() != null) {
            blocks.add(new com.h.agent.observability.semantic.TextBlock(message.text()));
        }
        if (message.toolExecutionRequests() != null) {
            for (ToolExecutionRequest request : message.toolExecutionRequests()) {
                blocks.add(new ToolCallBlock(request.id(), request.name(), request.arguments()));
            }
        }
        if (blocks.isEmpty()) {
            return null;
        }
        return SemanticContent.ofBlocks(blocks);
    }

    private static SemanticMessage fromMessage(ChatMessage message) {
        if (message instanceof SystemMessage systemMessage) {
            return new SemanticMessage("system",
                    List.of(new com.h.agent.observability.semantic.TextBlock(systemMessage.text())));
        }
        if (message instanceof UserMessage userMessage) {
            return new SemanticMessage("user",
                    List.of(new com.h.agent.observability.semantic.TextBlock(userMessage.singleText())));
        }
        if (message instanceof AiMessage aiMessage) {
            List<SemanticBlock> blocks = new ArrayList<>();
            if (aiMessage.text() != null) {
                blocks.add(new com.h.agent.observability.semantic.TextBlock(aiMessage.text()));
            }
            if (aiMessage.toolExecutionRequests() != null) {
                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    blocks.add(new ToolCallBlock(request.id(), request.name(), request.arguments()));
                }
            }
            if (blocks.isEmpty()) {
                return null;
            }
            return new SemanticMessage("assistant", blocks);
        }
        if (message instanceof ToolExecutionResultMessage toolMessage) {
            return new SemanticMessage("tool", List.of(new ToolResultBlock(
                    toolMessage.id(), toolMessage.toolName(), toolMessage.text(), false)));
        }
        return null;
    }
}
