package com.h.backend.chat.infrastructure.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 缺少 .env 时保留应用启动能力，但在真正调用时给出明确错误。
 * 这与现有 LangChain4j DisabledStreamingChatModel 的开发期语义一致。
 */
public class DisabledHarnessModel implements Model {

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.error(new IllegalStateException("Harness Agent is disabled because .env is missing"));
    }

    @Override
    public String getModelName() {
        return "disabled-harness-model";
    }
}
