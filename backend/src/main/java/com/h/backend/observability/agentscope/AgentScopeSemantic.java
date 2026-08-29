package com.h.backend.observability.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.semantic.SemanticBlock;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import com.h.agent.observability.semantic.TextBlock;
import com.h.agent.observability.semantic.ThinkingBlock;
import com.h.agent.observability.semantic.ToolCallBlock;
import com.h.agent.observability.semantic.ToolResultBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentScope 消息/内容块到统一语义内容模型的转换（设计 5.2 / 12.2）。
 *
 * <p>只读转换，不修改任何原始对象；工具入参序列化为 JSON 字符串，超限内容由
 * {@code SemanticJson} 统一裁剪。</p>
 */
final class AgentScopeSemantic {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentScopeSemantic() {
    }

    static SemanticContent fromMsgs(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return SemanticContent.empty();
        }
        List<SemanticMessage> messages = new ArrayList<>(msgs.size());
        for (Msg msg : msgs) {
            messages.add(new SemanticMessage(role(msg), blocks(msg.getContent())));
        }
        return SemanticContent.ofMessages(messages);
    }

    static SemanticContent fromMsg(Msg msg) {
        if (msg == null) {
            return SemanticContent.empty();
        }
        return SemanticContent.ofMessages(
                List.of(new SemanticMessage(role(msg), blocks(msg.getContent()))));
    }

    static SemanticContent fromToolUse(ToolUseBlock toolUse) {
        if (toolUse == null) {
            return SemanticContent.empty();
        }
        return SemanticContent.ofBlocks(List.of(toolCall(toolUse)));
    }

    static ToolCallBlock toolCall(ToolUseBlock toolUse) {
        return new ToolCallBlock(toolUse.getId(), toolUse.getName(), write(toolUse.getInput()));
    }

    static ToolResultBlock toolResult(io.agentscope.core.message.ToolResultBlock result) {
        return new ToolResultBlock(
                result.getId(),
                result.getName(),
                write(result.getOutput()),
                false);
    }

    private static List<SemanticBlock> blocks(List<ContentBlock> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        List<SemanticBlock> blocks = new ArrayList<>(contents.size());
        for (ContentBlock content : contents) {
            SemanticBlock block = block(content);
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    private static SemanticBlock block(ContentBlock content) {
        if (content instanceof io.agentscope.core.message.TextBlock text) {
            return new TextBlock(text.getText());
        }
        if (content instanceof io.agentscope.core.message.ThinkingBlock thinking) {
            return new ThinkingBlock(thinking.getThinking());
        }
        if (content instanceof ToolUseBlock toolUse) {
            return toolCall(toolUse);
        }
        if (content instanceof io.agentscope.core.message.ToolResultBlock toolResult) {
            return toolResult(toolResult);
        }
        return null;
    }

    private static String role(Msg msg) {
        MsgRole role = msg == null ? null : msg.getRole();
        return role == null ? "unknown" : role.name().toLowerCase();
    }

    private static String write(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
